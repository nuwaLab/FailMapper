package org.failmapper.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;

/**
 * The failure-aware MCTS orchestrator — port of {@code FA_MCTS.run_search}
 * ({@code fa_mcts.py:1092-1193}) and its per-iteration helpers:
 * expansion (D5, {@code fa_mcts.py:2578-2657}), simulation (D7,
 * {@code fa_mcts.py:3029-3074}), backpropagation (D8, {@code fa_mcts.py:3079-3153}),
 * and batch verification dispatch ({@code verify_all_potential_bugs},
 * {@code fa_mcts.py:1196-1306}).
 *
 * <p>PURE with respect to I/O: the LLM + compile/run composition arrives through the
 * {@link ActionApplier} seam (D6 lives in failmapper-app), verification through
 * {@link BugVerifier}, and all randomness through the injected {@link RandomSource}
 * (I9). Given a seeded RandomSource and deterministic applier/verifier, the whole
 * search is reproducible.
 *
 * <p>Loop mapping (one iteration, {@code fa_mcts.py:1123-1160}):
 * <ol>
 *   <li>D4 selection via {@link SelectionPolicy#select} (forced exploration every 3rd
 *       iteration lives there);</li>
 *   <li>D5 expansion: {@code generate_possible_actions} → uniform
 *       {@code random.choice} (R9 — NOT priority-based) → {@link ActionApplier#apply}
 *       → {@code add_child}; {@code used_action} appended BEFORE the apply, exactly
 *       like Python, so a failed apply still consumes the action;</li>
 *   <li>D7 simulation: collect the state's detected bugs into {@link #potentialBugs}
 *       deduplicated by the D7 {@link BugSignature} — WITHOUT verifying — then
 *       {@link RewardCalculator#calculate}. NOTE: the live Python call passes NO
 *       parent state ({@code fa_mcts.py:3066/3070}), so the parent-relative reward
 *       components (coverage improvement, previous pattern/branch counts, per-new
 *       high-risk bonus) are computed against an absent parent;</li>
 *   <li>D8 backpropagation: bugType = {@code "logical_" + } first HIGH-severity
 *       logical bug's type, else the first logical bug's type; walk the parent chain
 *       calling {@link FaMctsNode#update} with each node's OWN state's covered-set
 *       sizes (the Python shadowed-locals quirk — see FaMctsNode.update doc) and
 *       refresh each node's covered-set bookkeeping ({@code fa_mcts.py:540-544,
 *       563-574});</li>
 *   <li>D14 best-test update — GATED by {@code reward > best_reward}
 *       ({@code fa_mcts.py:1147-1148}) before {@link UpdateBestPolicy#updateBest}
 *       applies its own rules;</li>
 *   <li>history recording for the ROOT's current best child
 *       ({@code fa_mcts.py:1150-1155}; skipped when the root has no children or the
 *       best child has no state, {@code fa_mcts.py:2246-2248}) — the recorded reward
 *       is {@code round(reward, 5)} half-even (contract N6) and feeds the C35
 *       no-progress window;</li>
 *   <li>termination check each iteration via {@link TerminationPolicy}.</li>
 * </ol>
 * A per-iteration exception is caught and the loop continues
 * ({@code fa_mcts.py:1162-1164}).
 *
 * <p>After the loop: batch verification of all potential bugs
 * ({@code verify_all_potential_bugs}) unless {@code verifyBugsMode == NONE} or no
 * verifier was injected; then {@code bugs_found = real_bugs_count}
 * ({@code fa_mcts.py:1302}). The final integrated-test merge
 * ({@code generate_integrated_test_code}) is M5 scope and not performed here — the
 * result carries the best test plus the verified bug methods for the caller to merge.
 */
public final class FaMcts {

    /**
     * D6 seam — applies one action to a parent state and returns the evaluated child
     * state (port boundary of {@code _apply_action}, {@code fa_mcts.py:2659-2790}).
     * The implementation owns prompt building, the LLM call, code extraction,
     * carry-forward ({@link FaTestState#carryForwardFrom}) and evaluation; the
     * orchestrator owns used-action bookkeeping and failed-fix-path marking.
     *
     * <p>Return null to model Python's {@code return None} (LLM/extraction failure):
     * the expansion then returns the parent node without adding a child
     * ({@code fa_mcts.py:2637-2643}).
     */
    public interface ActionApplier {
        FaTestState apply(SearchAction action, FaTestState parentState);
    }

    /**
     * D9 seam — batch bug verification (port boundary of
     * {@code BugVerifier.verify_bugs}, {@code bug_verifier.py:141-238}). The
     * implementation is pre-configured with the source/class context, exactly like
     * Python's {@code BugVerifier(source_code, class_name, package_name)} constructed
     * inside {@code verify_all_potential_bugs} ({@code fa_mcts.py:1206}); it owns the
     * per-signature dedup (O16), the D10 pre-filters and the LLM verdict protocol.
     */
    public interface BugVerifier {
        List<VerifiedBugMethod> verifyBatch(List<MethodToVerify> methods);
    }

    /**
     * Class-under-test context the kernel reads during expansion and reward
     * calculation ({@code self.f_model} / {@code self.failures} /
     * {@code self.strategy_selector}); every component may be null.
     */
    public record SearchContext(
            FailureModel fModel,
            List<FailureScenario> failures,
            StrategySelector strategySelector) {

        public static SearchContext empty() {
            return new SearchContext(null, null, null);
        }
    }

    /** One history entry (subset of the Python history dict, {@code fa_mcts.py:2335-2352}). */
    public record IterationRecord(
            int iteration,
            String actionType,
            double reward,
            double coverage,
            int bugsFound,
            int logicScenarioCoverage,
            int branchConditionCoverage,
            int visits,
            double wins) {
    }

    /** Final search outcome ({@code run_search} return + exposed verification results). */
    public record SearchResult(
            String bestTestCode,
            double bestCoverage,
            double bestReward,
            int iterationsRun,
            List<IterationRecord> history,
            List<PotentialBug> potentialBugs,
            List<VerifiedBugMethod> verifiedBugMethods,
            int realBugsCount,
            int falsePositivesCount) {
    }

    private final SearchConfig config;
    private final FaTestState rootState;
    private final ActionGenerator actionGenerator;
    private final SelectionPolicy selectionPolicy;
    private final ActionApplier actionApplier;
    private final RewardCalculator rewardCalculator;
    private final UpdateBestPolicy updateBestPolicy;
    private final TerminationPolicy terminationPolicy;
    private final BugVerifier bugVerifier;
    private final RandomSource random;
    private final SearchContext context;

    private final PredictedIssueMatcher issueMatcher = new PredictedIssueMatcher();

    /** {@code self.failed_fix_paths} / {@code global_compilation_fix_attempts} (C7). */
    private final CompilationFixTracker fixTracker = new CompilationFixTracker();

    /** {@code self.potential_bugs} — D7 collection, insertion-ordered. */
    private final List<PotentialBug> potentialBugs = new ArrayList<>();

    /** {@code self.potential_bug_signatures} — D7 dedup set. */
    private final LinkedHashSet<String> potentialBugSignatures = new LinkedHashSet<>();

    /** {@code self.verified_bug_methods} (seeded from the initial state, overwritten by verification). */
    private List<VerifiedBugMethod> verifiedBugMethods = new ArrayList<>();

    /** {@code self.history} rewards (round-5) — feeds the C35 termination window. */
    private final List<Double> rewardHistory = new ArrayList<>();

    private final List<IterationRecord> history = new ArrayList<>();

    /** {@code self.bugs_found} — initial-state logical bug count until verification overwrites it. */
    private int bugsFound = 0;

    private int realBugsCount = 0;
    private int falsePositivesCount = 0;

    /** {@code self.root} — exposed for tests. */
    private FaMctsNode root;

    private int currentIteration = 0;

    public FaMcts(SearchConfig config,
                  FaTestState rootState,
                  ActionGenerator actionGenerator,
                  SelectionPolicy selectionPolicy,
                  ActionApplier actionApplier,
                  RewardCalculator rewardCalculator,
                  UpdateBestPolicy updateBestPolicy,
                  TerminationPolicy terminationPolicy,
                  BugVerifier bugVerifier,
                  RandomSource random,
                  SearchContext context) {
        this.config = config;
        this.rootState = rootState;
        this.actionGenerator = actionGenerator;
        this.selectionPolicy = selectionPolicy;
        this.actionApplier = actionApplier;
        this.rewardCalculator = rewardCalculator;
        this.updateBestPolicy = updateBestPolicy;
        this.terminationPolicy = terminationPolicy;
        this.bugVerifier = bugVerifier;
        this.random = random;
        this.context = context == null ? SearchContext.empty() : context;
    }

    /** The MCTS tree root; populated by {@link #runSearch()}. Exposed for tests. */
    public FaMctsNode root() {
        return root;
    }

    /** Global compilation-fix bookkeeping — exposed for tests and reporting. */
    public CompilationFixTracker fixTracker() {
        return fixTracker;
    }

    /** Collected potential bugs (D7), insertion-ordered; live view. */
    public List<PotentialBug> potentialBugs() {
        return Collections.unmodifiableList(potentialBugs);
    }

    /** Verified bug methods after {@link #runSearch()} (empty before). */
    public List<VerifiedBugMethod> verifiedBugMethods() {
        return Collections.unmodifiableList(verifiedBugMethods);
    }

    /**
     * Run the full search — port of {@code run_search} ({@code fa_mcts.py:1092-1193})
     * minus the M5 integrated-test merge.
     */
    public SearchResult runSearch() {
        root = new FaMctsNode(rootState);

        // --- initial best seeding, process_initial_state (fa_mcts.py:899-929) ---
        updateBestPolicy.bestState = rootState;
        updateBestPolicy.bestTest = rootState.testCode;
        if (rootState.coverage > 0) {
            updateBestPolicy.currentCoverage = rootState.coverage;
        }
        if (rootState.hasBugs) {
            bugsFound = rootState.countLogicalBugs(); // fa_mcts.py:913-914
            seedInitialBugMethods(); // fa_mcts.py:915-925
        }
        double initialReward = rewardCalculator.calculate(rewardInputs(rootState));
        updateBestPolicy.bestReward = initialReward; // fa_mcts.py:928-929

        int iterationsRun = 0;
        for (int iteration = 1; iteration <= config.maxIterations; iteration++) {
            currentIteration = iteration;
            iterationsRun = iteration;
            try {
                // 1. D4 selection.
                FaMctsNode selected = selectionPolicy.select(root, iteration);

                // 2. D5 expansion.
                FaMctsNode expanded = selected.isFullyExpanded() ? selected : expansion(selected);

                // 3. D7 simulation.
                double reward = simulation(expanded);

                // 4. D8 backpropagation.
                backpropagation(expanded, reward);

                // 5. D14 best update — gated by reward > best_reward (fa_mcts.py:1147).
                if (reward > updateBestPolicy.bestReward) {
                    updateBestPolicy.updateBest(stateOf(expanded), reward, iteration);
                }

                // 6. History for the root's most promising child (fa_mcts.py:1150-1155).
                FaMctsNode bestNode = root.bestChild(config.explorationWeight, config.fWeight);
                recordHistory(bestNode, iteration, reward);

                // 7. Termination check (fa_mcts.py:1157-1160).
                if (terminationPolicy.shouldTerminate(
                        iteration, updateBestPolicy.currentCoverage, bugsFound, rewardHistory)) {
                    break;
                }
            } catch (RuntimeException e) {
                // fa_mcts.py:1162-1164 — log-and-continue; the next iteration proceeds.
            }
        }

        // --- batch verification (fa_mcts.py:1169-1172; mode gate per C5) ---
        if (bugVerifier != null && config.verifyBugsMode != VerifyBugsMode.NONE) {
            verifiedBugMethods = verifyAllPotentialBugs();
            bugsFound = realBugsCount; // fa_mcts.py:1302
        }

        return new SearchResult(
                updateBestPolicy.bestTest,
                updateBestPolicy.currentCoverage,
                updateBestPolicy.bestReward,
                iterationsRun,
                List.copyOf(history),
                List.copyOf(potentialBugs),
                List.copyOf(verifiedBugMethods),
                realBugsCount,
                falsePositivesCount);
    }

    // ------------------------------------------------------------------
    // D5 — expansion (fa_mcts.py:2578-2657)
    // ------------------------------------------------------------------

    private FaMctsNode expansion(FaMctsNode node) {
        FaTestState state = stateOf(node);
        // uncovered_data = {"uncovered_lines": state.uncovered_lines} if hasattr else None
        List<UncoveredLine> uncoveredLines = state == null ? null : state.uncoveredLines;

        List<SearchAction> possibleActions = actionGenerator.generate(
                node, uncoveredLines, context.fModel(), context.failures(),
                context.strategySelector(), fixTracker);

        if (possibleActions.isEmpty()) {
            node.expanded = true; // fa_mcts.py:2599-2602
            return node;
        }

        // R9 — uniform random choice, NOT priority-based (fa_mcts.py:2608).
        SearchAction action = random.choice(possibleActions);

        // used_action appended BEFORE the apply (fa_mcts.py:2634) — a failed apply
        // still consumes the action.
        node.usedActions.add(action);

        FaTestState newState = actionApplier.apply(action, state);

        if (newState == null) {
            // fa_mcts.py:2638-2643 — creation failed; maybe mark expanded.
            if (node.children.size() >= possibleActions.size()) {
                node.expanded = true;
            }
            return node;
        }

        // fix_compilation_errors bookkeeping (fa_mcts.py:2762-2768): persistent errors
        // after a fix attempt mark this path signature as failed. Python does this
        // inside _apply_action; the applier is a pure seam here, so the orchestrator
        // owns the tracker mutation.
        if (RewardCalculator.FIX_COMPILATION_ERRORS_ACTION.equals(action.type())
                && !newState.compilationErrors.isEmpty()) {
            Object pathSignature = action.attributes().get("path_signature");
            if (pathSignature instanceof String s) {
                fixTracker.markPathFailed(s);
            }
        }

        FaMctsNode child = node.addChild(newState, action);
        if (node.children.size() >= possibleActions.size()) {
            node.expanded = true; // fa_mcts.py:2648-2650
        }
        return child;
    }

    // ------------------------------------------------------------------
    // D7 — simulation (fa_mcts.py:3029-3074)
    // ------------------------------------------------------------------

    private double simulation(FaMctsNode node) {
        FaTestState state = stateOf(node);
        if (state == null) {
            return 0.0; // fa_mcts.py:3073-3074
        }

        if (!state.detectedBugs.isEmpty()) {
            for (DetectedBug bug : state.detectedBugs) {
                PotentialBug info = new PotentialBug();
                info.testMethod = bug.testMethod == null ? "unknown" : bug.testMethod;
                // D7 quirk: bug_type <- bug["type"] (the failure kind), fa_mcts.py:3047.
                info.bugType = bug.type == null ? "unknown" : bug.type;
                info.error = bug.errorOrEmpty();
                info.severity = bug.severity == null ? "medium" : bug.severity;
                info.methodCode = TestMethodExtractor.extract(
                        state.testCode, bug.testMethodOrEmpty());
                info.foundInIteration = currentIteration;
                info.originalTestCode = state.testCode;
                // NO verified copy: the Python D7 bug_info dict (fa_mcts.py:3044-3052)
                // carries no "verified" key, so bug.get("verified", False) at the batch
                // grouping (fa_mcts.py:1216) queues EVERY D7-collected bug for
                // verification — including assertion failures pre-marked verified=True
                // by the evaluator (test_state.py:181). Copying bug.verified here
                // filtered them out and starved the whole verification phase.

                String signature = BugSignature.create(info.testMethod, info.error);
                if (!potentialBugSignatures.contains(signature)) {
                    info.bugSignature = signature;
                    potentialBugSignatures.add(signature);
                    potentialBugs.add(info);
                }
            }
        }

        // Live call passes NO parent state (fa_mcts.py:3066/3070).
        return rewardCalculator.calculate(rewardInputs(state));
    }

    // ------------------------------------------------------------------
    // D8 — backpropagation (fa_mcts.py:3079-3153)
    // ------------------------------------------------------------------

    private void backpropagation(FaMctsNode node, double reward) {
        String bugType = null;
        FaTestState leafState = stateOf(node);

        LinkedHashSet<String> leafPatterns = null;
        LinkedHashSet<String> leafBranches = null;
        if (leafState != null) {
            leafPatterns = leafState.coveredFailures;
            leafBranches = leafState.coveredBranchConditions;

            if (leafState.hasBugs) {
                // First HIGH-severity logical bug wins (fa_mcts.py:3103-3106).
                for (DetectedBug bug : leafState.logicalBugs) {
                    String severity = bug.severity == null ? "medium" : bug.severity;
                    if ("high".equals(severity)) {
                        bugType = "logical_" + (bug.bugType == null ? "unknown" : bug.bugType);
                        break;
                    }
                }
                // Else the first logical bug (fa_mcts.py:3108-3109).
                if (bugType == null && !leafState.logicalBugs.isEmpty()) {
                    DetectedBug first = leafState.logicalBugs.get(0);
                    bugType = "logical_" + (first.bugType == null ? "unknown" : first.bugType);
                }
            }
        }

        FaMctsNode current = node;
        while (current != null) {
            FaTestState nodeState = stateOf(current);

            // F3/F4 stat update; covered-set sizes come from THIS node's own state
            // (the fa_mcts.py:560/569 shadowed-locals quirk — see FaMctsNode.update).
            int failuresCount = nodeState == null ? -1 : nodeState.coveredFailures.size();
            int branchesCount = nodeState == null ? -1 : nodeState.coveredBranchConditions.size();
            current.update(reward, bugType, failuresCount, branchesCount);

            // Covered-set bookkeeping: the leaf's sets are assigned first
            // (fa_mcts.py:540-544), then overwritten from the node's own state when it
            // exists (fa_mcts.py:563-574).
            if (leafPatterns != null) {
                current.coveredPatterns.clear();
                current.coveredPatterns.addAll(leafPatterns);
            }
            if (leafBranches != null) {
                current.coveredBranchConditions.clear();
                current.coveredBranchConditions.addAll(leafBranches);
            }
            if (nodeState != null) {
                current.coveredPatterns.clear();
                current.coveredPatterns.addAll(nodeState.coveredFailures);
                current.coveredBranchConditions.clear();
                current.coveredBranchConditions.addAll(nodeState.coveredBranchConditions);
            }

            current = current.parent;
        }
    }

    // ------------------------------------------------------------------
    // Batch verification (fa_mcts.py:1196-1306)
    // ------------------------------------------------------------------

    private List<VerifiedBugMethod> verifyAllPotentialBugs() {
        // Group by method name — plain dict in Python, insertion-ordered (contract O6).
        LinkedHashMap<String, List<PotentialBug>> bugsByMethod = new LinkedHashMap<>();
        for (PotentialBug bug : potentialBugs) {
            String methodName = bug.testMethod == null ? "unknown" : bug.testMethod;
            List<PotentialBug> list =
                    bugsByMethod.computeIfAbsent(methodName, k -> new ArrayList<>());
            // Only unverified bugs are queued (fa_mcts.py:1216-1221); the D7 signature
            // is always present already (fa_mcts.py:1218 never fires here).
            if (!bug.verified) {
                list.add(bug);
            }
        }

        if (bugsByMethod.isEmpty()) {
            return new ArrayList<>(); // "no bugs to verify" (fa_mcts.py:1223-1225)
        }

        List<MethodToVerify> methodsToVerify = new ArrayList<>();
        for (Map.Entry<String, List<PotentialBug>> entry : bugsByMethod.entrySet()) {
            List<PotentialBug> bugs = entry.getValue();
            if (bugs.isEmpty()) {
                continue; // fa_mcts.py:1234-1235
            }

            // First bug with method code wins (fa_mcts.py:1239-1244); Python leaves
            // method_code = None when none extractable (the multi-source fallback at
            // 1246-1250 is a TODO/pass in the baseline).
            String methodCode = null;
            for (PotentialBug bug : bugs) {
                if (bug.methodCode != null && !bug.methodCode.isEmpty()) {
                    methodCode = bug.methodCode;
                    break;
                }
            }

            // "{bug_type}: {error[:100](...)}" descriptions (fa_mcts.py:1253-1262).
            List<String> bugDescriptions = new ArrayList<>();
            for (PotentialBug bug : bugs) {
                String bugType = bug.bugType == null ? "unknown" : bug.bugType;
                String error = bug.error == null ? "" : bug.error;
                if (error.codePointCount(0, error.length()) > 100) {
                    error = error.substring(0, error.offsetByCodePoints(0, 100)) + "...";
                }
                bugDescriptions.add(bugType + ": " + error);
            }

            methodsToVerify.add(new MethodToVerify(
                    entry.getKey(),
                    methodCode,
                    List.copyOf(bugs),
                    bugDescriptions,
                    bugs.get(0).bugSignature)); // first bug's signature (fa_mcts.py:1270, O6)
        }

        List<VerifiedBugMethod> verified = bugVerifier.verifyBatch(methodsToVerify);
        if (verified == null) {
            verified = new ArrayList<>();
        }

        realBugsCount = 0;
        falsePositivesCount = 0;
        for (VerifiedBugMethod method : verified) {
            if (method.isRealBug) {
                realBugsCount++;
            } else {
                falsePositivesCount++;
            }
        }
        return verified;
    }

    // ------------------------------------------------------------------
    // History (subset of record_history, fa_mcts.py:2236-2355)
    // ------------------------------------------------------------------

    private void recordHistory(FaMctsNode node, int iteration, double reward) {
        if (node == null || node.state == null) {
            return; // fa_mcts.py:2246-2248 — nothing recorded, history stays shorter
        }
        FaTestState nodeState = stateOf(node);

        // Coverage preference chain (fa_mcts.py:2251-2257).
        double coverage = 0.0;
        if (updateBestPolicy.currentCoverage > 0) {
            coverage = updateBestPolicy.currentCoverage;
        } else if (updateBestPolicy.bestState != null) {
            coverage = updateBestPolicy.bestState.coverage;
        } else if (nodeState != null) {
            coverage = nodeState.coverage;
        }

        // Bug counts: verified list first, else the node state's logical bugs
        // (fa_mcts.py:2260-2312).
        int entryBugsFound = 0;
        if (!verifiedBugMethods.isEmpty()) {
            entryBugsFound = verifiedBugMethods.size();
        } else if (nodeState != null && !nodeState.logicalBugs.isEmpty()) {
            entryBugsFound = nodeState.logicalBugs.size();
        }

        double roundedReward = pyRound5(reward); // round(float(reward), 5) — N6
        rewardHistory.add(roundedReward);

        history.add(new IterationRecord(
                iteration,
                node.action == null ? "root" : (node.action.type() == null ? "unknown" : node.action.type()),
                roundedReward,
                coverage,
                entryBugsFound,
                nodeState == null ? 0 : nodeState.coveredFailures.size(),
                nodeState == null ? 0 : nodeState.coveredBranchConditions.size(),
                node.visits,
                pyRound5(node.historyWins())));
    }

    // ------------------------------------------------------------------
    // Reward inputs (adapter over FaTestState + context; parent absent per D7)
    // ------------------------------------------------------------------

    private RewardInputs rewardInputs(FaTestState state) {
        FailureModel fModel = context.fModel();
        List<FailureScenario> failures = context.failures();
        return new RewardInputs() {
            @Override
            public double coverage() {
                return state.coverage;
            }

            @Override
            public Double parentCoverage() {
                return null; // simulation passes no parent state (fa_mcts.py:3066/3070)
            }

            @Override
            public boolean hasCompilationErrors() {
                return !state.compilationErrors.isEmpty();
            }

            @Override
            public String actionType() {
                return state.metadataAction == null ? null : state.metadataAction.type();
            }

            @Override
            public boolean hadErrorsBefore() {
                return !state.previousCompilationErrors.isEmpty();
            }

            @Override
            public boolean hasDetectedBugs() {
                return !state.detectedBugs.isEmpty();
            }

            @Override
            public List<Double> matchedBusinessLogicIssueConfidences() {
                // fa_mcts.py:3219-3227: per detected bug, the FIRST matching predicted
                // issue contributes issue.get('confidence', 0.5), then break.
                List<Double> confidences = new ArrayList<>();
                for (DetectedBug bug : state.detectedBugs) {
                    for (BusinessLogicIssue issue : state.businessLogicIssues) {
                        if (issueMatcher.matches(bug, issue)) {
                            confidences.add(issue.confidenceOrHalf());
                            break;
                        }
                    }
                }
                return confidences;
            }

            @Override
            public boolean hasLogicalBugs() {
                return state.hasBugs;
            }

            @Override
            public int logicalBugCount() {
                return state.countLogicalBugs();
            }

            @Override
            public List<String> logicalBugTypes() {
                List<String> types = new ArrayList<>();
                for (DetectedBug bug : state.logicalBugs) {
                    types.add(bug.bugType == null ? "" : bug.bugType);
                }
                return types;
            }

            @Override
            public int currentPatternCount() {
                return state.coveredFailures.size();
            }

            @Override
            public int previousPatternCount() {
                return 0; // no parent state in the live call
            }

            @Override
            public int newHighRiskPatternCount() {
                return 0; // newly_covered stays empty without a parent (fa_mcts.py:3270-3273)
            }

            @Override
            public int totalFailures() {
                return failures == null ? 0 : failures.size();
            }

            @Override
            public boolean trackBranchConditions() {
                // hasattr(state, "covered_branch_conditions") and self.f_model
                return fModel != null;
            }

            @Override
            public int currentBranchCount() {
                return state.coveredBranchConditions.size();
            }

            @Override
            public int previousBranchCount() {
                return 0;
            }

            @Override
            public int totalBoundaryConditions() {
                return fModel == null || fModel.boundaryConditions() == null
                        ? 0 : fModel.boundaryConditions().size();
            }

            @Override
            public boolean hasBoundaryTests() {
                return state.hasBoundaryTests;
            }

            @Override
            public boolean hasBooleanBugTests() {
                return state.hasBooleanBugTests;
            }

            @Override
            public boolean hasStateTransitionTests() {
                return state.hasStateTransitionTests;
            }

            @Override
            public boolean hasExceptionPathTests() {
                return state.hasExceptionPathTests;
            }

            @Override
            public int stagnantCoverageIterations() {
                return state.stagnantCoverageIterations;
            }

            @Override
            public void setStagnantCoverageIterations(int value) {
                state.stagnantCoverageIterations = value;
            }
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Initial-state bug methods seeded into {@code verified_bug_methods}
     * ({@code fa_mcts.py:915-925} via {@code get_logical_bug_finding_methods},
     * {@code test_state.py:668-689}): one entry per logical bug whose test method name
     * exists and whose method code extracts; duplicates (dict value equality) skipped.
     * This list only shapes loop-time history counts — run_search OVERWRITES it with
     * the batch-verification result ({@code fa_mcts.py:1172}).
     */
    private void seedInitialBugMethods() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (DetectedBug bug : rootState.logicalBugs) {
            String methodName = bug.testMethod;
            if (methodName == null || methodName.isEmpty()) {
                continue;
            }
            String methodCode = TestMethodExtractor.extract(rootState.testCode, methodName);
            if (methodCode.isEmpty()) {
                continue;
            }
            String bugType = bug.bugType == null ? "unknown" : bug.bugType;
            String key = methodName + "\u0000" + bugType + "\u0000"
                    + (bug.severity == null ? "medium" : bug.severity);
            if (!seen.add(key)) {
                continue; // `if bug_method not in self.verified_bug_methods`
            }
            VerifiedBugMethod method = new VerifiedBugMethod();
            method.methodName = methodName;
            method.methodCode = methodCode;
            method.bugType = bugType;
            method.verified = bug.verified;
            method.isRealBug = Boolean.TRUE.equals(bug.isRealBug);
            method.verificationConfidence = bug.logicConfidence == null ? 0.5 : bug.logicConfidence;
            verifiedBugMethods.add(method);
        }
    }

    private static FaTestState stateOf(FaMctsNode node) {
        return node != null && node.state instanceof FaTestState s ? s : null;
    }

    /**
     * Python {@code round(x, 5)} — half-even on the exact binary double (contract N6;
     * same decimal semantics as {@link org.failmapper.core.util.PyFormat}).
     */
    private static double pyRound5(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        return new BigDecimal(x).setScale(5, RoundingMode.HALF_EVEN).doubleValue();
    }
}
