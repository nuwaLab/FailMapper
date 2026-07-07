package org.failmapper.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.LogicalOperation;
import org.failmapper.core.model.RiskLevel;

/**
 * D1 — port of {@code generate_possible_actions} ({@code fa_mcts.py:105-386}).
 *
 * <p>Generation ORDER is load-bearing (contract O3: expansion picks
 * {@code random.choice(possible_actions)}, so list order decides what a given draw
 * maps to). Source order preserved exactly:
 * <ol>
 *   <li>compilation errors → a single {@code fix_compilation_errors} action and EARLY
 *       RETURN (bypassing the used-action filter), gated by the global counter
 *       (C7 MAX_FIX_ATTEMPTS = 10) and the {@code failed_fix_paths} set; when either
 *       gate blocks, generation FALLS THROUGH to the normal actions;</li>
 *   <li>one {@code business_logic_test} per predicted issue;</li>
 *   <li>{@code target_line} for up to 5 sampled uncovered lines (C20/R1), skipping
 *       blank/brace/comment contents;</li>
 *   <li>strategy-driven actions in selector order, skipping strategies with
 *       {@code weight < 0.1} (C18 — the Python check at {@code fa_mcts.py:250} is a
 *       strict {@code <}, so weight == 0.1 SURVIVES the cutoff): boundary_test
 *       (2 sampled conditions, C21/R2), expression_test (2 sampled operations, C21/R3),
 *       and the four fixed actions;</li>
 *   <li>{@code bug_pattern_test} for up to 2 sampled high-risk failures (C22/R4);</li>
 *   <li>{@code general_exploration} with 20% probability when other actions exist —
 *       ALWAYS when none (C19/R5; the Python {@code or} short-circuits, so no random
 *       draw is consumed when the list is empty);</li>
 *   <li>{@code fallback} when the list is still empty (dead in practice — step 6 already
 *       guarantees one action — ported verbatim per the iron rule);</li>
 *   <li>finally, actions value-equal to any in {@code node.usedActions} are filtered
 *       out (contract S2).</li>
 * </ol>
 */
public final class ActionGenerator {

    private final SearchConfig config;
    private final RandomSource random;

    public ActionGenerator(SearchConfig config, RandomSource random) {
        this.config = config;
        this.random = random;
    }

    /**
     * Generate possible actions for a node.
     *
     * @param node             the tree node ({@code self}); supplies the used-action
     *                         list, the covered sets passed to the selector, and the
     *                         path signature
     * @param uncoveredLines   {@code uncovered_data["uncovered_lines"]}; null models an
     *                         absent {@code uncovered_data}
     * @param fModel           failure model; may be null
     * @param failures         detected failure scenarios; may be null
     * @param strategySelector may be null → C46 default strategies
     * @param fixTracker       global fix bookkeeping; null models the Python fallback
     *                         where the MCTS instance is unreachable
     *                         ({@code fa_mcts.py:162-172}: emits an ungated fix action)
     */
    public List<SearchAction> generate(FaMctsNode node,
                                       List<UncoveredLine> uncoveredLines,
                                       FailureModel fModel,
                                       List<FailureScenario> failures,
                                       StrategySelector strategySelector,
                                       CompilationFixTracker fixTracker) {
        FaTestState state = node.state instanceof FaTestState s ? s : null;
        List<SearchAction> possibleActions = new ArrayList<>();

        // (1) Compilation errors → prioritized fix action (fa_mcts.py:124-172).
        if (hasCompilationErrors(state)) {
            if (fixTracker != null) {
                int globalAttempts = fixTracker.globalAttempts();
                if (globalAttempts >= config.maxFixAttempts) {
                    // Reached the global limit — explore alternative paths (fall through).
                } else {
                    String pathSignature = node.pathSignature();
                    if (fixTracker.pathFailed(pathSignature)) {
                        // This path already failed to fix — explore alternatives (fall through).
                    } else {
                        fixTracker.incrementGlobalAttempts();
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("description", "Fix compilation errors in test code");
                        attrs.put("errors", List.copyOf(state.compilationErrors));
                        attrs.put("attempt", globalAttempts + 1);
                        attrs.put("path_signature", pathSignature);
                        possibleActions.add(new SearchAction("fix_compilation_errors", attrs));
                        return possibleActions; // early return — no used-action filter
                    }
                }
            } else {
                // Fallback when global tracking is unreachable (fa_mcts.py:162-172).
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("description", "Fix compilation errors in test code");
                attrs.put("errors", List.copyOf(state.compilationErrors));
                attrs.put("attempt", 1);
                possibleActions.add(new SearchAction("fix_compilation_errors", attrs));
                return possibleActions;
            }
        }

        // Business-logic issues (fa_mcts.py:174-175): truthiness of the analysis.
        List<BusinessLogicIssue> businessLogicIssues =
                (state != null && !state.businessLogicIssues.isEmpty())
                        ? state.businessLogicIssues
                        : List.of();

        // Strategies (fa_mcts.py:182-196).
        List<Strategy> strategies;
        if (strategySelector != null) {
            strategies = strategySelector.selectStrategies(
                    state, node.coveredPatterns, node.coveredBranchConditions, businessLogicIssues);
        } else {
            // C46 default strategies when no selector is present.
            strategies = List.of(
                    new Strategy("boundary_testing", "Boundary Value Testing", 1.0),
                    new Strategy("expression", "Expression Testing", 1.0),
                    new Strategy("exception_handling", "Exception Path Testing", 0.7));
        }

        // (2) One business_logic_test per issue (fa_mcts.py:198-212).
        for (BusinessLogicIssue issue : businessLogicIssues) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("issue_type", issue.typeOr("unknown"));
            attrs.put("method", issue.method() == null ? "" : issue.method());
            attrs.put("description", "Test for potential business logic issue: "
                    + (issue.description() == null ? "" : issue.description()));
            attrs.put("confidence", issue.confidenceOrZero()); // .get('confidence', 0)
            attrs.put("business_logic", Boolean.TRUE);
            possibleActions.add(new SearchAction("business_logic_test", attrs));
        }

        // (3) target_line actions for sampled uncovered lines (fa_mcts.py:214-242).
        if (uncoveredLines != null && !uncoveredLines.isEmpty()) {
            List<UncoveredLine> selectedLines = random.sample(
                    uncoveredLines, Math.min(config.uncoveredLineSampleSize, uncoveredLines.size()));
            for (UncoveredLine lineInfo : selectedLines) {
                int lineNum = lineInfo.line();
                String content = lineInfo.contentOrEmpty().strip();
                // Skip empty or irrelevant lines (fa_mcts.py:232).
                if (content.isEmpty() || content.equals("}") || content.equals("{")
                        || content.equals("//") || content.equals("/*") || content.equals("*/")) {
                    continue;
                }
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("line", lineNum);
                attrs.put("content", content);
                attrs.put("description", "Target uncovered line " + lineNum + ": " + prefix40(content) + "...");
                possibleActions.add(new SearchAction("target_line", attrs));
            }
        }

        // (4) Strategy-based actions (fa_mcts.py:244-340).
        for (Strategy strategy : strategies) {
            String strategyId = strategy.idOrUnknown();
            double strategyWeight = strategy.weight();

            // C18 — strict <: strategies with weight exactly 0.1 are kept (fa_mcts.py:250).
            if (strategyWeight < config.strategyWeightCutoff) {
                continue;
            }

            if (strategyId.equals("boundary_testing") && fModel != null) {
                List<BoundaryCondition> boundaryConditions = fModel.boundaryConditions();
                if (boundaryConditions != null && !boundaryConditions.isEmpty()) {
                    List<BoundaryCondition> selected = random.sample(
                            boundaryConditions, Math.min(config.conditionSampleSize, boundaryConditions.size()));
                    for (BoundaryCondition condition : selected) {
                        String conditionStr = condition.expression() == null ? "" : condition.expression();
                        int lineNum = condition.line();
                        if (conditionStr.isEmpty()) {
                            continue; // Python: `if not condition_str: continue`
                        }
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("condition", conditionStr);
                        attrs.put("line", lineNum);
                        attrs.put("strategy", strategyId);
                        attrs.put("description", "Test boundary condition at line " + lineNum
                                + ": " + prefix40(conditionStr) + "...");
                        possibleActions.add(new SearchAction("boundary_test", attrs));
                    }
                }
            } else if (strategyId.equals("expression") && fModel != null) {
                List<LogicalOperation> operations = fModel.operations();
                if (operations != null && !operations.isEmpty()) {
                    List<LogicalOperation> selected = random.sample(
                            operations, Math.min(config.conditionSampleSize, operations.size()));
                    for (LogicalOperation operation : selected) {
                        // Python reads the "condition" key of the operation dict (fa_mcts.py:291).
                        String operationStr = operation.expression() == null ? "" : operation.expression();
                        int lineNum = operation.line();
                        if (operationStr.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("operation", operationStr);
                        attrs.put("line", lineNum);
                        attrs.put("strategy", strategyId);
                        attrs.put("description", "Test operation at line " + lineNum
                                + ": " + prefix40(operationStr) + "...");
                        possibleActions.add(new SearchAction("expression_test", attrs));
                    }
                }
            } else if (strategyId.equals("exception_handling")) {
                possibleActions.add(fixedStrategyAction("exception_test", strategyId,
                        "Generate tests for exception paths"));
            } else if (strategyId.equals("data_validation")) {
                possibleActions.add(fixedStrategyAction("data_validation_test", strategyId,
                        "Generate tests for data validation edge cases"));
            } else if (strategyId.equals("resource_management")) {
                possibleActions.add(fixedStrategyAction("resource_management_test", strategyId,
                        "Generate tests for resource management issues"));
            } else if (strategyId.equals("state_transition")) {
                possibleActions.add(fixedStrategyAction("state_transition_test", strategyId,
                        "Generate tests for state transitions"));
            }
            // NOTE: the "business_logic" strategy id has NO action branch in Python —
            // business-logic actions come exclusively from step (2).
        }

        // (5) bug_pattern_test for sampled high-risk failures (fa_mcts.py:342-364).
        if (failures != null && !failures.isEmpty()) {
            List<FailureScenario> highRiskPatterns = new ArrayList<>();
            for (FailureScenario p : failures) {
                if (p.riskLevel() == RiskLevel.HIGH) {
                    highRiskPatterns.add(p);
                }
            }
            if (!highRiskPatterns.isEmpty()) {
                List<FailureScenario> selected = random.sample(
                        highRiskPatterns, Math.min(config.highRiskPatternSampleSize, highRiskPatterns.size()));
                for (FailureScenario pattern : selected) {
                    String patternType = pattern.type() == null ? "unknown" : pattern.type();
                    int lineNum = pattern.line();
                    String description = pattern.description() == null ? "" : pattern.description();
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("pattern_type", patternType);
                    attrs.put("line", lineNum);
                    attrs.put("description", "Test for " + patternType + " bug pattern at line "
                            + lineNum + ": " + prefix40(description) + "...");
                    possibleActions.add(new SearchAction("bug_pattern_test", attrs));
                }
            }
        }

        // (6) general_exploration — 20% chance, or always when no actions (fa_mcts.py:366-372).
        // Short-circuit preserved: no random draw when the list is empty.
        if (possibleActions.isEmpty() || random.nextDouble() < config.generalExplorationProbability) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("description", "General test exploration");
            possibleActions.add(new SearchAction("general_exploration", attrs));
        }

        // (7) fallback — unreachable after (6), ported verbatim (fa_mcts.py:374-380).
        if (possibleActions.isEmpty()) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("description", "Fallback test generation");
            possibleActions.add(new SearchAction("fallback", attrs));
        }

        // (8) used-action filter by VALUE equality (fa_mcts.py:385, contract S2).
        List<SearchAction> filtered = new ArrayList<>();
        for (SearchAction action : possibleActions) {
            if (!node.usedActions.contains(action)) {
                filtered.add(action);
            }
        }
        return filtered;
    }

    /** {@code has_compilation_errors} ({@code fa_mcts.py:75-103}): state truthy AND errors truthy. */
    static boolean hasCompilationErrors(FaTestState state) {
        return state != null && state.compilationErrors != null && !state.compilationErrors.isEmpty();
    }

    private static SearchAction fixedStrategyAction(String type, String strategyId, String description) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("strategy", strategyId);
        attrs.put("description", description);
        return new SearchAction(type, attrs);
    }

    /** Python {@code s[:40]} — safe prefix slice. */
    private static String prefix40(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40);
    }
}
