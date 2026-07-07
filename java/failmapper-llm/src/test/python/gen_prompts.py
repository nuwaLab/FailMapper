#!/usr/bin/env python3
"""
Layer-P differential prompt-fixture generator (JAVA_PORT_CONTRACT.md section 5 +
section 3.6 prompt register; milestone M4).

Renders the search-loop-critical prompt templates by calling the REAL Python
functions from the FailMapper baseline and records
{templateId, caseId, inputs, renderedPrompt} to prompts.json (ensure_ascii=False,
exact bytes). The Java side (LayerPDifferentialTest in failmapper-llm) re-renders
each case with the ported builders and asserts EXACT string equality.

Templates covered (contract 3.6 ids):
  P2  fa_mcts.FA_MCTS.create_logic_aware_action_prompt      (fa_mcts.py:2864-3027)
  P1  fa_mcts.FA_MCTS._create_business_logic_test_prompt    (fa_mcts.py:2793-2862)
  P12 feedback.generate_initial_test wrapper                (feedback.py:520-554)
  P10 verify_bug_with_llm.verify_bug_with_llm single prompt (verify_bug_with_llm.py:83-123)
  P11 verify_bug_with_llm.filter_verified_bug_methods batch (verify_bug_with_llm.py:328-371)
  P3  fa_mcts.FA_MCTS.fix_integrated_test_with_llm          (fa_mcts.py:4218-4268)
  P7  enhanced_mcts_test_generator.select_final_best_test merge prompt (:1836-1892)

Oracle-access technique: prompt-owning classes are instantiated with
object.__new__(Class), setting ONLY the attributes each prompt method reads.
Functions that immediately send the prompt to an LLM are captured by patching the
module-level call_anthropic_api reference with a recorder that returns a canned,
parseable response (long enough to pass the length guards); instance-level
patches (fix_test_with_llm, merge_all_valuable_tests, verify_test_compilation)
short-circuit the compile machinery around the P7 merge prompt.

REGISTERED DEVIATION I7 (contract section 4): P3 embeds the compilation_errors
LIST via f-string -> Python list repr. Cases with a truthy error list record
i7Transform=true plus pythonErrorRepr (the exact repr segment); the Java test
replaces that segment with the registered numbered-list rendering before
comparing. Everything outside the segment is still byte-compared.

Regen:
    cd /Users/ruiqidong/Desktop/FailMapper
    python3 java/failmapper-llm/src/test/python/gen_prompts.py \
        java/failmapper-llm/src/test/resources/layerp/prompts.json
"""

import json
import logging
import os
import sys
import tempfile
from types import SimpleNamespace

sys.path.insert(0, "/Users/ruiqidong/Desktop/FailMapper")

logging.disable(logging.CRITICAL)

import fa_mcts  # noqa: E402
import feedback  # noqa: E402
import verify_bug_with_llm as vb  # noqa: E402
import enhanced_mcts_test_generator as etg  # noqa: E402

CASES = []


def add_case(template_id, case_id, inputs, prompt):
    assert isinstance(prompt, str) and prompt, f"{case_id}: empty prompt"
    CASES.append({
        "templateId": template_id,
        "caseId": case_id,
        "inputs": inputs,
        "renderedPrompt": prompt,
    })


class Capture:
    """Recorder standing in for call_anthropic_api."""

    def __init__(self, response):
        self.response = response
        self.prompts = []

    def __call__(self, prompt, *args, **kwargs):
        self.prompts.append(prompt)
        return self.response


# ---------------------------------------------------------------------------
# Shared fixture material
# ---------------------------------------------------------------------------

SOURCE_CODE = (
    "package com.example.bank;\n\n"
    "public class Account {\n"
    "    private long balanceCents;\n\n"
    "    public void deposit(long amountCents) {\n"
    "        if (amountCents <= 0) {\n"
    "            throw new IllegalArgumentException(\"amount must be positive\");\n"
    "        }\n"
    "        balanceCents += amountCents;\n"
    "    }\n\n"
    "    public boolean withdraw(long amountCents) {\n"
    "        if (amountCents > balanceCents) {  // boundary: >= vs >\n"
    "            return false;\n"
    "        }\n"
    "        balanceCents -= amountCents;\n"
    "        return true;\n"
    "    }\n"
    "}\n"
)

TEST_CODE = (
    "package com.example.bank;\n\n"
    "import org.junit.jupiter.api.Test;\n"
    "import static org.junit.jupiter.api.Assertions.*;\n\n"
    "public class AccountTest {\n"
    "    @Test\n"
    "    public void testDeposit() {\n"
    "        Account a = new Account();\n"
    "        a.deposit(100);\n"
    "        assertTrue(a.withdraw(100));\n"
    "    }\n"
    "}\n"
)

# Dependency section WITH the GUIDELINES separator (branch 1 of the extractor).
TEST_PROMPT_WITH_GUIDE = (
    "1. CLASS OVERVIEW\nAccount: bank account with cent precision.\n"
    "-----------\n"
    "4. DEPENDENCY API REFERENCES\n"
    "- com.example.bank.Ledger#record(long): void\n"
    "- com.example.bank.Audit#log(String): void\n"
    "\n-----------\n5. GUIDELINES\n"
    "- never mock dependencies\n"
    "- prefer real Ledger instances\n"
)

# Dependency section WITHOUT the separator (branch 2: rest-of-content, stripped).
TEST_PROMPT_NO_GUIDE = (
    "intro text\n"
    "4. DEPENDENCY API REFERENCES\n"
    "- com.example.bank.Ledger#record(long): void\n\n"
)

# Prompt with no dependency section at all -> dep ctx == "".
TEST_PROMPT_NO_DEP = "1. CLASS OVERVIEW\nAccount only.\n"


def new_mcts(class_name="Account", source_code=SOURCE_CODE, test_prompt=None):
    m = object.__new__(fa_mcts.FA_MCTS)
    m.class_name = class_name
    m.source_code = source_code
    if test_prompt is not None:
        m.test_prompt = test_prompt
    return m


def state_of(coverage, test_code=TEST_CODE, business=None):
    s = SimpleNamespace(coverage=coverage, test_code=test_code)
    if business is not None:
        s.business_logic_analysis = business
    return s


# ---------------------------------------------------------------------------
# P2 — create_logic_aware_action_prompt
# ---------------------------------------------------------------------------

def p2(case_id, coverage, action, test_prompt=None, test_code=TEST_CODE,
       class_name="Account", source_code=SOURCE_CODE):
    m = new_mcts(class_name, source_code, test_prompt)
    prompt = m.create_logic_aware_action_prompt(state_of(coverage, test_code), action)
    add_case("P2", case_id, {
        "className": class_name,
        "sourceCode": source_code,
        "testPrompt": test_prompt,
        "coverage": coverage,
        "testCode": test_code,
        "action": action,
    }, prompt)


p2("P2-001", 83.125,
   {"type": "boundary_test", "condition": "amountCents > balanceCents", "line": 14},
   test_prompt=TEST_PROMPT_WITH_GUIDE)
p2("P2-002", 45.5,
   {"type": "expression_test", "operation": "amountCents <= 0 && balanceCents >= 0", "line": 7})
p2("P2-003", 0.0, {"type": "exception_test"}, test_prompt=TEST_PROMPT_NO_DEP)
p2("P2-004", 62.375,
   {"type": "target_line", "line": 17, "content": "balanceCents -= amountCents;"},
   test_prompt=TEST_PROMPT_NO_GUIDE)
p2("P2-005", 99.995,
   {"type": "business_logic_test", "issue_type": "off_by_one",
    "method": "withdraw", "description": "boundary uses > instead of >="})
p2("P2-006", 12.345, {"type": "general_exploration"})
p2("P2-007", 88.885, {"type": "bug_pattern_test", "pattern": "boundary_condition"})
p2("P2-008", 10.005,
   {"type": "fix_compilation_errors", "errors": [
       "cannot find symbol: variable ledger",
       "cannot find symbol: method depositAll(long)",
       "cannot find symbol: class Ledger",
       "incompatible types: String cannot be converted to long",
       "';' expected",
       "some totally novel error with no suggestion",
   ]},
   test_prompt=TEST_PROMPT_WITH_GUIDE)
p2("P2-009", 55.555,
   {"type": "fix_compilation_errors", "errors": [
       f"error {i}: unreachable statement" if i % 2 == 0
       else f"error {i}: missing return statement"
       for i in range(1, 13)
   ]})
p2("P2-010", 33.335, {"type": "fix_compilation_errors", "errors": []})
p2("P2-011", 20.0, {})  # no type -> "fallback": base + final reminder only
p2("P2-012", 75.125, {"type": "boundary_test"})  # missing keys -> N/A defaults

# ---------------------------------------------------------------------------
# P1 — _create_business_logic_test_prompt
# ---------------------------------------------------------------------------

def p1(case_id, coverage, action, business, test_prompt=None):
    m = new_mcts(test_prompt=test_prompt)
    prompt = m._create_business_logic_test_prompt(
        state_of(coverage, business=business), action)
    add_case("P1", case_id, {
        "className": "Account",
        "sourceCode": SOURCE_CODE,
        "testPrompt": test_prompt,
        "coverage": coverage,
        "testCode": TEST_CODE,
        "potentialBugs": None if business is None else business.get("potential_bugs", []),
        "action": action,
    }, prompt)


FULL_ISSUE = {
    "method": "withdraw",
    "type": "off_by_one",
    "description": "withdraw allows exact balance",
    "semantic_signals": {
        "expected_behavior": "withdraw succeeds when amount == balance",
        "actual_behavior": "withdraw fails when amount == balance",
    },
    "test_strategy": "test amounts balance-1, balance, balance+1",
}

p1("P1-001", 62.375,
   {"type": "business_logic_test", "issue_type": "off_by_one", "method": "withdraw",
    "description": "boundary uses > instead of >="},
   {"potential_bugs": [
       {"method": "deposit", "type": "off_by_one", "description": "decoy"},
       FULL_ISSUE,
   ]},
   test_prompt=TEST_PROMPT_WITH_GUIDE)
p1("P1-002", 0.005,
   {"type": "business_logic_test", "issue_type": "null_handling", "method": "deposit",
    "description": "no null check"},
   None)  # state has NO business_logic_analysis -> all defaults
p1("P1-003", 100.0,
   {"type": "business_logic_test", "issue_type": "state_error", "method": "withdraw",
    "description": "state left dirty"},
   {"potential_bugs": [
       {"method": "withdraw", "type": "state_error", "description": "matched, but bare"},
   ]})

# ---------------------------------------------------------------------------
# P12 — generate_initial_test wrapper
# ---------------------------------------------------------------------------

def p12(case_id, prompt_content):
    cap = Capture("```java\npublic class AccountTest {}\n```")
    saved = feedback.call_anthropic_api
    feedback.call_anthropic_api = cap
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False,
                                         encoding="utf-8") as f:
            f.write(prompt_content)
            path = f.name
        feedback.generate_initial_test(path, SOURCE_CODE)
        os.unlink(path)
    finally:
        feedback.call_anthropic_api = saved
    assert len(cap.prompts) == 1
    add_case("P12", case_id, {"promptContent": prompt_content}, cap.prompts[0])


p12("P12-001",
    "TEST GENERATION PROMPT for Account\n"
    "1. CLASS OVERVIEW\n...\n"
    "4. DEPENDENCY API REFERENCES\n- Ledger#record(long)\n"
    "\n-----------\n5. GUIDELINES\n- 保持测试独立\n")
p12("P12-002", "minimal prompt without trailing newline")

# ---------------------------------------------------------------------------
# P10 — single-bug verification prompt
# ---------------------------------------------------------------------------

def p10(case_id, bug_type, severity, error_message, test_method,
        class_name="Account", source_code=SOURCE_CODE):
    cap = Capture("VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: " + "x" * 80)
    saved = vb.call_anthropic_api
    vb.call_anthropic_api = cap
    try:
        vb.verify_bug_with_llm(
            {"type": bug_type, "error": error_message, "severity": severity,
             "confidence": 0.5},
            test_method, source_code, class_name)
    finally:
        vb.call_anthropic_api = saved
    assert len(cap.prompts) == 1
    add_case("P10", case_id, {
        "className": class_name,
        "sourceCode": source_code,
        "testMethod": test_method,
        "bugType": bug_type,
        "severity": severity,
        "errorMessage": error_message,
    }, cap.prompts[0])


p10("P10-001", "logical_bug", "high",
    "org.opentest4j.AssertionFailedError: withdraw(balance) should succeed",
    "@Test\npublic void testWithdrawExactBalance() {\n"
    "    Account a = new Account();\n    a.deposit(500);\n"
    "    assertTrue(a.withdraw(500));\n}")
p10("P10-002", "boundary_error", "medium",
    "边界条件失败: expected <true> — got <false> & more",
    "@Test\npublic void testBoundary() { /* 中文注释 */ }")

# ---------------------------------------------------------------------------
# P11 — batch verification prompt
# ---------------------------------------------------------------------------

def p11(case_id, methods, source_code, package_name="com.example.bank",
        class_name="Account"):
    cap = Capture(("Method 1: Yes. Reasoning ok. Confidence: 8\n" * 4)
                  + "REAL_BUGS: 1\n")
    saved = vb.call_anthropic_api
    vb.call_anthropic_api = cap
    try:
        vb.filter_verified_bug_methods(
            [dict(m) if isinstance(m, dict) else m for m in methods],
            source_code, class_name, package_name)
    finally:
        vb.call_anthropic_api = saved
    assert len(cap.prompts) == 1, f"{case_id}: expected one batch"
    batch = []
    for m in methods:
        if isinstance(m, dict) and "code" in m:
            batch.append({
                "code": m["code"],
                "bugTypes": [b.get("type", "Unknown") for b in m.get("bug_info", [])],
                "isRaw": False,
            })
        else:
            batch.append({"code": str(m), "bugTypes": [], "isRaw": True})
    add_case("P11", case_id, {
        "packageName": package_name,
        "className": class_name,
        "sourceCode": source_code,
        "batch": batch,
    }, cap.prompts[0])


METHOD_A = ("@Test\npublic void testWithdrawBoundaryBug() {\n"
            "    Account a = new Account();\n    a.deposit(100);\n"
            "    assertTrue(a.withdraw(100));\n}")
METHOD_B = ("@Test\npublic void testDepositZeroThrows() {\n"
            "    assertThrows(IllegalArgumentException.class,\n"
            "        () -> new Account().deposit(0));\n}")
METHOD_C = "@Test\npublic void testRawString() { new Account().deposit(1); }"

p11("P11-001",
    [{"code": METHOD_A,
      "bug_info": [{"type": "boundary_error"}, {"type": "logical_bug"}]}],
    SOURCE_CODE)
p11("P11-002",
    [{"code": METHOD_A,
      "bug_info": [{"type": "boundary_error"}, {"type": "logical_bug"},
                   {"type": "off_by_one"}, {"type": "state_error"},
                   {}]},  # 5 entries (one typeless -> "Unknown") -> ", and 2 more"
     {"code": METHOD_B, "bug_info": []},  # -> "Unknown issue"
     METHOD_C],  # raw non-dict branch
    SOURCE_CODE)

# Truncation edge cases: source EXACTLY 2500 chars, and 2600 chars (cut at 2500).
_base = "public class Filler {\n" + ("    // filler content line for truncation test\n" * 50)
SRC_2500 = (_base + "x" * 2500)[:2500]
assert len(SRC_2500) == 2500
SRC_2600 = SRC_2500 + "z" * 100
p11("P11-003", [{"code": METHOD_B, "bug_info": [{"type": "validation_gap"}]}],
    SRC_2500)
p11("P11-004", [{"code": METHOD_B, "bug_info": [{"type": "validation_gap"}]}],
    SRC_2600)

# ---------------------------------------------------------------------------
# P3 — fix_integrated_test_with_llm (REGISTERED I7 DEVIATION)
# ---------------------------------------------------------------------------

def p3(case_id, error_message, class_name="Account",
       package_name="com.example.bank", source_code=SOURCE_CODE,
       test_code=TEST_CODE):
    m = new_mcts(class_name, source_code)
    m.package_name = package_name
    cap = Capture("```java\n" + "public class AccountTest {}\n" * 8 + "```")
    saved = fa_mcts.call_anthropic_api
    fa_mcts.call_anthropic_api = cap
    try:
        m.fix_integrated_test_with_llm(test_code, error_message)
    finally:
        fa_mcts.call_anthropic_api = saved
    assert len(cap.prompts) == 1
    truthy = bool(error_message)
    add_case("P3", case_id, {
        "className": class_name,
        "packageName": package_name,
        "sourceCode": source_code,
        "testCode": test_code,
        "errorMessages": error_message,
        # I7: exact segment the Python f-string produced, so the Java test can
        # apply the registered transform (replace repr with numbered list).
        "i7Transform": truthy,
        "pythonErrorRepr": str(error_message) if truthy
        else "compilation failed, please check possible issues",
    }, cap.prompts[0])


p3("P3-001", ["cannot find symbol: variable ledger",
              "duplicate method testDeposit()"])
p3("P3-002", None)
p3("P3-003", [])  # empty list is falsy -> same fallback text as None
p3("P3-004", ["unexpected token: '}' at line 42",
              'string literal "unclosed at end',
              "错误: 缺少分号"])  # quotes + non-ASCII inside the repr segment

# ---------------------------------------------------------------------------
# P7 — merge ("special") prompt from select_final_best_test
# ---------------------------------------------------------------------------

def p7(case_id, base_test, verified_bug_methods):
    gen = object.__new__(etg.EnhancedMCTSTestGenerator)
    gen.class_name = "Account"
    gen.package_name = "com.example.bank"
    gen.project_dir = "/nonexistent/project"
    gen.best_coverage_test = base_test
    gen.best_coverage = 77.5
    gen.verified_bug_methods = verified_bug_methods
    gen.merge_all_valuable_tests = lambda *a, **k: "MERGED-CANDIDATE"
    gen.verify_test_compilation = lambda *a, **k: (False, "does not compile")
    captured = []
    gen.fix_test_with_llm = (
        lambda test_code, source_code, class_name, package_name, special_prompt:
        captured.append(special_prompt) or "FIXED")
    saved_find, saved_read = etg.find_source_code, etg.read_source_code
    etg.find_source_code = lambda *a, **k: "/nonexistent/Account.java"
    etg.read_source_code = lambda *a, **k: SOURCE_CODE
    try:
        gen.select_final_best_test(merge_all=True)
    finally:
        etg.find_source_code, etg.read_source_code = saved_find, saved_read
    assert len(captured) == 1, f"{case_id}: merge prompt not captured"
    add_case("P7", case_id, {
        "baseTest": base_test,
        "methodCodes": [m.get("code", "") for m in verified_bug_methods
                        if isinstance(m, dict) and m.get("is_real_bug", True)],
    }, captured[0])


BUG_METHOD = ("@Test\npublic void verifiedBug%d() {\n"
              "    assertTrue(new Account().withdraw(0));\n}")

p7("P7-001", TEST_CODE, [
    {"code": BUG_METHOD % 1, "is_real_bug": True},
    {"code": BUG_METHOD % 2, "is_real_bug": True},
])
p7("P7-002", TEST_CODE, [
    {"code": BUG_METHOD % i, "is_real_bug": True} for i in range(1, 8)
])  # 7 verified methods -> only the first 5 embedded
p7("P7-003", "public class T {}", [
    {"is_real_bug": True},  # no "code" key -> method.get('code','') == ""
])

# ---------------------------------------------------------------------------

out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "prompts.json")
os.makedirs(os.path.dirname(out_path), exist_ok=True)
with open(out_path, "w", encoding="utf-8") as f:
    json.dump({
        "_comment": [
            "LAYER-P PINNED ORACLE SNAPSHOT - do not edit by hand.",
            "Every renderedPrompt was produced by the REAL Python prompt functions",
            "of the FailMapper baseline; see gen_prompts.py module docstring.",
            "Regen: cd /Users/ruiqidong/Desktop/FailMapper && python3 "
            "java/failmapper-llm/src/test/python/gen_prompts.py "
            "java/failmapper-llm/src/test/resources/layerp/prompts.json",
        ],
        "cases": CASES,
    }, f, ensure_ascii=False, indent=2)
    f.write("\n")

print(f"wrote {len(CASES)} cases to {out_path}")
