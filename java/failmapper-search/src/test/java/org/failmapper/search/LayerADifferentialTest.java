package org.failmapper.search;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.RiskLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * LAYER-A DIFFERENTIAL TESTS (contract doc/JAVA_PORT_CONTRACT.md section 5, layer A).
 *
 * <p>Every case in {@code src/test/resources/layera/fixtures.json} was produced by
 * running the REAL Python baseline implementations (commit d2baa9e) over generated
 * inputs and recording their outputs to full float precision (Python {@code repr}).
 * This class replays the same inputs against the ported kernel and asserts equality
 * (doubles within 1e-9; list/map ORDER asserted wherever the contract pins it:
 * O1 first-max tie-break, O4 stable strategy sort, F9 score-map insertion order).
 *
 * <p>THE FIXTURE FILE IS A PINNED ORACLE SNAPSHOT — do not edit it by hand.
 * Generator: {@code java/failmapper-search/src/test/python/gen_fixtures.py}
 * (working copy also produced at the scratchpad path recorded in its header).
 * Regen:
 * <pre>
 *   cd /Users/ruiqidong/Desktop/FailMapper
 *   python3 java/failmapper-search/src/test/python/gen_fixtures.py \
 *       java/failmapper-search/src/test/resources/layera/fixtures.json
 * </pre>
 *
 * <p>Oracle-access notes (per formula family):
 * <ul>
 *   <li>F1/F2: RAW UCB scores captured from the genuine {@code ucb_score} closure by
 *       shadowing the module-global {@code max} used by {@code best_child}
 *       ({@code fa_mcts.max = capturing wrapper}) — both the chosen-child index AND all
 *       per-child scores are asserted;</li>
 *   <li>F3/F4, F5-F8, F9, D3, D11, D13, F15/D10: direct invocation on
 *       {@code object.__new__}-constructed instances with only the attributes each
 *       method reads;</li>
 *   <li>none of these formulas consumes {@code random.*}, so every case's
 *       {@code randomTrace} is empty and no scripted RandomSource replay is needed.</li>
 * </ul>
 *
 * <p>Registered improvements (contract section 4) are EXCLUDED from differential scope
 * (I3 branch tracker is behavior-tested in {@link BranchCoverageTrackerTest}).
 */
class LayerADifferentialTest {

    private static final double EPS = 1e-9;

    private static JsonNode CASES;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = LayerADifferentialTest.class.getResourceAsStream("/layera/fixtures.json")) {
            assertThat(in).as("fixtures.json on test classpath").isNotNull();
            CASES = new ObjectMapper().readTree(in).get("cases");
        }
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    private static double parsePyFloat(String repr) {
        if ("inf".equals(repr)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-inf".equals(repr)) {
            return Double.NEGATIVE_INFINITY;
        }
        return Double.parseDouble(repr);
    }

    private static void assertClose(String what, double actual, String expectedRepr) {
        double expected = parsePyFloat(expectedRepr);
        if (Double.isInfinite(expected)) {
            assertThat(actual).as(what).isEqualTo(expected);
        } else {
            assertThat(actual).as(what).isCloseTo(expected, within(EPS));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double optDouble(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private static List<String> stringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && !array.isNull()) {
            array.forEach(n -> out.add(n.isNull() ? null : n.asText()));
        }
        return out;
    }

    private interface CaseCheck {
        void run(JsonNode inputs, JsonNode expected);
    }

    private static Iterator<DynamicTest> family(String formula, CaseCheck check) {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : CASES) {
            if (!formula.equals(c.get("formula").asText())) {
                continue;
            }
            JsonNode inputs = c.get("inputs");
            JsonNode expected = c.get("expected");
            tests.add(DynamicTest.dynamicTest(c.get("caseId").asText(),
                    () -> check.run(inputs, expected)));
        }
        assertThat(tests).as("cases for " + formula).isNotEmpty();
        return tests.iterator();
    }

    // ------------------------------------------------------------------
    // F3/F4 — FaMctsNode.update
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> f3f4_update() {
        return family("F3F4_update", (inputs, expected) -> {
            JsonNode init = inputs.get("init");
            FaMctsNode node = new FaMctsNode(null);
            node.visits = init.path("visits").asInt(0);
            node.wins = init.path("wins").asDouble(0.0);
            node.logicBugRewards = init.path("logicBugRewards").asDouble(0.0);
            node.failureCoverageRewards = init.path("failureCoverageRewards").asDouble(0.0);
            node.highRiskPatternRewards = init.path("highRiskPatternRewards").asDouble(0.0);
            node.bugsFound = init.path("bugsFound").asInt(0);
            // Python lazily creates consecutive_failures defaulting to 0; the Java int
            // field default 0 covers both consecutiveFailuresSet true/false.
            if (init.path("consecutiveFailuresSet").asBoolean(false)) {
                node.consecutiveFailures = init.path("consecutiveFailures").asInt(0);
            }

            JsonNode snapshots = expected.get("snapshots");
            int i = 0;
            for (JsonNode u : inputs.get("updates")) {
                String kind = u.get("stateKind").asText();
                int cf = switch (kind) {
                    case "none", "branchesOnly" -> -1;
                    default -> u.get("coveredFailuresSize").asInt();
                };
                int cb = switch (kind) {
                    case "none", "patternsOnly" -> -1;
                    default -> u.get("coveredBranchSize").asInt();
                };
                node.update(u.get("reward").asDouble(), text(u, "bugType"), cf, cb,
                        u.get("hasError").asBoolean());

                JsonNode snap = snapshots.get(i);
                String at = " after update " + (i + 1);
                assertThat(node.visits).as("visits" + at).isEqualTo(snap.get("visits").asInt());
                assertClose("wins" + at, node.wins, snap.get("wins").asText());
                assertThat(node.consecutiveFailures).as("consecutiveFailures" + at)
                        .isEqualTo(snap.get("consecutiveFailures").asInt());
                assertClose("logicBugRewards" + at, node.logicBugRewards,
                        snap.get("logicBugRewards").asText());
                assertClose("failureCoverageRewards" + at, node.failureCoverageRewards,
                        snap.get("failureCoverageRewards").asText());
                assertClose("highRiskPatternRewards" + at, node.highRiskPatternRewards,
                        snap.get("highRiskPatternRewards").asText());
                assertThat(node.bugsFound).as("bugsFound" + at).isEqualTo(snap.get("bugsFound").asInt());
                i++;
            }
        });
    }

    // ------------------------------------------------------------------
    // F1/F2 — FaMctsNode.bestChild / ucbScore
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> f1f2_bestChild() {
        return family("F1F2_bestChild", (inputs, expected) -> {
            FaMctsNode parent = new FaMctsNode(null);
            parent.visits = inputs.get("parentVisits").asInt();
            parent.lastActionType = text(inputs, "lastActionType");

            for (JsonNode cs : inputs.get("children")) {
                String actionType = cs.get("actionType").asText();
                // Python action=None and action={} both fail the 'type' in-dict check;
                // the Java model uses a null action for both (numerically identical).
                SearchAction action =
                        ("__NO_ACTION__".equals(actionType) || "__EMPTY_DICT__".equals(actionType))
                                ? null : SearchAction.of(actionType);
                FaMctsNode child = parent.addChild(null, action);
                child.visits = cs.get("visits").asInt();
                child.wins = cs.get("wins").asDouble();
                child.logicBugRewards = cs.get("logicBugRewards").asDouble();
                child.failureCoverageRewards = cs.get("failureCoverageRewards").asDouble();
                child.highRiskPatternRewards = cs.get("highRiskPatternRewards").asDouble();
                child.isNovel = cs.get("isNovel").asBoolean();
                JsonNode cfNode = cs.get("consecutiveFailures");
                child.consecutiveFailures = cfNode.isNull() ? 0 : cfNode.asInt();
            }

            double ew = inputs.get("explorationWeight").asDouble();
            double fw = inputs.get("fWeight").asDouble();

            // raw UCB scores (captured in Python from the genuine ucb_score closure)
            JsonNode scores = expected.get("scores");
            for (int i = 0; i < parent.children.size(); i++) {
                assertClose("ucbScore(child " + i + ")",
                        parent.ucbScore(parent.children.get(i), ew, fw), scores.get(i).asText());
            }

            // selection outcome (O1: first-max tie-break)
            FaMctsNode chosen = parent.bestChild(ew, fw);
            int chosenIndex = -1;
            for (int i = 0; i < parent.children.size(); i++) {
                if (parent.children.get(i) == chosen) {
                    chosenIndex = i;
                    break;
                }
            }
            assertThat(chosenIndex).as("bestChild index").isEqualTo(expected.get("chosenIndex").asInt());
        });
    }

    // ------------------------------------------------------------------
    // F5-F8 — RewardCalculator (+ D13 wiring for business-logic matches)
    // ------------------------------------------------------------------

    /** Mutable RewardInputs built from a fixture's raw state description. */
    private static final class FixtureRewardInputs implements RewardInputs {
        double coverage;
        Double parentCoverage;
        boolean hasCompilationErrors;
        String actionType;
        boolean hadErrorsBefore;
        boolean hasDetectedBugs;
        List<Double> matchedConfidences = List.of();
        boolean hasLogicalBugs;
        int logicalBugCount;
        List<String> logicalBugTypes = List.of();
        boolean trackCoveredFailures;
        int currentPatternCount;
        int previousPatternCount;
        int newHighRiskPatternCount;
        int totalFailures;
        boolean trackBranchConditions;
        int currentBranchCount;
        int previousBranchCount;
        int totalBoundaryConditions;
        boolean hasBoundaryTests;
        boolean hasBooleanBugTests;
        boolean hasStateTransitionTests;
        boolean hasExceptionPathTests;
        int stagnant;

        @Override public double coverage() { return coverage; }
        @Override public Double parentCoverage() { return parentCoverage; }
        @Override public boolean hasCompilationErrors() { return hasCompilationErrors; }
        @Override public String actionType() { return actionType; }
        @Override public boolean hadErrorsBefore() { return hadErrorsBefore; }
        @Override public boolean hasDetectedBugs() { return hasDetectedBugs; }
        @Override public List<Double> matchedBusinessLogicIssueConfidences() { return matchedConfidences; }
        @Override public boolean hasLogicalBugs() { return hasLogicalBugs; }
        @Override public int logicalBugCount() { return logicalBugCount; }
        @Override public List<String> logicalBugTypes() { return logicalBugTypes; }
        @Override public boolean trackCoveredFailures() { return trackCoveredFailures; }
        @Override public int currentPatternCount() { return currentPatternCount; }
        @Override public int previousPatternCount() { return previousPatternCount; }
        @Override public int newHighRiskPatternCount() { return newHighRiskPatternCount; }
        @Override public int totalFailures() { return totalFailures; }
        @Override public boolean trackBranchConditions() { return trackBranchConditions; }
        @Override public int currentBranchCount() { return currentBranchCount; }
        @Override public int previousBranchCount() { return previousBranchCount; }
        @Override public int totalBoundaryConditions() { return totalBoundaryConditions; }
        @Override public boolean hasBoundaryTests() { return hasBoundaryTests; }
        @Override public boolean hasBooleanBugTests() { return hasBooleanBugTests; }
        @Override public boolean hasStateTransitionTests() { return hasStateTransitionTests; }
        @Override public boolean hasExceptionPathTests() { return hasExceptionPathTests; }
        @Override public int stagnantCoverageIterations() { return stagnant; }
        @Override public void setStagnantCoverageIterations(int value) { this.stagnant = value; }
    }

    private static DetectedBug bugFrom(JsonNode b) {
        DetectedBug bug = new DetectedBug();
        bug.testMethod = text(b, "test_method");
        bug.error = text(b, "error");
        bug.description = text(b, "description");
        return bug;
    }

    @TestFactory
    Iterator<DynamicTest> f5f8_reward() {
        PredictedIssueMatcher matcher = new PredictedIssueMatcher();
        return family("F5F8_reward", (inputs, expected) -> {
            JsonNode sd = inputs.get("state");
            JsonNode pd = inputs.get("parent");
            boolean fModelPresent = inputs.get("fModelPresent").asBoolean();

            FixtureRewardInputs in = new FixtureRewardInputs();
            in.coverage = sd.get("coverage").asDouble();
            in.hasCompilationErrors = sd.get("compilationErrors").size() > 0;
            in.actionType = text(sd, "metadataActionType");
            in.hadErrorsBefore = sd.get("previousCompilationErrors").size() > 0;
            in.stagnant = sd.get("stagnantSet").asBoolean()
                    ? sd.get("stagnantCoverageIterations").asInt() : 0;

            // detected bugs + D13-matched business-logic confidences
            // (fa_mcts.py:3218-3227: per bug, FIRST matching issue contributes
            // issue.get('confidence', 0.5) then break)
            List<DetectedBug> bugs = new ArrayList<>();
            sd.get("detectedBugs").forEach(b -> bugs.add(bugFrom(b)));
            in.hasDetectedBugs = !bugs.isEmpty();
            List<BusinessLogicIssue> issues = new ArrayList<>();
            sd.get("businessIssues").forEach(i -> issues.add(new BusinessLogicIssue(
                    null, text(i, "method"), text(i, "description"), optDouble(i, "confidence"))));
            List<Double> matched = new ArrayList<>();
            for (DetectedBug bug : bugs) {
                for (BusinessLogicIssue issue : issues) {
                    if (matcher.matches(bug, issue)) {
                        matched.add(issue.confidenceOrHalf());
                        break;
                    }
                }
            }
            in.matchedConfidences = matched;

            in.hasLogicalBugs = sd.get("hasBugs").asBoolean();
            in.logicalBugTypes = stringList(sd.get("logicalBugTypes"));
            in.logicalBugCount = in.logicalBugTypes.size();

            JsonNode cf = sd.get("coveredFailures");
            in.trackCoveredFailures = !cf.isNull();
            List<String> stateCf = stringList(cf);
            in.currentPatternCount = stateCf.size();

            List<String> parentCf = null;
            if (pd != null && !pd.isNull()) {
                JsonNode pcf = pd.get("coveredFailures");
                if (!pcf.isNull()) {
                    parentCf = stringList(pcf);
                }
                if (pd.get("hasCoverage").asBoolean()) {
                    in.parentCoverage = pd.get("coverage").asDouble();
                }
            }
            in.previousPatternCount = parentCf == null ? 0 : parentCf.size();

            // newly-covered high-risk count (fa_mcts.py:3270-3283): only when the parent
            // state exists AND has covered_failures; pattern type = id up to the FIRST '_'
            // (Python pattern_id.split('_')[0] — the load-bearing quirk).
            int highRiskNew = 0;
            if (parentCf != null) {
                for (String id : stateCf) {
                    if (parentCf.contains(id)) {
                        continue;
                    }
                    String patternType = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
                    for (JsonNode f : inputs.get("failures")) {
                        if ("high".equals(text(f, "riskLevel")) && patternType.equals(text(f, "type"))) {
                            highRiskNew++;
                            break;
                        }
                    }
                }
            }
            in.newHighRiskPatternCount = highRiskNew;
            in.totalFailures = inputs.get("failures").size();

            JsonNode cb = sd.get("coveredBranchConditions");
            in.trackBranchConditions = !cb.isNull() && fModelPresent;
            in.currentBranchCount = stringList(cb).size();
            if (pd != null && !pd.isNull() && !pd.get("coveredBranchConditions").isNull()) {
                in.previousBranchCount = stringList(pd.get("coveredBranchConditions")).size();
            }
            in.totalBoundaryConditions = inputs.get("boundaryConditionCount").asInt();

            in.hasBoundaryTests = sd.get("hasBoundaryTests").asBoolean();
            in.hasBooleanBugTests = sd.get("hasBooleanBugTests").asBoolean();
            in.hasStateTransitionTests = sd.get("hasStateTransitionTests").asBoolean();
            in.hasExceptionPathTests = sd.get("hasExceptionPathTests").asBoolean();

            SearchConfig config = SearchConfig.builder()
                    .focusOnBugs(inputs.get("focusOnBugs").asBoolean())
                    .build();
            double reward = new RewardCalculator(config).calculate(in);

            assertClose("reward", reward, expected.get("reward").asText());
            JsonNode stagnantAfter = expected.get("stagnantAfter");
            if (!stagnantAfter.isNull()) {
                assertThat(in.stagnant).as("stagnant_coverage_iterations after call")
                        .isEqualTo(stagnantAfter.asInt());
            }
        });
    }

    // ------------------------------------------------------------------
    // F9 — PatternCoverageTracker
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> f9_patternCoverage() {
        PatternCoverageTracker tracker = new PatternCoverageTracker();
        return family("F9_patternCoverage", (inputs, expected) -> {
            List<FailureScenario> failures = new ArrayList<>();
            for (JsonNode f : inputs.get("failures")) {
                String risk = text(f, "riskLevel");
                failures.add(new FailureScenario(f.get("type").asText(), null,
                        f.get("location").asInt(),
                        risk == null ? null : RiskLevel.fromWire(risk), null, null));
            }
            FaTestState state = new FaTestState(inputs.get("testCode").asText(), null, failures);
            inputs.get("initialScores").fields().forEachRemaining(
                    e -> state.coveredFailuresScores.put(e.getKey(), e.getValue().asDouble()));
            state.coveredFailures.addAll(stringList(inputs.get("initialCovered")));
            for (JsonNode b : inputs.get("logicalBugs")) {
                DetectedBug bug = new DetectedBug();
                bug.description = text(b, "description");
                bug.error = text(b, "error");
                bug.bugType = text(b, "bug_type");
                state.logicalBugs.add(bug);
            }
            for (JsonNode m : inputs.get("testMethods")) {
                state.testMethods.add(new TestMethod(text(m, "name"), text(m, "code")));
            }

            tracker.track(state);

            List<String> coveredSorted = new ArrayList<>(state.coveredFailures);
            coveredSorted.sort(null);
            assertThat(coveredSorted).as("covered set (sorted)")
                    .isEqualTo(stringList(expected.get("covered")));

            // score map: same keys, same insertion ORDER (LinkedHashMap vs dict), same values
            List<String> expectedKeyOrder = stringList(expected.get("scoreKeyOrder"));
            assertThat(new ArrayList<>(state.coveredFailuresScores.keySet()))
                    .as("score map key order").isEqualTo(expectedKeyOrder);
            JsonNode scores = expected.get("scores");
            for (String key : expectedKeyOrder) {
                assertClose("score[" + key + "]", state.coveredFailuresScores.get(key),
                        scores.get(key).asText());
            }
        });
    }

    // ------------------------------------------------------------------
    // D3 — StrategySelector.selectStrategies
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> d3_selectStrategies() {
        return family("D3_selectStrategies", (inputs, expected) -> {
            List<FailureScenario> failures = new ArrayList<>();
            for (JsonNode f : inputs.get("failures")) {
                failures.add(new FailureScenario(f.get("type").asText(), null,
                        f.get("location").asInt(), null, null, null));
            }

            FailureModel fModel = null;
            JsonNode fm = inputs.get("fModel");
            if (!fm.isNull()) {
                List<BoundaryCondition> conds = new ArrayList<>();
                for (JsonNode c : fm.get("boundaryConditions")) {
                    conds.add(new BoundaryCondition(c.get("method").asText(),
                            c.get("line").asInt(), c.get("type").asText(), null));
                }
                fModel = new FailureModel("layera.Fixture", conds, List.of(), List.of(), java.util.Map.of());
            }

            FaTestState state = null;
            JsonNode sdesc = inputs.get("state");
            if (!sdesc.isNull()) {
                state = new FaTestState("", null, null);
                state.coverage = sdesc.get("coverage").asDouble();
                state.parentCoverage = optDouble(sdesc, "parentCoverage");
                for (JsonNode t : sdesc.get("detectedBugTypes")) {
                    DetectedBug bug = new DetectedBug();
                    bug.type = t.asText();
                    state.detectedBugs.add(bug);
                }
            }

            Set<String> coveredPatterns = inputs.get("coveredPatterns").isNull()
                    ? null : new LinkedHashSet<>(stringList(inputs.get("coveredPatterns")));
            Set<String> coveredConditions = inputs.get("coveredConditions").isNull()
                    ? null : new LinkedHashSet<>(stringList(inputs.get("coveredConditions")));

            List<BusinessLogicIssue> issues = null;
            if (!inputs.get("businessIssues").isNull()) {
                issues = new ArrayList<>();
                for (JsonNode i : inputs.get("businessIssues")) {
                    issues.add(new BusinessLogicIssue(text(i, "type"), null, null,
                            optDouble(i, "confidence")));
                }
            }

            List<Strategy> result = new StrategySelector(failures, fModel)
                    .selectStrategies(state, coveredPatterns, coveredConditions, issues);

            JsonNode exp = expected.get("strategies");
            assertThat(result).as("strategy count").hasSize(exp.size());
            for (int i = 0; i < exp.size(); i++) {
                JsonNode e = exp.get(i);
                Strategy s = result.get(i);
                assertThat(s.id()).as("strategy[" + i + "].id (ordered, O4)").isEqualTo(e.get("id").asText());
                assertThat(s.name()).as("strategy[" + i + "].name").isEqualTo(e.get("name").asText());
                assertClose("strategy[" + i + "].weight", s.weight(), e.get("weight").asText());
            }
        });
    }

    // ------------------------------------------------------------------
    // D11 — LogicalBugClassifier.classify
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> d11_classify() {
        LogicalBugClassifier classifier = new LogicalBugClassifier();
        return family("D11_classify", (inputs, expected) -> {
            FaTestState state = new FaTestState("", null, null);
            for (JsonNode b : inputs.get("bugs")) {
                DetectedBug bug = new DetectedBug();
                bug.testMethod = text(b, "testMethod");
                bug.error = text(b, "error");
                bug.description = text(b, "description");
                state.detectedBugs.add(bug);
            }

            classifier.classify(state);

            JsonNode expBugs = expected.get("bugs");
            for (int i = 0; i < expBugs.size(); i++) {
                JsonNode e = expBugs.get(i);
                DetectedBug bug = state.detectedBugs.get(i);
                assertThat(bug.bugCategory).as("bug[" + i + "].bugCategory")
                        .isEqualTo(text(e, "bugCategory"));
                assertThat(bug.bugType).as("bug[" + i + "].bugType").isEqualTo(text(e, "bugType"));
                String conf = text(e, "logicConfidence");
                if (conf == null) {
                    assertThat(bug.logicConfidence).as("bug[" + i + "].logicConfidence").isNull();
                } else {
                    assertThat(bug.logicConfidence).as("bug[" + i + "].logicConfidence").isNotNull();
                    assertClose("bug[" + i + "].logicConfidence", bug.logicConfidence, conf);
                }
            }

            List<Integer> logicalIndices = new ArrayList<>();
            for (DetectedBug lb : state.logicalBugs) {
                for (int i = 0; i < state.detectedBugs.size(); i++) {
                    if (state.detectedBugs.get(i) == lb) {
                        logicalIndices.add(i);
                        break;
                    }
                }
            }
            List<Integer> expectedIndices = new ArrayList<>();
            expected.get("logicalBugIndices").forEach(n -> expectedIndices.add(n.asInt()));
            assertThat(logicalIndices).as("logical bug indices (ordered)").isEqualTo(expectedIndices);
            assertThat(state.hasBugs).as("hasBugs").isEqualTo(expected.get("hasBugs").asBoolean());
        });
    }

    // ------------------------------------------------------------------
    // D13 — PredictedIssueMatcher
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> d13_matcher() {
        PredictedIssueMatcher matcher = new PredictedIssueMatcher();
        return family("D13_matcher", (inputs, expected) -> {
            JsonNode b = inputs.get("bug");
            DetectedBug bug = new DetectedBug();
            bug.testMethod = text(b, "testMethod");
            bug.error = text(b, "error");
            bug.description = text(b, "description");
            JsonNode i = inputs.get("issue");
            BusinessLogicIssue issue = new BusinessLogicIssue(null, text(i, "method"),
                    text(i, "description"), null);
            assertThat(matcher.matches(bug, issue)).as("matches")
                    .isEqualTo(expected.get("matches").asBoolean());
        });
    }

    // ------------------------------------------------------------------
    // F15/D10 — VerificationPolicy (mirrors the verify_bug_with_llm driver flow)
    // ------------------------------------------------------------------

    @TestFactory
    Iterator<DynamicTest> f15d10_verify() {
        VerificationPolicy policy = new VerificationPolicy();
        return family("F15D10_verify", (inputs, expected) -> {
            JsonNode bugInfo = inputs.get("bugInfo");
            double confidence = bugInfo.has("confidence") && !bugInfo.get("confidence").isNull()
                    ? bugInfo.get("confidence").asDouble() : 0.5;
            String testMethod = inputs.get("testMethod").asText();
            String sourceCode = inputs.get("sourceCode").asText();
            String scenario = inputs.get("scenario").asText();
            String response = text(inputs, "response");

            VerificationPolicy.Verdict verdict;
            if (testMethod.isEmpty() || sourceCode.isEmpty()) {
                // verify_bug_with_llm.py:32-39
                verdict = policy.insufficientInput(confidence);
            } else {
                verdict = policy.preFilter(text(bugInfo, "type"), text(bugInfo, "error"),
                        confidence, testMethod);
                if (verdict == null) {
                    if ("api_fail".equals(scenario) || policy.insufficientResponse(response)) {
                        // verify_bug_with_llm.py:131-137 / 146-153
                        verdict = policy.apiFailureDefault(confidence);
                    } else {
                        verdict = policy.parseResponse(response);
                    }
                }
            }

            assertThat(verdict.isRealBug()).as("isRealBug")
                    .isEqualTo(expected.get("isRealBug").asBoolean());
            assertClose("confidence", verdict.confidence(), expected.get("confidence").asText());
            if (expected.get("reasoningCheck").asBoolean()) {
                assertThat(verdict.reasoning()).as("reasoning")
                        .isEqualTo(text(expected, "reasoning"));
            }
        });
    }
}
