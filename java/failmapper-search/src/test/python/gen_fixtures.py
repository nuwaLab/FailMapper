#!/usr/bin/env python3
"""
Layer-A differential fixture generator (JAVA_PORT_CONTRACT.md section 5, layer A).

Calls the REAL Python implementations from the FailMapper baseline (commit d2baa9e)
and records inputs + expected outputs as fixtures.json. The Java side
(LayerADifferentialTest in failmapper-search) replays the same inputs against the
ported kernel and asserts equality (doubles via repr round-trip, epsilon 1e-9).

Oracle-access technique: heavy classes (FA_MCTSNode, FA_MCTS, FATestState,
TestStrategySelector) are instantiated with object.__new__(Class), setting ONLY the
attributes the target method reads (enumerated by reading each method). For raw UCB
scores, the module-global `max` used by FA_MCTSNode.best_child is shadowed with a
capturing wrapper (fa_mcts.max = ...), which records key(child) for every child from
the GENUINE ucb_score closure, then delegates to builtins.max.

None of the covered kernel formulas (F1-F9, F15, D3, D10, D11, D13) consumes
random.* — the randomTrace field of the schema is therefore unused (empty) in every
case; Python's random module is seeded anyway for the CASE-PARAMETER sampling so the
fixture file itself is reproducible.

Regen:
    cd /Users/ruiqidong/Desktop/FailMapper/python-baseline
    python3 <this file> [output.json]
"""

import json
import logging
import random
import sys
from types import SimpleNamespace

sys.path.insert(0, "/Users/ruiqidong/Desktop/FailMapper/python-baseline")

logging.disable(logging.CRITICAL)  # silence the modules' INFO chatter

import fa_mcts  # noqa: E402
import test_state  # noqa: E402
import test_generation_strategies  # noqa: E402
import verify_bug_with_llm as vb  # noqa: E402

FA_MCTSNode = fa_mcts.FA_MCTSNode
FA_MCTS = fa_mcts.FA_MCTS
FATestState = test_state.FATestState
TestStrategySelector = test_generation_strategies.TestStrategySelector

random.seed(20260707)

CASES = []


def fnum(x):
    """repr of a Python float — shortest round-trip; Java Double.parseDouble-safe."""
    if x == float("inf"):
        return "inf"
    if x == float("-inf"):
        return "-inf"
    return repr(float(x))


def add_case(formula, case_id, inputs, expected):
    CASES.append({
        "formula": formula,
        "caseId": case_id,
        "inputs": inputs,
        "expected": expected,
        "randomTrace": [],  # no covered formula consumes random.* (see module docstring)
    })


# ---------------------------------------------------------------------------
# F3/F4 — FA_MCTSNode.update (fa_mcts.py:513-574)
# ---------------------------------------------------------------------------

def make_update_node(init):
    node = object.__new__(FA_MCTSNode)
    node.visits = init.get("visits", 0)
    node.wins = init.get("wins", 0.0)
    node.logic_bug_rewards = init.get("logicBugRewards", 0.0)
    node.failure_coverage_rewards = init.get("failureCoverageRewards", 0.0)
    node.high_risk_pattern_rewards = init.get("highRiskPatternRewards", 0.0)
    node.bugs_found = init.get("bugsFound", 0)
    node.covered_patterns = set()
    node.covered_branch_conditions = set()
    if init.get("consecutiveFailuresSet", False):
        node.consecutive_failures = init.get("consecutiveFailures", 0)
    return node


def set_update_state(node, state_kind, cf_size, cb_size):
    if state_kind == "none":
        node.state = None
    elif state_kind == "patternsOnly":
        node.state = SimpleNamespace(covered_failures={f"p{i}" for i in range(cf_size)})
    elif state_kind == "branchesOnly":
        node.state = SimpleNamespace(covered_branch_conditions={f"b{i}" for i in range(cb_size)})
    else:  # both
        node.state = SimpleNamespace(
            covered_failures={f"p{i}" for i in range(cf_size)},
            covered_branch_conditions={f"b{i}" for i in range(cb_size)},
        )


def snapshot(node):
    return {
        "visits": node.visits,
        "wins": fnum(node.wins),
        "consecutiveFailures": getattr(node, "consecutive_failures", 0),
        "logicBugRewards": fnum(node.logic_bug_rewards),
        "failureCoverageRewards": fnum(node.failure_coverage_rewards),
        "highRiskPatternRewards": fnum(node.high_risk_pattern_rewards),
        "bugsFound": node.bugs_found,
    }


def gen_f3f4():
    rewards = [0.0, 0.05, 0.1, 0.3, 1.0, 2.0]
    bug_types = ["logical_boundary_error", "high_risk_overflow", "other_bug", None]
    sizes = [0, 3, 10, 15, 25]
    n = 0

    # structured grid: 6 rewards x 4 bug types = 24 cases (sizes/has_error cycled)
    for ri, reward in enumerate(rewards):
        for bi, bug_type in enumerate(bug_types):
            n += 1
            cf = sizes[(ri + bi) % len(sizes)]
            cb = sizes[(ri + 2 * bi + 1) % len(sizes)]
            has_error = (ri + bi) % 2 == 0
            init = {"visits": 0, "wins": 0.0}
            updates = [
                {"reward": reward, "bugType": bug_type, "hasError": has_error,
                 "stateKind": "both", "coveredFailuresSize": cf, "coveredBranchSize": cb},
                {"reward": reward, "bugType": bug_type, "hasError": not has_error,
                 "stateKind": "both", "coveredFailuresSize": cf, "coveredBranchSize": cb},
                {"reward": reward, "bugType": bug_type, "hasError": has_error,
                 "stateKind": "both", "coveredFailuresSize": cf, "coveredBranchSize": cb},
            ]
            run_f3f4_case(f"F3F4-{n:03d}", init, updates)

    # randomized extras incl. state-kind variants, empty bugType, preloaded stats
    for _ in range(16):
        n += 1
        init = {
            "visits": random.randint(0, 12),
            "wins": round(random.uniform(0.0, 5.0), 3),
            "logicBugRewards": round(random.uniform(0.0, 3.0), 3),
            "failureCoverageRewards": round(random.uniform(0.0, 4.0), 3),
            "highRiskPatternRewards": round(random.uniform(0.0, 2.0), 3),
            "bugsFound": random.randint(0, 4),
            "consecutiveFailuresSet": random.random() < 0.5,
            "consecutiveFailures": random.randint(0, 5),
        }
        updates = []
        for _u in range(3):
            updates.append({
                "reward": random.choice([0.0, 0.05, 0.09999, 0.1, 0.10001, 0.5, 1.3, 2.0]),
                "bugType": random.choice(
                    ["logical_x", "high_risk_y", "logical_", "high_risk_", "", "plain", None]),
                "hasError": random.random() < 0.3,
                "stateKind": random.choice(["both", "both", "none", "patternsOnly", "branchesOnly"]),
                "coveredFailuresSize": random.choice([0, 1, 3, 9, 10, 11, 25]),
                "coveredBranchSize": random.choice([0, 2, 19, 20, 21, 40]),
            })
        run_f3f4_case(f"F3F4-{n:03d}", init, updates)


def run_f3f4_case(case_id, init, updates):
    node = make_update_node(init)
    snaps = []
    for u in updates:
        set_update_state(node, u["stateKind"], u["coveredFailuresSize"], u["coveredBranchSize"])
        node.update(u["reward"], bug_type=u["bugType"], has_error=u["hasError"])
        snaps.append(snapshot(node))
    add_case("F3F4_update", case_id, {"init": init, "updates": updates}, {"snapshots": snaps})


# ---------------------------------------------------------------------------
# F1/F2 — FA_MCTSNode.best_child / ucb_score (fa_mcts.py:392-451)
# ---------------------------------------------------------------------------

def gen_f1f2():
    import builtins
    action_types = ["boundary_test", "expression_test", "bug_pattern_test",
                    "general_exploration", "target_line"]
    n = 0

    def build_child(spec):
        c = object.__new__(FA_MCTSNode)
        c.visits = spec["visits"]
        c.wins = spec["wins"]
        c.logic_bug_rewards = spec["logicBugRewards"]
        c.failure_coverage_rewards = spec["failureCoverageRewards"]
        c.high_risk_pattern_rewards = spec["highRiskPatternRewards"]
        c.is_novel = spec["isNovel"]
        if spec["consecutiveFailures"] is not None:
            c.consecutive_failures = spec["consecutiveFailures"]
        if spec["actionType"] == "__NO_ACTION__":
            c.action = None
        elif spec["actionType"] == "__EMPTY_DICT__":
            c.action = {}
        else:
            c.action = {"type": spec["actionType"]}
        return c

    def run_case(case_id, parent_visits, last_action_type, ew, fw, child_specs):
        parent = object.__new__(FA_MCTSNode)
        parent.visits = parent_visits
        if last_action_type is not None:
            parent.last_action_type = last_action_type
        parent.children = [build_child(s) for s in child_specs]

        captured = {}

        def capturing_max(*args, **kwargs):
            if len(args) == 1 and "key" in kwargs:
                items = list(args[0])
                key = kwargs["key"]
                captured["scores"] = [key(x) for x in items]
                return builtins.max(items, key=key)
            return builtins.max(*args, **kwargs)

        fa_mcts.max = capturing_max
        try:
            chosen = parent.best_child(exploration_weight=ew, f_weight=fw)
        finally:
            del fa_mcts.max

        chosen_index = next(i for i, c in enumerate(parent.children) if c is chosen)
        add_case("F1F2_bestChild", case_id, {
            "parentVisits": parent_visits,
            "lastActionType": last_action_type,
            "explorationWeight": ew,
            "fWeight": fw,
            "children": child_specs,
        }, {
            "chosenIndex": chosen_index,
            "scores": [fnum(s) for s in captured["scores"]],
        })

    def spec(visits=1, wins=0.0, lbr=0.0, fcr=0.0, hrr=0.0, novel=False, cf=None, at="boundary_test"):
        return {"visits": visits, "wins": wins, "logicBugRewards": lbr,
                "failureCoverageRewards": fcr, "highRiskPatternRewards": hrr,
                "isNovel": novel, "consecutiveFailures": cf, "actionType": at}

    # hand-built cases pinning specific mechanics
    hand = [
        # all unvisited -> +inf tie, first wins (O1)
        (10, None, 1.0, 2.0, [spec(visits=0), spec(visits=0), spec(visits=0)]),
        # one unvisited among visited -> unvisited wins
        (10, None, 1.0, 2.0, [spec(visits=3, wins=2.5), spec(visits=0), spec(visits=2, wins=1.9)]),
        # exact score tie between identical visited children -> first wins
        (8, None, 1.0, 2.0, [spec(visits=2, wins=1.0), spec(visits=2, wins=1.0)]),
        # novelty bonus decides
        (8, None, 1.0, 2.0, [spec(visits=2, wins=1.0), spec(visits=2, wins=1.0, novel=True)]),
        # consecutive failures penalty (floor at 0.3: cf=4 -> 0.2; max(0.3, .2)=0.3)
        (8, None, 1.0, 2.0, [spec(visits=2, wins=1.0, lbr=2.0, cf=4), spec(visits=2, wins=1.0, lbr=2.0, cf=1)]),
        # cf attr absent vs cf=0 must score identically
        (8, None, 1.0, 2.0, [spec(visits=2, wins=1.0, lbr=1.0, cf=None), spec(visits=2, wins=1.0, lbr=1.0, cf=0)]),
        # diversity bonus: parent.last_action_type set, differing child type gets +0.15
        (8, "boundary_test", 1.0, 2.0, [spec(visits=2, wins=1.0), spec(visits=2, wins=1.0, at="expression_test")]),
        # last_action_type absent -> NO diversity bonus for anyone
        (8, None, 1.0, 2.0, [spec(visits=2, wins=1.0), spec(visits=2, wins=1.0, at="expression_test")]),
        # action None / dict without 'type' -> no diversity bonus
        (8, "boundary_test", 1.0, 2.0, [spec(visits=2, wins=1.0, at="__NO_ACTION__"),
                                        spec(visits=2, wins=1.0, at="__EMPTY_DICT__"),
                                        spec(visits=2, wins=1.0, at="expression_test")]),
        # parent visits 0 (root before backprop): exploration term 0 for visited children
        (0, None, 1.0, 2.0, [spec(visits=1, wins=0.4), spec(visits=1, wins=0.5)]),
        # exploration weight 1.5 (temp exploration, D4)
        (12, "expression_test", 1.5, 2.0, [spec(visits=4, wins=2.0), spec(visits=1, wins=0.2)]),
        # f_weight 1.0 (best_child signature default)
        (12, None, 1.0, 1.0, [spec(visits=4, wins=2.0, lbr=3.0), spec(visits=4, wins=2.2)]),
        # high visit decay: same rewards, more visits
        (30, None, 1.0, 2.0, [spec(visits=10, wins=5.0, lbr=5.0, fcr=3.0), spec(visits=2, wins=1.0, lbr=1.0, fcr=0.6)]),
        # single child
        (5, None, 1.0, 2.0, [spec(visits=1, wins=0.05)]),
    ]
    for h in hand:
        n += 1
        run_case(f"F1F2-{n:03d}", *h)

    # randomized configurations
    for _ in range(16):
        n += 1
        parent_visits = random.randint(0, 40)
        last = random.choice([None, None] + action_types)
        ew = random.choice([1.0, 1.0, 1.5])
        fw = random.choice([2.0, 2.0, 1.0])
        children = []
        for _c in range(random.randint(2, 5)):
            children.append(spec(
                visits=random.choice([0, 1, 1, 2, 3, 5, 8, 13]),
                wins=round(random.uniform(-0.5, 6.0), 4),
                lbr=round(random.uniform(0.0, 4.0), 4),
                fcr=round(random.uniform(0.0, 5.0), 4),
                hrr=round(random.uniform(0.0, 2.4), 4),
                novel=random.random() < 0.3,
                cf=random.choice([None, 0, 1, 2, 3, 4, 6]),
                at=random.choice(action_types + ["__NO_ACTION__", "__EMPTY_DICT__"]),
            ))
        run_case(f"F1F2-{n:03d}", parent_visits, last, ew, fw, children)


# ---------------------------------------------------------------------------
# F5-F8 — FA_MCTS.calculate_failure_aware_reward (fa_mcts.py:3156-3360)
# ---------------------------------------------------------------------------

def build_reward_state(sd):
    st = SimpleNamespace()
    st.coverage = sd["coverage"]
    st.compilation_errors = list(sd["compilationErrors"])
    if sd.get("metadataActionType") is not None:
        st.metadata = {"action": {"type": sd["metadataActionType"]}}
    else:
        st.metadata = {}
    st.previous_compilation_errors = list(sd["previousCompilationErrors"])
    if sd.get("stagnantSet", False):
        st.stagnant_coverage_iterations = sd["stagnantCoverageIterations"]
    st.detected_bugs = [dict(b) for b in sd["detectedBugs"]]
    st.business_logic_analysis = {"potential_bugs": [dict(i) for i in sd["businessIssues"]]}
    st.has_bugs = sd["hasBugs"]
    lb = [{"bug_type": t} for t in sd["logicalBugTypes"]]
    st.logical_bugs = lb
    st.count_logical_bugs = (lambda bugs: (lambda: len(bugs)))(lb)
    if sd.get("coveredFailures") is not None:
        st.covered_failures = set(sd["coveredFailures"])
    if sd.get("coveredBranchConditions") is not None:
        st.covered_branch_conditions = set(sd["coveredBranchConditions"])
    st.has_boundary_tests = sd["hasBoundaryTests"]
    st.has_boolean_bug_tests = sd["hasBooleanBugTests"]
    st.has_state_transition_tests = sd["hasStateTransitionTests"]
    st.has_exception_path_tests = sd["hasExceptionPathTests"]
    return st


def build_reward_parent(pd):
    if pd is None:
        return None
    p = SimpleNamespace()
    if pd.get("hasCoverage", True):
        p.coverage = pd["coverage"]
    if pd.get("coveredFailures") is not None:
        p.covered_failures = set(pd["coveredFailures"])
    if pd.get("coveredBranchConditions") is not None:
        p.covered_branch_conditions = set(pd["coveredBranchConditions"])
    return p


def run_f5f8_case(case_id, inputs):
    fa = object.__new__(FA_MCTS)
    fa.focus_on_bugs = inputs["focusOnBugs"]
    fa.failures = [
        {"type": f["type"], "location": f["location"], "risk_level": f["riskLevel"]}
        for f in inputs["failures"]
    ]
    if inputs["fModelPresent"]:
        fa.f_model = SimpleNamespace(
            boundary_conditions=[{"method": f"m{i}", "line": i}
                                 for i in range(inputs["boundaryConditionCount"])])
    else:
        fa.f_model = None

    state = build_reward_state(inputs["state"])
    parent = build_reward_parent(inputs["parent"])
    reward = fa.calculate_failure_aware_reward(state, parent)
    add_case("F5F8_reward", case_id, inputs, {
        "reward": fnum(reward),
        "stagnantAfter": getattr(state, "stagnant_coverage_iterations", None),
    })


def gen_f5f8():
    n = 0

    def sd(coverage=50.0, errors=(), action=None, prev=(), stagnant=None, bugs=(),
           issues=(), has_bugs=False, lb_types=(), cf=(), cb=(), flags=(0, 0, 0, 0)):
        d = {
            "coverage": coverage,
            "compilationErrors": list(errors),
            "metadataActionType": action,
            "previousCompilationErrors": list(prev),
            "stagnantSet": stagnant is not None,
            "stagnantCoverageIterations": stagnant if stagnant is not None else 0,
            "detectedBugs": list(bugs),
            "businessIssues": list(issues),
            "hasBugs": has_bugs,
            "logicalBugTypes": list(lb_types),
            "coveredFailures": list(cf) if cf is not None else None,
            "coveredBranchConditions": list(cb) if cb is not None else None,
            "hasBoundaryTests": bool(flags[0]),
            "hasBooleanBugTests": bool(flags[1]),
            "hasStateTransitionTests": bool(flags[2]),
            "hasExceptionPathTests": bool(flags[3]),
        }
        return d

    def pf(coverage=40.0, has_coverage=True, cf=(), cb=()):
        return {
            "hasCoverage": has_coverage,
            "coverage": coverage,
            "coveredFailures": list(cf) if cf is not None else None,
            "coveredBranchConditions": list(cb) if cb is not None else None,
        }

    failures_basic = [
        {"type": "overflow", "location": 12, "riskLevel": "high"},
        {"type": "off_by_one", "location": 5, "riskLevel": "high"},
        {"type": "null_handling", "location": 30, "riskLevel": "medium"},
        {"type": "boolean_bug", "location": 44, "riskLevel": "low"},
    ]

    bug_match = {"test_method": "testCalculateTotal", "error": "expected: <5> but was: <6>",
                 "description": "total mismatch in discount calculation"}
    issue_match = {"method": "calculateTotal", "description": "discount calculation produces wrong total value",
                   "confidence": 0.8}
    issue_noconf = {"method": "calculateTotal", "description": "discount calculation produces wrong total value"}
    issue_nomatch = {"method": "unrelatedMethod", "description": "completely different problem domain here"}

    hand = [
        # F5: fix action success -> 2.0
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(action="fix_compilation_errors", prev=["e1"], errors=[]), parent=None),
        # F5: fix action failed -> 0.1
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(action="fix_compilation_errors", prev=["e1"], errors=["e2"]), parent=None),
        # F5: fix action, no errors before/after -> FALLTHROUGH to normal computation
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=62.5, action="fix_compilation_errors", cf=["overflow_12"], cb=["m0_0"]),
             parent=None),
        # F5: plain state with compile errors -> 0.05
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(errors=["boom"]), parent=None),
        # coverage improvement (delta > 0)
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=55.0, stagnant=2), parent=pf(coverage=48.5)),
        # stagnation: delta <= 0 increments; > 3 strict -> bonus
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, stagnant=3), parent=pf(coverage=40.0)),
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, stagnant=5), parent=pf(coverage=41.0)),
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, stagnant=9), parent=pf(coverage=40.0)),  # bonus cap 0.5
        # stagnant but NEW PATTERN discovery resets counter -> bonus zeroed (subtle)
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, stagnant=5, cf=["overflow_12", "null_handling_30"]),
             parent=pf(coverage=40.0, cf=["null_handling_30"])),
        # new high-risk pattern: type WITHOUT underscore matches ("overflow")
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, cf=["overflow_12"]), parent=pf(coverage=40.0, cf=[])),
        # new high-risk pattern quirk: "off_by_one_5".split('_')[0] == "off" -> NOT high risk
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, cf=["off_by_one_5"]), parent=pf(coverage=40.0, cf=[])),
        # new patterns but parent lacks covered_failures attr -> newly_covered stays []
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, cf=["overflow_12", "off_by_one_5"]),
             parent=pf(coverage=39.0, cf=None)),
        # bugs: base 0.5 only (no logical)
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(bugs=[{"test_method": "testX", "error": "boom", "description": "d"}]),
             parent=None),
        # bugs + logical bugs incl. tier1/tier2 types
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(bugs=[bug_match], has_bugs=True,
                      lb_types=["boundary_error", "resource_leak", "incorrect_value"]),
             parent=None),
        # business-logic issue match (confidence present)
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(bugs=[bug_match], issues=[issue_nomatch, issue_match]), parent=None),
        # business-logic issue match with MISSING confidence -> 0.5 default
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(bugs=[bug_match], issues=[issue_noconf]), parent=None),
        # two bugs each matching -> two accruals; break after first issue per bug
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(bugs=[bug_match, dict(bug_match)], issues=[issue_match, issue_noconf]),
             parent=None),
        # branch rewards: ratio + new branches
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=8,
             state=sd(cb=["m0_0", "m1_1", "m2_2"]), parent=pf(cb=["m0_0"])),
        # f_model None -> branch block skipped even with covered set
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=False, boundaryConditionCount=0,
             state=sd(cb=["m0_0", "m1_1"]), parent=None),
        # empty failures list -> pattern pct term skipped (truthiness N13)
        dict(focusOnBugs=True, failures=[], fModelPresent=True, boundaryConditionCount=4,
             state=sd(cf=["overflow_12"]), parent=pf(cf=[])),
        # boundary_conditions empty -> ratio skipped, new-branch reward still applies
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=0,
             state=sd(cb=["m0_0"]), parent=pf(cb=[])),
        # quality flags all set
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(flags=(1, 1, 1, 1)), parent=None),
        # focus_on_bugs=False variants
        dict(focusOnBugs=False, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=70.0, bugs=[bug_match], issues=[issue_match], has_bugs=True,
                      lb_types=["boolean_bug"], flags=(1, 0, 1, 0)),
             parent=pf(coverage=60.0)),
        dict(focusOnBugs=False, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=40.0, stagnant=6), parent=pf(coverage=40.0)),
        # parent present but NO coverage attr; still supplies covered sets
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(coverage=50.0, stagnant=7, cf=["overflow_12", "null_handling_30"]),
             parent=pf(has_coverage=False, cf=["overflow_12"])),
        # state lacks covered_failures attr entirely
        dict(focusOnBugs=True, failures=failures_basic, fModelPresent=True, boundaryConditionCount=4,
             state=sd(cf=None), parent=None),
    ]
    for h in hand:
        n += 1
        run_f5f8_case(f"F5F8-{n:03d}", h)

    # randomized grid
    all_pattern_ids = [f"{f['type']}_{f['location']}" for f in failures_basic] + ["extra_99"]
    all_cond_ids = [f"m{i}_{i}" for i in range(8)]
    lb_pool = ["boundary_error", "boolean_bug", "operator_logic", "resource_leak",
               "concurrency_issue", "state_corruption", "incorrect_value", "index_error", ""]
    for _ in range(34):
        n += 1
        has_parent = random.random() < 0.75
        cf_state = random.sample(all_pattern_ids, random.randint(0, 5)) if random.random() < 0.9 else None
        cb_state = random.sample(all_cond_ids, random.randint(0, 6)) if random.random() < 0.9 else None
        bugs = []
        issues = []
        if random.random() < 0.5:
            bugs.append(bug_match)
            if random.random() < 0.6:
                issues.append(issue_match)
            if random.random() < 0.3:
                issues.append(issue_noconf)
            if random.random() < 0.3:
                issues.append(issue_nomatch)
        has_bugs = bool(bugs) and random.random() < 0.7
        lb_types = random.sample(lb_pool, random.randint(1, 4)) if has_bugs else []
        state = sd(
            coverage=round(random.uniform(0.0, 100.0), 2),
            errors=["err"] if random.random() < 0.1 else [],
            action=random.choice([None, None, None, "fix_compilation_errors", "boundary_test"]),
            prev=["olderr"] if random.random() < 0.3 else [],
            stagnant=random.choice([None, 0, 1, 3, 4, 6, 9]),
            bugs=bugs, issues=issues, has_bugs=has_bugs, lb_types=lb_types,
            cf=cf_state, cb=cb_state,
            flags=tuple(int(random.random() < 0.5) for _ in range(4)),
        )
        parent = None
        if has_parent:
            parent = pf(
                coverage=round(random.uniform(0.0, 100.0), 2),
                has_coverage=random.random() < 0.9,
                cf=random.sample(all_pattern_ids, random.randint(0, 4)) if random.random() < 0.8 else None,
                cb=random.sample(all_cond_ids, random.randint(0, 5)) if random.random() < 0.8 else None,
            )
        run_f5f8_case(f"F5F8-{n:03d}", dict(
            focusOnBugs=random.random() < 0.7,
            failures=failures_basic if random.random() < 0.85 else [],
            fModelPresent=random.random() < 0.85,
            boundaryConditionCount=random.choice([0, 2, 4, 8]),
            state=state, parent=parent))


# ---------------------------------------------------------------------------
# F9 — FATestState.track_logic_scenario_coverage (test_state.py:387-546)
# ---------------------------------------------------------------------------

def run_f9_case(case_id, inputs):
    st = object.__new__(FATestState)
    st.test_code = inputs["testCode"]
    st.failures = [
        {"type": f["type"], "location": f["location"],
         **({"risk_level": f["riskLevel"]} if f["riskLevel"] is not None else {})}
        for f in inputs["failures"]
    ]
    st.covered_failures_scores = dict(inputs["initialScores"])
    st.covered_failures = set(inputs["initialCovered"])
    st.logical_bugs = [dict(b) for b in inputs["logicalBugs"]]
    st.test_methods = [dict(m) for m in inputs["testMethods"]]
    st.track_logic_scenario_coverage()
    add_case("F9_patternCoverage", case_id, inputs, {
        "covered": sorted(st.covered_failures),
        "scores": {k: fnum(v) for k, v in st.covered_failures_scores.items()},
        "scoreKeyOrder": list(st.covered_failures_scores.keys()),
    })


def gen_f9():
    n = 0

    def f(t, loc, risk="medium"):
        return {"type": t, "location": loc, "riskLevel": risk}

    hand = [
        # line-number match (0.7) + medium threshold 0.6 -> covered
        dict(testCode="// testing line 42 behavior", failures=[f("null_handling", 42)],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # Chinese line marker
        dict(testCode="// 检查 行 7 的边界", failures=[f("boundary_condition", 7, "high")],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # keyword matches capped at 0.5 (null_handling has 5 keywords)
        dict(testCode="assertnull(nullpointerexception) nullcheck null bounds",
             failures=[f("null_handling", 10)],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # high risk threshold 0.8: 0.7 line match alone NOT enough... plus keywords
        dict(testCode="check line 3", failures=[f("resource_leak", 3, "high")],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # unknown type -> fallback keywords [type, bug, test, error]
        dict(testCode="this test found a bug error in custom_thing",
             failures=[f("custom_thing", 9, "low")],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # bug evidence: pattern type appears in bug description
        dict(testCode="", failures=[f("integer_overflow", 21)],
             initialScores={}, initialCovered=[],
             logicalBugs=[{"description": "integer_overflow detected in add", "error": "", "bug_type": "numeric_overflow"}],
             testMethods=[]),
        # bug evidence via bug_type containing de-underscored pattern type
        dict(testCode="", failures=[f("boolean_bug", 15)],
             initialScores={}, initialCovered=[],
             logicalBugs=[{"description": "", "error": "", "bug_type": "boolean_bug_x"}],
             testMethods=[]),
        # bug evidence via keyword-in-bug (empty fallback for unknown type -> NO match)
        dict(testCode="", failures=[f("custom_thing", 9)],
             initialScores={}, initialCovered=[],
             logicalBugs=[{"description": "a bug error happened", "error": "", "bug_type": "unknown"}],
             testMethods=[]),
        # method-name evidence (+0.3)
        dict(testCode="", failures=[f("off_by_one", 33)],
             initialScores={}, initialCovered=[],
             logicalBugs=[], testMethods=[{"name": "testOffByOneAtEnd", "code": "..."}]),
        # decay: preloaded score 0.65 medium, no evidence -> pre-decay base kept (0.65 stays; decay transient)
        dict(testCode="", failures=[f("string_comparison", 4)],
             initialScores={"string_comparison_4": 0.65}, initialCovered=["string_comparison_4"],
             logicalBugs=[], testMethods=[]),
        # decay removes from covered mid-loop: 0.62*0.95 < 0.6 -> removed; no boost -> stays out
        dict(testCode="", failures=[f("string_comparison", 4)],
             initialScores={"string_comparison_4": 0.62}, initialCovered=["string_comparison_4"],
             logicalBugs=[], testMethods=[]),
        # decay + boost re-adds
        dict(testCode="string equals compare assertion", failures=[f("string_comparison", 4)],
             initialScores={"string_comparison_4": 0.62}, initialCovered=["string_comparison_4"],
             logicalBugs=[], testMethods=[]),
        # threshold equality: boost exactly 0.6 (keyword 0.2 + bug 0.4) >= 0.6 medium -> covered
        dict(testCode="operator precedence", failures=[f("operator_precedence", 8)],
             initialScores={}, initialCovered=[],
             logicalBugs=[{"description": "operator_precedence issue", "error": "", "bug_type": "x"}],
             testMethods=[]),
        # low risk 0.5: keyword 0.2 + method 0.3 = 0.5 -> covered exactly at threshold
        dict(testCode="copy paste", failures=[f("copy_paste", 2, "low")],
             initialScores={}, initialCovered=[],
             logicalBugs=[], testMethods=[{"name": "testCopyPaste", "code": ""}]),
        # critical risk -> unmapped -> default 0.6
        dict(testCode="overflow integer line 77", failures=[f("integer_overflow", 77, "critical")],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # missing risk_level -> medium default
        dict(testCode="bitwise logical", failures=[f("bitwise_logical_confusion", 66, None)],
             initialScores={}, initialCovered=[], logicalBugs=[], testMethods=[]),
        # duplicate pattern id in failures: second pass skips decay (updated_patterns)
        dict(testCode="resource leak close", failures=[f("resource_leak", 3), f("resource_leak", 3)],
             initialScores={"resource_leak_3": 0.5}, initialCovered=[],
             logicalBugs=[], testMethods=[]),
        # multiple patterns mixed
        dict(testCode="test line 5 with boundary edge case and null check plus overflow integer",
             failures=[f("off_by_one", 5, "high"), f("boundary_condition", 12),
                       f("null_handling", 30, "low"), f("integer_overflow", 40, "high")],
             initialScores={"null_handling_30": 0.3}, initialCovered=[],
             logicalBugs=[{"description": "boundary failure", "error": "off by one", "bug_type": "boundary_error"}],
             testMethods=[{"name": "testBoundaryCondition", "code": ""}]),
        # confidence cap at 1.0
        dict(testCode="line 11 null nullpointer nullpointerexception assertnull nullcheck",
             failures=[f("null_handling", 11)],
             initialScores={"null_handling_11": 0.9}, initialCovered=["null_handling_11"],
             logicalBugs=[{"description": "null_handling", "error": "", "bug_type": "null_reference"}],
             testMethods=[{"name": "testNullHandling", "code": ""}]),
        # empty test code (falsy) with method w/o name key
        dict(testCode="", failures=[f("boolean_bug", 1)],
             initialScores={}, initialCovered=[], logicalBugs=[],
             testMethods=[{"code": "no name key"}]),
    ]
    for h in hand:
        n += 1
        run_f9_case(f"F9-{n:03d}", h)


# ---------------------------------------------------------------------------
# D3 — TestStrategySelector.select_strategies (test_generation_strategies.py:706-842)
# ---------------------------------------------------------------------------

def run_d3_case(case_id, inputs):
    f_model = None
    if inputs["fModel"] is not None:
        f_model = SimpleNamespace(boundary_conditions=[
            {"method": c["method"], "line": c["line"], "type": c["type"]}
            for c in inputs["fModel"]["boundaryConditions"]
        ])
    failures = [{"type": f["type"], "location": f["location"]} for f in inputs["failures"]]
    sel = TestStrategySelector(failures=failures, f_model=f_model)

    state = None
    if inputs["state"] is not None:
        sdesc = inputs["state"]
        metadata = {}
        if sdesc["parentCoverage"] is not None:
            metadata["parent_coverage"] = sdesc["parentCoverage"]
        state = SimpleNamespace(
            coverage=sdesc["coverage"],
            metadata=metadata,
            detected_bugs=[{"type": b} for b in sdesc["detectedBugTypes"]],
        )

    covered_patterns = set(inputs["coveredPatterns"]) if inputs["coveredPatterns"] is not None else None
    covered_conditions = set(inputs["coveredConditions"]) if inputs["coveredConditions"] is not None else None
    issues = None
    if inputs["businessIssues"] is not None:
        issues = []
        for i in inputs["businessIssues"]:
            d = {"type": i["type"]}
            if i["confidence"] is not None:
                d["confidence"] = i["confidence"]
            issues.append(d)

    result = sel.select_strategies(state, covered_patterns, covered_conditions, issues)
    add_case("D3_selectStrategies", case_id, inputs, {
        "strategies": [{"id": s["id"], "name": s["name"], "weight": fnum(s["weight"])}
                       for s in result],
    })


def gen_d3():
    n = 0

    def f(t, loc=1):
        return {"type": t, "location": loc}

    def cond(m, line, t):
        return {"method": m, "line": line, "type": t}

    def st(coverage=50.0, parent=None, bugs=()):
        return {"coverage": coverage, "parentCoverage": parent, "detectedBugTypes": list(bugs)}

    def issue(t, conf):
        return {"type": t, "confidence": conf}

    base_conds = [cond("calc", 10, "if_condition"), cond("calc", 20, "while_loop"),
                  cond("loop", 30, "for_loop"), cond("misc", 40, "if_condition"),
                  cond("misc", 50, "switch_statement")]

    hand = [
        # pure base weights (everything None)
        dict(failures=[], fModel=None, state=None, coveredPatterns=None,
             coveredConditions=None, businessIssues=None),
        # coveredPatterns empty set -> block skipped (Python truthiness)
        dict(failures=[f("boundary_condition")], fModel=None, state=None,
             coveredPatterns=[], coveredConditions=None, businessIssues=None),
        # uncovered boundary + off_by_one -> boundary_testing boosts
        dict(failures=[f("boundary_condition", 1), f("off_by_one", 2), f("boolean_bug", 3),
                       f("operator_precedence", 4), f("null_handling", 5),
                       f("exception_handling", 6), f("resource_leak", 7),
                       f("state_corruption", 8), f("unmatched_type", 9)],
             fModel=None, state=None, coveredPatterns=["boolean_bug_3"],
             coveredConditions=None, businessIssues=None),
        # all covered -> no boosts
        dict(failures=[f("boundary_condition", 1), f("null_handling", 5)],
             fModel=None, state=None,
             coveredPatterns=["boundary_condition_1", "null_handling_5"],
             coveredConditions=None, businessIssues=None),
        # condition block: uncovered_if > uncovered_loops -> expression +0.3
        dict(failures=[], fModel={"boundaryConditions": base_conds}, state=None,
             coveredPatterns=None, coveredConditions=["calc_20", "loop_30"],
             businessIssues=None),
        # uncovered loops >= ifs -> boundary_testing +0.3
        dict(failures=[], fModel={"boundaryConditions": base_conds}, state=None,
             coveredPatterns=None, coveredConditions=["calc_10", "misc_40"],
             businessIssues=None),
        # all conditions covered -> counts 0/0 -> else branch STILL boosts boundary_testing
        dict(failures=[], fModel={"boundaryConditions": base_conds}, state=None,
             coveredPatterns=None,
             coveredConditions=["calc_10", "calc_20", "loop_30", "misc_40", "misc_50"],
             businessIssues=None),
        # business issues: every keyword route
        dict(failures=[], fModel=None, state=None, coveredPatterns=None, coveredConditions=None,
             businessIssues=[issue("boundary_check", 0.9), issue("logic_flaw", 0.7),
                             issue("null_deref", 0.6), issue("input_validation", 0.5),
                             issue("state_transition", 0.4), issue("resource_leak", 0.8),
                             issue("unrouted_kind", 0.5)]),
        # issue with missing confidence -> 0 default
        dict(failures=[], fModel=None, state=None, coveredPatterns=None, coveredConditions=None,
             businessIssues=[issue("boundary_index", None)]),
        # stagnation: |delta| < 0.1 -> business +0.4, state +0.3
        dict(failures=[], fModel=None, state=st(coverage=50.0, parent=50.05),
             coveredPatterns=None, coveredConditions=None, businessIssues=None),
        # stagnation + detected bug types (set-deduped) hitting all three routes
        dict(failures=[], fModel=None,
             state=st(coverage=50.0, parent=50.0,
                      bugs=["boundary_error", "logic_error", "resource_leak",
                            "boundary_error", "other"]),
             coveredPatterns=None, coveredConditions=None, businessIssues=None),
        # non-stagnant (delta >= 0.1) -> no boosts
        dict(failures=[], fModel=None, state=st(coverage=55.0, parent=50.0, bugs=["boundary_error"]),
             coveredPatterns=None, coveredConditions=None, businessIssues=None),
        # state present but no parent_coverage key -> skipped
        dict(failures=[], fModel=None, state=st(coverage=50.0, parent=None, bugs=["boundary_error"]),
             coveredPatterns=None, coveredConditions=None, businessIssues=None),
        # everything combined
        dict(failures=[f("boundary_condition", 1), f("null_handling", 2), f("state_corruption", 3)],
             fModel={"boundaryConditions": base_conds},
             state=st(coverage=42.0, parent=42.01, bugs=["logic_error"]),
             coveredPatterns=["null_handling_2"],
             coveredConditions=["calc_10"],
             businessIssues=[issue("condition_logic", 0.75)]),
    ]
    for h in hand:
        n += 1
        run_d3_case(f"D3-{n:03d}", h)

    # randomized
    pattern_types = ["boundary_condition", "off_by_one", "boolean_bug", "operator_precedence",
                     "null_handling", "exception_handling", "resource_leak", "state_corruption",
                     "integer_overflow", "concurrency"]
    issue_types = ["boundary_check", "index_bounds", "logic_flaw", "condition_error",
                   "null_pointer", "exception_flow", "validation_gap", "input_check",
                   "state_machine", "transition_bug", "resource_mgmt", "leak_risk", "misc"]
    bug_types = ["boundary_error", "logic_error", "resource_leak", "npe", ""]
    for _ in range(12):
        n += 1
        failures = [f(random.choice(pattern_types), i) for i in range(random.randint(0, 6))]
        ids = [f'{p["type"]}_{p["location"]}' for p in failures]
        conds = [cond(random.choice(["a", "b", "c"]), i,
                      random.choice(["if_condition", "while_loop", "for_loop", "other"]))
                 for i in range(random.randint(0, 6))]
        cond_ids = [f'{c["method"]}_{c["line"]}' for c in conds]
        has_state = random.random() < 0.7
        run_d3_case(f"D3-{n:03d}", dict(
            failures=failures,
            fModel={"boundaryConditions": conds} if random.random() < 0.8 else None,
            state=st(coverage=round(random.uniform(0, 100), 2),
                     parent=round(random.uniform(0, 100), 2) if random.random() < 0.7 else None,
                     bugs=[random.choice(bug_types) for _ in range(random.randint(0, 3))])
            if has_state else None,
            coveredPatterns=random.sample(ids, random.randint(0, len(ids))) if ids and random.random() < 0.8 else None,
            coveredConditions=random.sample(cond_ids, random.randint(0, len(cond_ids))) if cond_ids and random.random() < 0.8 else None,
            businessIssues=[issue(random.choice(issue_types),
                                  random.choice([None, 0.3, 0.5, 0.8, 1.0]))
                            for _ in range(random.randint(0, 3))] if random.random() < 0.7 else None,
        ))


# ---------------------------------------------------------------------------
# D11 — FATestState.classify_logical_bugs (test_state.py:313-384)
# ---------------------------------------------------------------------------

def run_d11_case(case_id, inputs):
    st = object.__new__(FATestState)
    st.detected_bugs = []
    for b in inputs["bugs"]:
        d = {}
        if b["testMethod"] is not None:
            d["test_method"] = b["testMethod"]
        if b["error"] is not None:
            d["error"] = b["error"]
        if b["description"] is not None:
            d["description"] = b["description"]
        st.detected_bugs.append(d)
    st.logical_bugs = []
    st.has_bugs = False
    st.classify_logical_bugs()
    out_bugs = []
    for d in st.detected_bugs:
        out_bugs.append({
            "bugCategory": d.get("bug_category"),
            "bugType": d.get("bug_type"),
            "logicConfidence": fnum(d["logic_confidence"]) if "logic_confidence" in d else None,
        })
    logical_indices = [st.detected_bugs.index(b) for b in st.logical_bugs]
    add_case("D11_classify", case_id, inputs, {
        "bugs": out_bugs,
        "logicalBugIndices": logical_indices,
        "hasBugs": st.has_bugs,
    })


def gen_d11():
    n = 0

    def bug(error, description, method=None):
        return {"testMethod": method, "error": error, "description": description}

    msgs = [
        # one per classifier row (+ mixed-confidence overlaps + near misses)
        ("expected: <5> but was: <6>", ""),                       # incorrect_value 0.7
        ("expected: true but was: false", ""),                    # 0.9 incorrect_boolean (also row1)
        ("expected FALSE but WAS TRUE", ""),                      # case-insensitive
        ("expected value empty", ""),                             # empty_null_handling 0.6
        ("expected result null here", ""),
        ("java.lang.IndexOutOfBoundsException: Index 4", ""),     # index_error 0.8
        ("ArrayIndexOutOfBoundsException at 3", ""),
        ("java.lang.NullPointerException", ""),                   # null_reference 0.6
        ("ClassCastException: A cannot be cast to B", ""),        # incorrect_type 0.7
        ("UnsupportedOperationException thrown", ""),             # 0.8
        ("IllegalArgumentException: bad input", ""),              # 0.7
        ("IllegalStateException: not ready", ""),                 # invalid_state 0.8 (also state_corruption row hits 'invalid.*?state')
        ("ConcurrentModificationException", ""),                  # 0.9 (also 'concurrent' row)
        ("NumberFormatException for x", ""),                      # 0.7
        ("integer overflow in add", ""),                          # numeric_overflow 0.8
        ("value underflow detected", ""),
        ("boundary condition failed", ""),                        # boundary_error 0.9
        ("classic fencepost error", ""),                          # fence.?post
        ("an off-by-one mistake", ""),                            # off.by.one
        ("operator precedence issue", ""),                        # operator_logic 0.8
        ("wrong condition logic", ""),
        ("race condition suspected", ""),                         # concurrency 0.9
        ("deadlock in pool", ""),
        ("boolean condition wrong", ""),                          # boolean_bug 0.8
        ("logic error in branch", ""),
        ("infinite loop detected", ""),                           # 0.9
        ("resource leak: stream not closed", ""),                 # 0.8 resource_leak
        ("state corruption after rollback", ""),                  # 0.8
        ("assertion failed logic check", ""),                     # logical_assertion 0.7 via 'assertion.*?fail.*?logic'
        ("completely unrelated failure", "no keywords here"),     # general
        ("expected:", "but was elsewhere"),                       # error+desc concatenation match
        ("EXPECTED: <A> BUT WAS: <B>", ""),                       # uppercase
        ("expecting 5 but were 6", ""),                           # near miss -> general
        ("IndexOutOfBounds", ""),                                 # near miss (no 'Exception') -> general
        ("expected:\r\nsomething but was", ""),                   # \n blocks dot in BOTH dialects -> general
        ("expected:\rsomething but was", ""),                     # Python dot matches \r (Java needs UNIX_LINES)
        ("expected: something but was", ""),                 # Python dot matches U+2028 (Java needs UNIX_LINES)
        ("boundary AND overflow AND deadlock", ""),               # multi-match: highest 0.9, earliest wins
    ]
    for error, desc in msgs:
        n += 1
        run_d11_case(f"D11-{n:03d}", {"bugs": [bug(error, desc, method=f"testM{n}")]})

    # dedup by test_method: second bug with same method is skipped (bug_category untouched)
    n += 1
    run_d11_case(f"D11-{n:03d}", {"bugs": [
        bug("boundary condition failed", "", method="testSame"),
        bug("NullPointerException", "", method="testSame"),
        bug("NullPointerException", "", method="testOther"),
    ]})
    # missing keys entirely
    n += 1
    run_d11_case(f"D11-{n:03d}", {"bugs": [bug(None, None, method=None)]})
    # non-logical then logical, empty method names collide ("" == "")
    n += 1
    run_d11_case(f"D11-{n:03d}", {"bugs": [
        bug("nothing to see", "", method=None),
        bug("deadlock", "", method=None),
    ]})


# ---------------------------------------------------------------------------
# D13 — FA_MCTS._bug_matches_predicted_issue (fa_mcts.py:3363-3404)
# ---------------------------------------------------------------------------

def run_d13_case(case_id, inputs):
    fa = object.__new__(FA_MCTS)
    bug = {}
    if inputs["bug"]["testMethod"] is not None:
        bug["test_method"] = inputs["bug"]["testMethod"]
    if inputs["bug"]["error"] is not None:
        bug["error"] = inputs["bug"]["error"]
    if inputs["bug"]["description"] is not None:
        bug["description"] = inputs["bug"]["description"]
    issue = {}
    if inputs["issue"]["method"] is not None:
        issue["method"] = inputs["issue"]["method"]
    if inputs["issue"]["description"] is not None:
        issue["description"] = inputs["issue"]["description"]
    result = fa._bug_matches_predicted_issue(bug, issue)
    add_case("D13_matcher", case_id, inputs, {"matches": bool(result)})


def gen_d13():
    n = 0

    def case(bm, be, bd, im, idesc):
        return {"bug": {"testMethod": bm, "error": be, "description": bd},
                "issue": {"method": im, "description": idesc}}

    hand = [
        # basic match: method overlap + enough keywords
        case("testCalculateTotal", "wrong discount total value", "", "calculateTotal",
             "discount calculation returns wrong total value"),
        # method containment in the other direction
        case("testCalc", "irrelevant", "", "calculateTotal", "some description keywords"),
        # 'test' prefix NOT stripped (doesn't start with 'test')
        case("checkCalculateTotal", "total discount wrong", "", "CalculateTotal",
             "total discount computation"),
        # empty bug method -> False
        case(None, "total", "", "calculateTotal", "total discount"),
        # empty issue method -> False
        case("testX", "total", "", None, "total discount"),
        # bug named exactly 'test' -> simplified "" is contained in everything -> overlap
        case("test", "wrong total discount value", "", "anything",
             "wrong total discount value"),
        # no keyword >= 4 chars in issue description -> False
        case("testFoo", "abc", "", "foo", "a bb ccc"),
        # 1 keyword -> threshold min(2, 0) = 0 -> ALWAYS matches once methods overlap
        case("testFoo", "no relation at all", "", "foo", "keyword"),
        # 2 keywords -> threshold 1
        case("testFoo", "alpha here", "", "foo", "alpha beta1"),
        case("testFoo", "nothing relevant", "", "foo", "alpha beta1"),
        # 4 keywords -> threshold 2
        case("testFoo", "alpha gamma", "", "foo", "alpha beta1 gamma delta"),
        case("testFoo", "alpha only", "", "foo", "alpha beta1 gamma delta"),
        # keywords found in DESCRIPTION part of bug text
        case("testFoo", "", "alpha gamma present", "foo", "alpha beta1 gamma delta"),
        # case-insensitive method + keyword matching
        case("testFOO", "ALPHA GAMMA", "", "foo", "alpha beta1 gamma delta"),
        # unicode keywords (\w Unicode-aware): 4-char CJK word
        case("test边界", "错误 边界条件 出现", "", "边界", "边界条件 检查 失败 处理"),
        # duplicate keywords dedup via set (repeated words count once)
        case("testFoo", "alpha", "", "foo", "alpha alpha alpha beta1 gamma delta"),
    ]
    for h in hand:
        n += 1
        run_d13_case(f"D13-{n:03d}", h)


# ---------------------------------------------------------------------------
# F15/D10 — verify_bug_with_llm (verify_bug_with_llm.py:19-223)
# ---------------------------------------------------------------------------

def run_f15_case(case_id, inputs):
    bug_info = {}
    for src_key, dst_key in [("type", "type"), ("error", "error"),
                             ("confidence", "confidence"), ("severity", "severity")]:
        if inputs["bugInfo"].get(src_key) is not None:
            bug_info[dst_key] = inputs["bugInfo"][src_key]

    scenario = inputs["scenario"]
    response = inputs["response"]

    def fake_anthropic(prompt):
        if scenario == "api_fail":
            raise RuntimeError("anthropic down")
        return response

    def fake_gpt(prompt):
        raise RuntimeError("gpt down")

    old_a, old_g = vb.call_anthropic_api, vb.call_gpt_api
    vb.call_anthropic_api = fake_anthropic
    vb.call_gpt_api = fake_gpt
    try:
        result = vb.verify_bug_with_llm(bug_info, inputs["testMethod"], inputs["sourceCode"], "Foo")
    finally:
        vb.call_anthropic_api = old_a
        vb.call_gpt_api = old_g

    reasoning = result.get("reasoning")
    # The only Python-vs-Java reasoning text divergence: Python distinguishes
    # "Verification failed - insufficient API response" (short response) from
    # "Unable to perform LLM verification" (API exception); the Java policy funnels
    # both through apiFailureDefault. Skip reasoning comparison for that case only.
    reasoning_check = reasoning != "Verification failed - insufficient API response"
    add_case("F15D10_verify", case_id, inputs, {
        "isRealBug": bool(result["is_real_bug"]),
        "confidence": fnum(result["confidence"]),
        "reasoning": reasoning,
        "reasoningCheck": reasoning_check,
    })


def gen_f15():
    n = 0

    def case(bug_info, test_method, source_code, scenario="normal", response=None):
        return {"bugInfo": bug_info, "testMethod": test_method, "sourceCode": source_code,
                "scenario": scenario, "response": response}

    long_pad = " padding to exceed fifty characters for the response length check."

    hand = [
        # insufficient input (empty test method)
        case({"type": "assertion_failure", "confidence": 0.8}, "", "class Foo {}"),
        case({"type": "assertion_failure", "confidence": 0.6}, "", "class Foo {}"),
        # D10 pre-filter 1: null/empty assertion false positive
        case({"type": "assertion_failure", "error": "expected: <null> but was: <5>", "confidence": 0.5},
             "void testNullCase() {}", "class Foo {}"),
        case({"type": "assertion_failure", "error": "expected: <[]> but was: <[1]>", "confidence": 0.5},
             "void testEmptyList() {}", "class Foo {}"),
        # pre-filter 1 miss: trivial expectation but method name lacks null/empty -> falls to LLM
        case({"type": "assertion_failure", "error": "expected: <null> but was: <5>", "confidence": 0.5},
             "void testSomething() {}", "class Foo {}",
             response="VERDICT: FALSE POSITIVE\nCONFIDENCE: 6\nREASONING: test env issue." + long_pad),
        # D10 pre-filter 2: memory errors
        case({"type": "memory_error", "error": "boom", "confidence": 0.2},
             "void testX() {}", "class Foo {}"),
        case({"type": "other", "error": "java.lang.OutOfMemoryError: heap", "confidence": 0.2},
             "void testX() {}", "class Foo {}"),
        case({"type": "other", "error": "StackOverflowError deep recursion", "confidence": 0.2},
             "void testX() {}", "class Foo {}"),
        # D10 pre-filter 3: confidence > 0.9 skip
        case({"type": "other", "error": "meh", "confidence": 0.95}, "void t() {}", "class Foo {}"),
        # boundary: exactly 0.9 does NOT skip
        case({"type": "other", "error": "meh", "confidence": 0.9}, "void t() {}", "class Foo {}",
             response="VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: definite defect." + long_pad),
        # API failure -> default threshold conf > 0.7
        case({"type": "other", "error": "meh", "confidence": 0.75}, "void t() {}", "class Foo {}",
             scenario="api_fail"),
        case({"type": "other", "error": "meh", "confidence": 0.7}, "void t() {}", "class Foo {}",
             scenario="api_fail"),
        # missing confidence -> default 0.5
        case({"type": "other", "error": "meh"}, "void t() {}", "class Foo {}", scenario="api_fail"),
        # short response -> insufficient -> default
        case({"type": "other", "error": "meh", "confidence": 0.8}, "void t() {}", "class Foo {}",
             response="too short"),
        # structured: REAL BUG conf 7 -> 0.7
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="VERDICT: REAL BUG\nCONFIDENCE: 7\nREASONING: because reasons." + long_pad),
        # structured: quoted verdict + fractional confidence 8.5 -> 0.85
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response='VERDICT: "FALSE POSITIVE"\nCONFIDENCE: 8.5\nREASONING: expectation wrong.' + long_pad),
        # structured: confidence 10 -> capped 0.95; lowercase verdict via IGNORECASE
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="verdict: real bug\nCONFIDENCE: 10\nREASONING: overflow proven." + long_pad),
        # structured: confidence 15 -> capped 0.95
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="VERDICT: REAL BUG\nCONFIDENCE: 15\nREASONING: extra sure." + long_pad),
        # structured: missing CONFIDENCE -> 0.7 default; REASONING ends at \Z with trailing newline (X1)
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="VERDICT: REAL BUG\nREASONING: trailing newline test." + long_pad + "\n"),
        # structured: REASONING before VERDICT (lookahead stops capture)
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="REASONING: the code divides by zero here.\nVERDICT: REAL BUG\nCONFIDENCE: 9" + long_pad),
        # unstructured: explicit real-bug statement
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="After analysis, this is a real bug in the accumulator logic." + long_pad),
        # unstructured quirk: 'not a real bug' contains 'real bug' -> classified REAL (verbatim)
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="In my assessment this is not a real bug at all." + long_pad),
        # unstructured: 'false positive' phrasing without 'real bug'
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="This looks like a false positive caused by the harness." + long_pad),
        # signal counting: 3 pos vs 1 neg -> 0.6 + 0.05*2 = 0.7
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="There is a code defect: an actual bug and a vulnerability, "
                      "though partly expected behavior." + long_pad),
        # signal counting tie -> FALSE POSITIVE at 0.6
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="A code defect exists but this is expected behavior in context." + long_pad),
        # signal counting: many negatives, cap at 0.3 -> 0.9
        case({"type": "other", "error": "e", "confidence": 0.5}, "void t() {}", "class Foo {}",
             response="Not a bug: expected behavior, by design, unreasonable test, "
                      "test method issue, test environment problem, not realistic, "
                      "documented limitation, unreasonable expectation." + long_pad),
    ]
    for h in hand:
        n += 1
        run_f15_case(f"F15D10-{n:03d}", h)


# ---------------------------------------------------------------------------

def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "fixtures.json"
    gen_f3f4()
    gen_f1f2()
    gen_f5f8()
    gen_f9()
    gen_d3()
    gen_d11()
    gen_d13()
    gen_f15()

    by_formula = {}
    for c in CASES:
        by_formula[c["formula"]] = by_formula.get(c["formula"], 0) + 1

    doc = {
        "meta": {
            "generator": "java/failmapper-search/src/test/python/gen_fixtures.py (see file header for regen)",
            "pythonBaseline": "d2baa9e",
            "totalCases": len(CASES),
            "byFormula": by_formula,
        },
        "cases": CASES,
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
    print(json.dumps(by_formula, indent=2))
    print(f"total={len(CASES)} -> {out_path}")


if __name__ == "__main__":
    main()
