package org.failmapper.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Node in the failure-aware MCTS tree — port of {@code FA_MCTSNode}
 * ({@code fa_mcts.py:33-574}; the algorithmic core is {@code best_child} at 392-451
 * and {@code update} at 513-574).
 *
 * <p>IRON RULE (contract section 1): the UCB here is deliberately NON-standard —
 * {@code sqrt(N_parent/n_child)} with NO log, multiplied by 2 (F1) — and {@code update}
 * decays accumulated wins by 0.9 on failure signals (F3). Both look like bugs; both are
 * load-bearing. Do not "fix" them.
 *
 * <p>Mutable statistics are public fields, mirroring the Python object's open attributes;
 * the orchestrator and tests mutate them directly, exactly as the Python code does.
 *
 * <p>Ordering contract O1: {@link #children} is an insertion-ordered ArrayList and
 * {@link #bestChild} iterates it in order with a strict {@code >} update, so the FIRST
 * maximal child wins ties — critical because all unvisited children tie at +infinity
 * and the child expanded next is decided purely by insertion order.
 *
 * <p>D1 bookkeeping now lives here too: {@link #usedActions},
 * {@link #coveredPatterns}/{@link #coveredBranchConditions} ({@code fa_mcts.py:64-65}),
 * {@link #bugTypesFound} and {@link #pathSignature()}; {@code generate_possible_actions}
 * itself is ported as {@link ActionGenerator}.
 */
public final class FaMctsNode {

    /** The test state this node wraps ({@code self.state}). Opaque to the kernel; may be null. */
    public final Object state;

    /** Parent node; null for the root ({@code self.parent}). */
    public final FaMctsNode parent;

    /** Action taken to reach this state; null for the root ({@code self.action}). */
    public final SearchAction action;

    /** Insertion-ordered children ({@code self.children = []}); order is load-bearing (O1). */
    public final List<FaMctsNode> children = new ArrayList<>();

    /** Accumulated reward ({@code self.wins = 0.0}); decays by 0.9 on failure signals (F3/C12). */
    public double wins = 0.0;

    /** Visit count ({@code self.visits = 0}). */
    public int visits = 0;

    /** F4/C14: +1.0 per {@code logical_}-prefixed bug type backpropagated through this node. */
    public double logicBugRewards = 0.0;

    /** F4/C16-C17: accrues min(coveredFailures/10, 1) + min(coveredBranches/20, 1) per update. */
    public double failureCoverageRewards = 0.0;

    /** F4/C15: +0.8 per {@code high_risk_}-prefixed bug type backpropagated through this node. */
    public double highRiskPatternRewards = 0.0;

    /** Count of logical bugs credited to this node ({@code self.bugs_found}, incremented in F4). */
    public int bugsFound = 0;

    /** Novelty flag ({@code self.is_novel}); grants the 0.2 bonus (C8) inside the UCB logic bonus. */
    public boolean isNovel = false;

    /** Expansion flag ({@code self.expanded}); set by the expansion step (D5). */
    public boolean expanded = false;

    /**
     * Actions already tried from this node ({@code self.used_action = []},
     * {@code fa_mcts.py:73}). Deduplication in D1 is by VALUE equality (contract S2:
     * {@code fa_mcts.py:385} compares action dicts by contents) — {@link SearchAction}
     * is a record, so {@code List.contains} matches Python's {@code in} check.
     */
    public final List<SearchAction> usedActions = new ArrayList<>();

    /** Pattern ids covered under this node ({@code self.covered_patterns = set()}, {@code fa_mcts.py:64}). */
    public final LinkedHashSet<String> coveredPatterns = new LinkedHashSet<>();

    /** Condition ids covered under this node ({@code self.covered_branch_conditions = set()}, {@code fa_mcts.py:65}). */
    public final LinkedHashSet<String> coveredBranchConditions = new LinkedHashSet<>();

    /** Bug types found by this node and its children ({@code self.bug_types_found = set()}, {@code fa_mcts.py:68}). */
    public final LinkedHashSet<String> bugTypesFound = new LinkedHashSet<>();

    /**
     * Consecutive failure-signal count (F3). Python creates this attribute lazily
     * ({@code fa_mcts.py:528-529}) defaulting to 0; a plain int field is equivalent.
     */
    public int consecutiveFailures = 0;

    /**
     * Action type of the child most recently selected FROM this node — feeds the 0.15
     * diversity bonus (F2/C11). Python NEVER initializes this attribute in
     * {@code __init__}; it is stamped on the parent by the selection policy
     * ({@code fa_mcts.py:2561}). {@code null} here encodes Python's attribute-not-set
     * state: while null, the {@code hasattr(self, 'last_action_type')} guard at
     * {@code fa_mcts.py:436} fails and NO child receives the diversity bonus.
     */
    public String lastActionType = null;

    public FaMctsNode(Object state, FaMctsNode parent, SearchAction action) {
        this.state = state;
        this.parent = parent;
        this.action = action;
    }

    /** Root constructor. */
    public FaMctsNode(Object state) {
        this(state, null, null);
    }

    /**
     * Create a child node and append it to {@link #children} in insertion order.
     * The novelty detection of Python {@code add_child} ({@code fa_mcts.py:470-491})
     * requires state introspection and lives in the orchestrator layer; callers set
     * {@link #isNovel} on the returned child themselves.
     */
    public FaMctsNode addChild(Object childState, SearchAction childAction) {
        FaMctsNode child = new FaMctsNode(childState, this, childAction);
        children.add(child);
        return child;
    }

    /** {@code is_fully_expanded} ({@code fa_mcts.py:388-390}). */
    public boolean isFullyExpanded() {
        return expanded;
    }

    /**
     * {@code _get_path_signature} ({@code fa_mcts.py:496-511}) — the action-type path
     * from the root to this node joined with {@code "->"}, used as the
     * {@code failed_fix_paths} key in D1. Python walks {@code while current.parent},
     * appending {@code current.action.get('type', 'unknown')} when the action dict
     * exists, then reverses. A null action is skipped; a null type maps to "unknown".
     */
    public String pathSignature() {
        List<String> path = new ArrayList<>();
        FaMctsNode current = this;
        while (current.parent != null) {
            if (current.action != null) {
                path.add(current.action.type() != null ? current.action.type() : "unknown");
            }
            current = current.parent;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = path.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append("->");
            }
            sb.append(path.get(i));
        }
        return sb.toString();
    }

    /**
     * F1 + F2 — select the best child by the non-standard failure-aware UCB score
     * (port of {@code best_child}, {@code fa_mcts.py:392-451}).
     *
     * <p>Python signature defaults are {@code exploration_weight=1.0, f_weight=1.0}
     * ({@code fa_mcts.py:392}), but live callers always pass the instance values
     * (C1=1.0, C2=2.0) — see contract C2 note.
     *
     * <p>Tie-break (contract O1): Python {@code max(children, key=ucb_score)} keeps the
     * FIRST maximal element; this loop iterates {@link #children} in insertion order and
     * only replaces the incumbent on a STRICT {@code >}.
     *
     * @return the best child, or null when this node has no children
     *         ({@code fa_mcts.py:403-404})
     */
    public FaMctsNode bestChild(double explorationWeight, double fWeight) {
        if (children.isEmpty()) {
            return null;
        }
        FaMctsNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (FaMctsNode child : children) {
            double score = ucbScore(child, explorationWeight, fWeight);
            if (best == null || score > bestScore) {
                best = child;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The UCB score of one child as seen from this node ({@code ucb_score} closure,
     * {@code fa_mcts.py:406-448}). Exposed for layer-A differential tests.
     *
     * <pre>
     * exploitation = child.wins / child.visits                      (0.0 if unvisited)
     * exploration  = exploration_weight * (2 * sqrt(self.visits / child.visits))
     *                                                               (+inf if unvisited — F1;
     *                NON-standard UCB1: sqrt(N/n), NO log, times 2)
     * logic_bonus  = f_weight * ((logic_bug_term + logic_coverage_term + high_risk_term
     *                             + novelty_bonus) * visits_decay * failure_penalty)
     *                + diversity_bonus                               (0.0 if unvisited — F2)
     *   logic_bug_term      = child.logicBugRewards / child.visits
     *   logic_coverage_term = child.failureCoverageRewards / child.visits
     *   high_risk_term      = child.highRiskPatternRewards / child.visits
     *   novelty_bonus       = 0.2 if child.isNovel else 0.0                     (C8)
     *   visits_decay        = 1.0 / (1.0 + 0.1 * child.visits)                  (C9)
     *   failure_penalty     = max(0.3, 1.0 - 0.2*consecutiveFailures) if &gt; 0 else 1.0 (C10)
     *   diversity_bonus     = 0.15 if child action type != this.lastActionType  (C11;
     *                         requires lastActionType set — see field doc)
     * </pre>
     *
     * All divisions are double (int/int true division trap — contract N1); unvisited
     * children score {@code Double.POSITIVE_INFINITY} (contract N2), so they all tie
     * and insertion order decides (O1).
     */
    public double ucbScore(FaMctsNode child, double explorationWeight, double fWeight) {
        double exploitation = child.visits > 0 ? child.wins / child.visits : 0.0;
        double exploration = child.visits > 0
                ? explorationWeight * (2.0 * Math.sqrt((double) this.visits / (double) child.visits))
                : Double.POSITIVE_INFINITY;

        double logicBonus = 0.0;
        if (child.visits > 0) {
            double logicBugTerm = child.logicBugRewards / child.visits;
            double logicCoverageTerm = child.failureCoverageRewards / child.visits;
            double highRiskTerm = child.highRiskPatternRewards / child.visits;
            double noveltyBonus = child.isNovel ? 0.2 : 0.0;
            double visitsDecay = 1.0 / (1.0 + 0.1 * child.visits);

            double failurePenalty = 1.0;
            if (child.consecutiveFailures > 0) {
                failurePenalty = Math.max(0.3, 1.0 - (0.2 * child.consecutiveFailures));
            }

            double diversityBonus = 0.0;
            // Python guards: hasattr(child,'action') and hasattr(self,'last_action_type')
            // and isinstance(child.action, dict) and 'type' in child.action
            // (fa_mcts.py:436-439). lastActionType == null <=> attribute never set.
            if (this.lastActionType != null && child.action != null && child.action.type() != null
                    && !child.action.type().equals(this.lastActionType)) {
                diversityBonus = 0.15;
            }

            logicBonus = fWeight * (
                    (logicBugTerm + logicCoverageTerm + highRiskTerm + noveltyBonus)
                            * visitsDecay * failurePenalty
            ) + diversityBonus;
        }

        return exploitation + exploration + logicBonus;
    }

    /**
     * Convenience overload of {@link #update(double, String, int, int, boolean)} with
     * {@code hasError = false} — the Python default; note the live backpropagation loop
     * ({@code fa_mcts.py:3134}) never passes {@code has_error}, so the failure signal is
     * in practice always driven by {@code reward < 0.1}.
     */
    public void update(double reward, String bugType, int coveredFailuresCount, int coveredBranchConditionsCount) {
        update(reward, bugType, coveredFailuresCount, coveredBranchConditionsCount, false);
    }

    /**
     * F3 + F4 — update node statistics after simulation (port of {@code update},
     * {@code fa_mcts.py:513-574}).
     *
     * <pre>
     * visits += 1; wins += reward
     * if hasError or reward &lt; 0.1:  consecutiveFailures += 1; wins *= 0.9   (F3, C12/C13)
     * else:                            consecutiveFailures = 0
     * if bugType startswith "logical_":    logicBugRewards += 1.0; bugsFound += 1  (C14)
     * elif bugType startswith "high_risk_": highRiskPatternRewards += 0.8          (C15)
     * failureCoverageRewards += min(coveredFailuresCount / 10.0, 1.0)              (C16)
     * failureCoverageRewards += min(coveredBranchConditionsCount / 20.0, 1.0)      (C17)
     * </pre>
     *
     * <p>IMPORTANT — which counts to pass: Python reads
     * {@code len(self.state.covered_failures)} / {@code len(self.state.covered_branch_conditions)}
     * of the node's OWN state ({@code fa_mcts.py:560/569}); the {@code pattern_coverage}/
     * {@code branch_coverage} parameters passed down from backpropagation are the LEAF
     * state's sets and are only stored into bookkeeping fields (shadowed locals at
     * 560/569 make them irrelevant to the accrual). Callers must therefore pass the
     * size of THIS node's own state's covered sets. Pass a NEGATIVE count to model a
     * node whose state is null / lacks the attribute ({@code fa_mcts.py:557-559/568}),
     * which skips that accrual term.
     *
     * <p>{@code bugType} accrual requires a non-null, NON-EMPTY string: Python's
     * {@code if bug_type:} treats {@code ""} as falsy.
     *
     * <p>Failure-signal threshold is STRICT: {@code reward == 0.1} is a success.
     */
    public void update(double reward, String bugType, int coveredFailuresCount,
                       int coveredBranchConditionsCount, boolean hasError) {
        visits += 1;
        wins += reward;

        if (hasError || reward < 0.1) {
            consecutiveFailures += 1;
            wins *= 0.9; // slight decay of accumulated rewards (C12) — deliberate, load-bearing
        } else {
            consecutiveFailures = 0;
        }

        if (bugType != null && !bugType.isEmpty()) {
            if (bugType.startsWith("logical_")) {
                logicBugRewards += 1.0;
                bugsFound += 1;
            } else if (bugType.startsWith("high_risk_")) {
                highRiskPatternRewards += 0.8;
            }
        }

        if (coveredFailuresCount >= 0) {
            failureCoverageRewards += Math.min(coveredFailuresCount / 10.0, 1.0);
        }
        if (coveredBranchConditionsCount >= 0) {
            failureCoverageRewards += Math.min(coveredBranchConditionsCount / 20.0, 1.0);
        }
    }

    /**
     * The node's win total as reported in history entries — I11-REGISTERED RESOLUTION
     * of the Python and/or chain at {@code fa_mcts.py:2344/2363}:
     * {@code hasattr(node,'wins') and node.wins or (hasattr(node,'value') and node.value or 0.0)}.
     *
     * <p>Because {@code 0.0} is falsy in Python, a node whose {@code wins == 0.0} falls
     * through to {@code node.value}; {@code FA_MCTSNode} has no {@code value} attribute,
     * so the chain then yields {@code 0.0} — numerically identical to {@code wins} for
     * this node type. Per the improvements register (contract section 4, I11) the port
     * uses the explicit form and returns {@code wins} directly. REGISTERED DEVIATION:
     * for a hypothetical base-class node carrying {@code value}, Python would report
     * {@code value} whenever {@code wins == 0.0}; the Java node type has no such field,
     * so {@code wins} is always reported. Layer-D regression: FaMctsNodeUpdateTest.
     */
    public double historyWins() {
        return wins;
    }
}
