package org.failmapper.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Deterministic full-loop tests of the {@link FaMcts} orchestrator with a FAKE
 * {@link FaMcts.ActionApplier} and {@link FaMcts.BugVerifier} — no LLM, no compiler,
 * no network. Covers the D4-D8 loop, D14 gating, D7 bug dedup + signatures, the
 * post-loop batch verification, and termination.
 */
class FaMctsTest {

    private static final String TEST_CODE_ROOT = """
            public class FooTest {
                @Test
                public void testNothing() {
                    assertTrue(true);
                }
            }
            """;

    private static final String TEST_CODE_WITH_BUG = """
            public class FooTest {
                @Test
                public void testAdd() {
                    if (true) {
                        assertEquals(5, Foo.add(2, 2));
                    }
                }
            }
            """;

    /** Applier that pops pre-built states and records the (action, parent) calls. */
    private static final class ScriptedApplier implements FaMcts.ActionApplier {
        final Deque<FaTestState> states = new ArrayDeque<>();
        final List<SearchAction> appliedActions = new ArrayList<>();
        final List<FaTestState> parents = new ArrayList<>();

        ScriptedApplier add(FaTestState... s) {
            for (FaTestState state : s) {
                states.add(state);
            }
            return this;
        }

        @Override
        public FaTestState apply(SearchAction action, FaTestState parentState) {
            appliedActions.add(action);
            parents.add(parentState);
            return states.isEmpty() ? null : states.poll();
        }
    }

    /** Verifier that records its input and marks every method a real bug. */
    private static final class RecordingVerifier implements FaMcts.BugVerifier {
        final List<List<MethodToVerify>> calls = new ArrayList<>();
        boolean verdictRealBug = true;

        @Override
        public List<VerifiedBugMethod> verifyBatch(List<MethodToVerify> methods) {
            calls.add(methods);
            List<VerifiedBugMethod> out = new ArrayList<>();
            for (MethodToVerify method : methods) {
                VerifiedBugMethod v = new VerifiedBugMethod();
                v.methodName = method.methodName();
                v.methodCode = method.methodCode();
                v.bugSignature = method.bugSignature();
                v.bugType = method.bugInfo().isEmpty() ? "unknown"
                        : method.bugInfo().get(0).bugType;
                v.verified = true;
                v.isRealBug = verdictRealBug;
                v.verificationConfidence = 0.8;
                v.verificationReasoning = "scripted";
                out.add(v);
            }
            return out;
        }
    }

    private static FaTestState plainState(String testCode, double coverage) {
        FaTestState s = new FaTestState(testCode, null, null);
        s.coverage = coverage;
        return s;
    }

    private static FaTestState bugState(String testCode, double coverage,
                                        String method, String error) {
        FaTestState s = plainState(testCode, coverage);
        DetectedBug bug = new DetectedBug(
                "assertion_failure", "assertion failed", method, error, "medium");
        bug.bugCategory = "logical";
        bug.bugType = "incorrect_behavior";
        s.detectedBugs.add(bug);
        s.logicalBugs.add(bug);
        s.hasBugs = true;
        return s;
    }

    private static FaMcts mcts(SearchConfig config, FaTestState rootState,
                               FaMcts.ActionApplier applier, FaMcts.BugVerifier verifier,
                               RandomSource random) {
        return new FaMcts(
                config,
                rootState,
                new ActionGenerator(config, random),
                new SelectionPolicy(config, random),
                applier,
                new RewardCalculator(config),
                new UpdateBestPolicy(config, rootState.testCode, rootState.coverage),
                new TerminationPolicy(config),
                verifier,
                random,
                FaMcts.SearchContext.empty());
    }

    // ------------------------------------------------------------------
    // Full loop: node statistics, D14 updates, D7 collection, verification
    // ------------------------------------------------------------------

    @Test
    void fullLoopUpdatesNodeStatisticsBestTestAndVerifiesBugs() {
        SearchConfig config = SearchConfig.builder().maxIterations(3).build();

        FaTestState rootState = plainState(TEST_CODE_ROOT, 0.0);
        FaTestState s1 = plainState(TEST_CODE_ROOT, 50.0);
        FaTestState s2 = bugState(TEST_CODE_WITH_BUG, 60.0, "testAdd",
                "org.opentest4j.AssertionFailedError: expected: <5> but was: <4>");
        FaTestState s3 = plainState(TEST_CODE_ROOT, 60.0);

        ScriptedApplier applier = new ScriptedApplier().add(s1, s2, s3);
        RecordingVerifier verifier = new RecordingVerifier();

        // One general-exploration draw (0.9 -> no extra action) + one choice int per
        // expansion; anything else drawn would throw (script exhaustion check).
        FixedRandomSource random = new FixedRandomSource()
                .doubles(0.9, 0.9, 0.9).ints(0, 0, 0);

        FaMcts search = mcts(config, rootState, applier, verifier, random);
        FaMcts.SearchResult result = search.runSearch();

        assertThat(random.exhausted()).isTrue();

        // C46 default strategies without an f_model yield exactly one exception_test
        // action per expansion; all three applies see it.
        assertThat(applier.appliedActions).hasSize(3);
        assertThat(applier.appliedActions).allSatisfy(
                a -> assertThat(a.type()).isEqualTo("exception_test"));
        assertThat(applier.parents).containsExactly(rootState, s1, s2);

        // Tree shape: root -> c1 -> c2 -> c3 (each expansion consumed the only action).
        FaMctsNode root = search.root();
        assertThat(root.children).hasSize(1);
        FaMctsNode c1 = root.children.get(0);
        assertThat(c1.children).hasSize(1);
        FaMctsNode c2 = c1.children.get(0);
        assertThat(c2.children).hasSize(1);
        FaMctsNode c3 = c2.children.get(0);

        // D8 backprop statistics. Rewards (F5-F7, focusOnBugs weights):
        //   iter1: 0.2*0.5                     = 0.10
        //   iter2: 0.2*0.6 + 0.3*(0.5+0.4*1)   = 0.39
        //   iter3: 0.2*0.6                     = 0.12
        assertThat(root.visits).isEqualTo(3);
        assertThat(c1.visits).isEqualTo(3);
        assertThat(c2.visits).isEqualTo(2);
        assertThat(c3.visits).isEqualTo(1);
        assertThat(root.wins).isCloseTo(0.61, within(1e-9));
        assertThat(c1.wins).isCloseTo(0.61, within(1e-9));
        assertThat(c2.wins).isCloseTo(0.51, within(1e-9));
        assertThat(c3.wins).isCloseTo(0.12, within(1e-9));

        // F4: the logical bug propagated 'logical_incorrect_behavior' up the whole chain
        // in iteration 2 only.
        assertThat(root.logicBugRewards).isEqualTo(1.0);
        assertThat(c1.logicBugRewards).isEqualTo(1.0);
        assertThat(c2.logicBugRewards).isEqualTo(1.0);
        assertThat(c3.logicBugRewards).isEqualTo(0.0);
        assertThat(c2.bugsFound).isEqualTo(1);

        // D14: best test from iteration 2 (reward 0.39, coverage 60); iteration 3's
        // 0.12 is gated by reward > best_reward (fa_mcts.py:1147).
        assertThat(result.bestTestCode()).isEqualTo(TEST_CODE_WITH_BUG);
        assertThat(result.bestCoverage()).isEqualTo(60.0);
        assertThat(result.bestReward()).isCloseTo(0.39, within(1e-9));

        // History: one entry per iteration, rewards rounded to 5 places.
        assertThat(result.history()).hasSize(3);
        assertThat(result.history().get(0).reward()).isEqualTo(0.1);
        assertThat(result.history().get(1).reward()).isEqualTo(0.39);
        assertThat(result.history().get(2).reward()).isEqualTo(0.12);

        // D7: one potential bug with the Python-oracle signature
        // (md5 of "expected:5_but_was:4", computed with CPython hashlib).
        assertThat(result.potentialBugs()).hasSize(1);
        PotentialBug bug = result.potentialBugs().get(0);
        assertThat(bug.bugSignature).isEqualTo("testAdd:0cd5c944d95f");
        assertThat(bug.bugType).isEqualTo("assertion_failure"); // D7 quirk: from "type"
        assertThat(bug.foundInIteration).isEqualTo(2);
        assertThat(bug.methodCode).contains("public void testAdd()");
        assertThat(bug.originalTestCode).isEqualTo(TEST_CODE_WITH_BUG);

        // Batch verification ran once with the grouped method.
        assertThat(verifier.calls).hasSize(1);
        List<MethodToVerify> methods = verifier.calls.get(0);
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).methodName()).isEqualTo("testAdd");
        assertThat(methods.get(0).bugSignature()).isEqualTo("testAdd:0cd5c944d95f");
        assertThat(methods.get(0).bugDescriptions())
                .containsExactly("assertion_failure: org.opentest4j.AssertionFailedError:"
                        + " expected: <5> but was: <4>");
        assertThat(methods.get(0).methodCode()).contains("testAdd");

        assertThat(result.verifiedBugMethods()).hasSize(1);
        assertThat(result.verifiedBugMethods().get(0).isRealBug).isTrue();
        assertThat(result.realBugsCount()).isEqualTo(1);
        assertThat(result.iterationsRun()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // D7 — bug dedup by signature
    // ------------------------------------------------------------------

    @Test
    void duplicateBugsAcrossIterationsAreDedupedBySignature() {
        SearchConfig config = SearchConfig.builder().maxIterations(3).build();

        FaTestState rootState = plainState(TEST_CODE_ROOT, 0.0);
        String error = "java.lang.IllegalStateException: boom @1a2b3c";
        // Same method + same error (module a differing memory address) twice, then a
        // genuinely different error.
        FaTestState s1 = bugState(TEST_CODE_WITH_BUG, 50.0, "testAdd", error);
        FaTestState s2 = bugState(TEST_CODE_WITH_BUG, 50.0, "testAdd",
                "java.lang.IllegalStateException: boom @ffff00");
        FaTestState s3 = bugState(TEST_CODE_WITH_BUG, 50.0, "testAdd",
                "java.lang.ArithmeticException: / by zero");

        ScriptedApplier applier = new ScriptedApplier().add(s1, s2, s3);
        RecordingVerifier verifier = new RecordingVerifier();
        FixedRandomSource random = new FixedRandomSource()
                .doubles(0.9, 0.9, 0.9).ints(0, 0, 0);

        FaMcts search = mcts(config, rootState, applier, verifier, random);
        FaMcts.SearchResult result = search.runSearch();

        // The address-differing duplicate deduped (cleaning strips @hex); the
        // different-exception bug kept: IllegalStateException vs ArithmeticException
        // reduce to different exception-type cores.
        assertThat(result.potentialBugs()).hasSize(2);
        assertThat(result.potentialBugs().get(0).error).isEqualTo(error);
        assertThat(result.potentialBugs().get(1).error)
                .isEqualTo("java.lang.ArithmeticException: / by zero");

        // Both group under testAdd -> ONE method to verify, first bug's signature used
        // (fa_mcts.py:1270, contract O6).
        assertThat(verifier.calls).hasSize(1);
        assertThat(verifier.calls.get(0)).hasSize(1);
        MethodToVerify method = verifier.calls.get(0).get(0);
        assertThat(method.bugInfo()).hasSize(2);
        assertThat(method.bugSignature()).isEqualTo(result.potentialBugs().get(0).bugSignature);
        assertThat(method.bugDescriptions()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // D7 — pre-verified assertion failures still reach batch verification
    // (the live evaluator marks them verified=true, test_state.py:181, but the
    // Python D7 bug_info dict has no "verified" key, fa_mcts.py:3044-3062)
    // ------------------------------------------------------------------

    @Test
    void preVerifiedAssertionFailureIsStillQueuedForBatchVerification() {
        SearchConfig config = SearchConfig.builder().maxIterations(1).build();

        FaTestState rootState = plainState(TEST_CODE_ROOT, 0.0);
        // Exactly what DefaultEvaluator produces for a failing assertion
        // (test_state.py:175-185): pre-verified, is_real_bug=true, logical.
        FaTestState s1 = plainState(TEST_CODE_WITH_BUG, 50.0);
        DetectedBug bug = new DetectedBug(
                "assertion_failure", "expected: <5> but was: <4>", "testAdd",
                "AssertionError", "medium");
        bug.verified = true;
        bug.isRealBug = Boolean.TRUE;
        bug.bugCategory = "logical";
        bug.bugType = "incorrect_behavior";
        s1.detectedBugs.add(bug);
        s1.logicalBugs.add(bug);
        s1.hasBugs = true;

        ScriptedApplier applier = new ScriptedApplier().add(s1);
        RecordingVerifier verifier = new RecordingVerifier();
        FixedRandomSource random = new FixedRandomSource().doubles(0.9).ints(0);

        FaMcts search = mcts(config, rootState, applier, verifier, random);
        FaMcts.SearchResult result = search.runSearch();

        // The D7 potential bug does NOT inherit the verified flag (no "verified" key
        // in the Python bug_info dict) ...
        assertThat(result.potentialBugs()).hasSize(1);
        assertThat(result.potentialBugs().get(0).verified).isFalse();

        // ... so the bug is queued and the verifier IS called, with the method code
        // extracted at collection time (fa_mcts.py:3050) flowing through.
        assertThat(verifier.calls).hasSize(1);
        assertThat(verifier.calls.get(0)).hasSize(1);
        MethodToVerify method = verifier.calls.get(0).get(0);
        assertThat(method.methodName()).isEqualTo("testAdd");
        assertThat(method.methodCode()).contains("public void testAdd()");
        assertThat(method.bugInfo()).hasSize(1);

        assertThat(result.verifiedBugMethods()).hasSize(1);
        assertThat(result.realBugsCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // D7/D9 — unextractable method code: verification still dispatched with a
    // null code, mirroring Python's method_code=None path (fa_mcts.py:1239-1250
    // leaves code=None; bug_verifier.py:167 only skips when the KEY is absent)
    // ------------------------------------------------------------------

    @Test
    void unextractableMethodCodeStillReachesVerifierWithNullCode() {
        SearchConfig config = SearchConfig.builder().maxIterations(1).build();

        FaTestState rootState = plainState(TEST_CODE_ROOT, 0.0);
        // The failing method name does not exist in the test code -> the D7
        // extraction yields "" (fa_mcts.py:3050 / TestMethodExtractor).
        FaTestState s1 = bugState(TEST_CODE_WITH_BUG, 50.0, "testGhost",
                "java.lang.IllegalStateException: boom");
        s1.detectedBugs.get(0).verified = true; // pre-verified must not matter

        ScriptedApplier applier = new ScriptedApplier().add(s1);
        RecordingVerifier verifier = new RecordingVerifier();
        FixedRandomSource random = new FixedRandomSource().doubles(0.9).ints(0);

        FaMcts search = mcts(config, rootState, applier, verifier, random);
        search.runSearch();

        assertThat(verifier.calls).hasSize(1);
        assertThat(verifier.calls.get(0)).hasSize(1);
        MethodToVerify method = verifier.calls.get(0).get(0);
        assertThat(method.methodName()).isEqualTo("testGhost");
        // First-bug-with-code loop finds none -> code stays null (fa_mcts.py:1239-1250);
        // the D9 implementation then resolves it per verify_bug_with_llm.py:32-39
        // (insufficient input, no LLM call) — see LlmBugVerifierTest.
        assertThat(method.methodCode()).isNull();
    }

    // ------------------------------------------------------------------
    // Termination — bugs threshold from the initial state
    // ------------------------------------------------------------------

    @Test
    void terminatesEarlyWhenInitialBugsReachThreshold() {
        SearchConfig config = SearchConfig.builder()
                .maxIterations(5)
                .bugsThreshold(1)
                .build();

        // Root state already carries one logical bug -> bugs_found = 1 at init
        // (fa_mcts.py:913-914); the C3 check then stops after iteration 1.
        FaTestState rootState = bugState(TEST_CODE_WITH_BUG, 10.0, "testAdd",
                "expected: <1> but was: <2>");
        FaTestState s1 = plainState(TEST_CODE_ROOT, 20.0);

        ScriptedApplier applier = new ScriptedApplier().add(s1);
        FixedRandomSource random = new FixedRandomSource().doubles(0.9).ints(0);

        FaMcts search = mcts(config, rootState, applier, null, random);
        FaMcts.SearchResult result = search.runSearch();

        assertThat(result.iterationsRun()).isEqualTo(1);
        assertThat(applier.appliedActions).hasSize(1);
        // No verifier injected: verified list keeps the initial-state seeding
        // (fa_mcts.py:915-925) and bugs_found is not overwritten.
        assertThat(result.verifiedBugMethods()).hasSize(1);
        assertThat(result.verifiedBugMethods().get(0).methodName).isEqualTo("testAdd");
    }

    // ------------------------------------------------------------------
    // D14 gate — best test NOT updated when reward does not exceed best
    // ------------------------------------------------------------------

    @Test
    void bestTestNotUpdatedWhenRewardBelowInitialReward() {
        SearchConfig config = SearchConfig.builder().maxIterations(1).build();

        // Initial state coverage 100 -> initial reward 0.2*1.0 = 0.2 (fa_mcts.py:928).
        FaTestState rootState = plainState(TEST_CODE_ROOT, 100.0);
        // Child coverage 50 -> reward 0.1 < 0.2: gated before UpdateBestPolicy runs.
        FaTestState s1 = plainState(TEST_CODE_WITH_BUG, 50.0);

        ScriptedApplier applier = new ScriptedApplier().add(s1);
        FixedRandomSource random = new FixedRandomSource().doubles(0.9).ints(0);

        FaMcts search = mcts(config, rootState, applier, null, random);
        FaMcts.SearchResult result = search.runSearch();

        assertThat(result.bestTestCode()).isEqualTo(TEST_CODE_ROOT);
        assertThat(result.bestCoverage()).isEqualTo(100.0);
        assertThat(result.bestReward()).isCloseTo(0.2, within(1e-9));
    }

    // ------------------------------------------------------------------
    // Fix-path bookkeeping (fa_mcts.py:2762-2768)
    // ------------------------------------------------------------------

    @Test
    void failedFixAttemptMarksPathSignature() {
        SearchConfig config = SearchConfig.builder().maxIterations(1).build();

        FaTestState rootState = plainState(TEST_CODE_ROOT, 0.0);
        rootState.compilationErrors = new ArrayList<>(List.of("cannot find symbol: Foo"));

        // The fix attempt leaves errors behind.
        FaTestState stillBroken = plainState(TEST_CODE_ROOT, 0.0);
        stillBroken.compilationErrors = new ArrayList<>(List.of("cannot find symbol: Foo"));

        ScriptedApplier applier = new ScriptedApplier().add(stillBroken);
        // D1 emits ONLY the fix action (early return, no general-exploration draw);
        // expansion consumes one choice int.
        FixedRandomSource random = new FixedRandomSource().ints(0);

        FaMcts search = mcts(config, rootState, applier, null, random);
        search.runSearch();

        assertThat(random.exhausted()).isTrue();
        assertThat(applier.appliedActions).hasSize(1);
        assertThat(applier.appliedActions.get(0).type()).isEqualTo("fix_compilation_errors");
        // Root node's path signature is "" (no parent chain).
        assertThat(search.fixTracker().pathFailed("")).isTrue();
        assertThat(search.fixTracker().globalAttempts()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Applier failure — Python `return None` path (fa_mcts.py:2637-2643)
    // ------------------------------------------------------------------

    @Test
    void applierFailureLeavesTreeUnexpandedButConsumesAction() {
        SearchConfig config = SearchConfig.builder().maxIterations(1).build();

        FaTestState rootState = plainState(TEST_CODE_ROOT, 30.0);
        ScriptedApplier applier = new ScriptedApplier(); // empty -> returns null

        FixedRandomSource random = new FixedRandomSource().doubles(0.9).ints(0);

        FaMcts search = mcts(config, rootState, applier, null, random);
        FaMcts.SearchResult result = search.runSearch();

        FaMctsNode root = search.root();
        assertThat(root.children).isEmpty();
        // used_action was appended BEFORE the failed apply (fa_mcts.py:2634).
        assertThat(root.usedActions).hasSize(1);
        // Simulation then ran on the returned (root) node itself: reward from the
        // root state (coverage 30 -> 0.2*0.3 = 0.06); 0.06 < 0.1 counts as a failure
        // signal (C13), so wins decay by 0.9 (F3): (0 + 0.06) * 0.9 = 0.054.
        assertThat(root.visits).isEqualTo(1);
        assertThat(root.consecutiveFailures).isEqualTo(1);
        assertThat(root.wins).isCloseTo(0.054, within(1e-9));
        // No history entry: the root has no children, best_child returns null
        // (fa_mcts.py:2246-2248).
        assertThat(result.history()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Determinism — same seed, same scripted applier => identical trajectories
    // ------------------------------------------------------------------

    @Test
    void seededRunsAreReproducible() {
        FaMcts.SearchResult a = runSeeded(42L);
        FaMcts.SearchResult b = runSeeded(42L);

        assertThat(a.bestTestCode()).isEqualTo(b.bestTestCode());
        assertThat(a.bestCoverage()).isEqualTo(b.bestCoverage());
        assertThat(a.bestReward()).isEqualTo(b.bestReward());
        assertThat(a.iterationsRun()).isEqualTo(b.iterationsRun());
        assertThat(a.history()).isEqualTo(b.history());
        assertThat(a.potentialBugs()).hasSameSizeAs(b.potentialBugs());
    }

    private static FaMcts.SearchResult runSeeded(long seed) {
        SearchConfig config = SearchConfig.builder().maxIterations(6).build();
        FaTestState rootState = plainState(TEST_CODE_ROOT, 10.0);

        // Pure function of (action, parent): deterministic child states.
        FaMcts.ActionApplier applier = (action, parent) -> {
            FaTestState child = new FaTestState(
                    TEST_CODE_ROOT + "// " + action.type(), null, null);
            child.carryForwardFrom(parent, action);
            double parentCoverage = parent == null ? 0.0 : parent.coverage;
            child.coverage = Math.min(100.0, parentCoverage + action.type().length());
            return child;
        };

        FaMcts search = mcts(config, rootState, applier, null, new SeededRandomSource(seed));
        return search.runSearch();
    }
}
