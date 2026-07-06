package org.failmapper.analysis;

import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.RiskLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the Python {@code FS_Detector} (failure_scenarios.py): detects common
 * logical-bug failure scenarios in Java source code by regex over the RAW source text.
 *
 * <p>Fidelity notes (doc/JAVA_PORT_CONTRACT.md section 3.3 / D12):
 * <ul>
 *   <li>Detectors intentionally fire on commented-out code and string literals — the regexes
 *       run over raw source and only SOME detectors consult the crude
 *       {@link #isInCommentOrString} line heuristic, exactly mirroring which Python call
 *       sites do. Do NOT add comment filtering.</li>
 *   <li>Per-detector exception boundary (failure_scenarios.py:97-101): each of the 21
 *       registered detectors runs in its own try/catch(Exception); on failure the detector
 *       aborts but every scenario it already appended survives, and later detectors still
 *       run. Contract X5: some detectors interpolate raw code fragments into regexes;
 *       java.util.regex throws on MORE malformed fragments than Python re (e.g. a stray
 *       '{'), so the SET of aborting inputs differs by engine — accepted divergence.</li>
 *   <li>All patterns compile with {@link Pattern#UNICODE_CHARACTER_CLASS} for Python
 *       {@code \w}/{@code \d}/{@code \b} parity (contract X2). failure_scenarios.py uses no
 *       re.IGNORECASE, so no CASE_INSENSITIVE flags are needed (contract X3).</li>
 *   <li>{@code re.split(r'&&|\|\|', expr)} keeps trailing empty strings — ported as
 *       {@code Pattern.split(expr, -1)} (contract X4).</li>
 *   <li>Python {@code str.isdigit()/isalpha()/isalnum()} are translated via
 *       {@link Character#isDigit}/{@code isLetter}/{@code isLetterOrDigit} on every char of
 *       a non-empty string: identical for ASCII (the realistic input set); Unicode
 *       superscript digits / numeric letters diverge (documented divergence, S7).</li>
 *   <li>Type/subtype string vocabulary is preserved verbatim; risk sort is Python's
 *       detect_patterns() sort (failure_scenarios.py:104): stable, by weight
 *       high=3 &gt; medium=2 &gt; low=1 &gt; critical=0 ({@link RiskLevel#sortWeight}),
 *       ties keep detector registration order then per-detector match order.</li>
 *   <li>Divergence from Python __init__: a null source is treated as "" so that
 *       {@link #detect()} never throws (Python would raise in __init__); required by the
 *       port task's null-safety contract.</li>
 * </ul>
 */
public final class FailureScenarioDetector {

    /** Contract X2: Python \w/\d/\b are Unicode-aware. */
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    /** Python line 286: receivers skipped by the null-handling detector. */
    private static final Set<String> NON_NULL_RECEIVERS =
            Set.of("this", "super", "String", "Integer", "Boolean", "Double", "Math");

    /** Python line 862: primitive/immutable field types skipped by the concurrency detector. */
    private static final Set<String> PRIMITIVE_FIELD_TYPES = Set.of(
            "int", "boolean", "char", "byte", "short", "long", "float", "double", "String");

    /** Python line 1268: placeholder credentials skipped by the security detector. */
    private static final Set<String> CREDENTIAL_PLACEHOLDERS =
            Set.of("password", "changeme", "test", "example", "placeholder");

    private final String sourceCode;
    @SuppressWarnings("unused")
    private final String classFqn; // parity: Python stores class/package names (export only)
    @SuppressWarnings("unused")
    private final FailureModel fModel; // parity: Python stores f_model but no detector reads it
    private final String[] lines;
    private final List<FailureScenario> patterns = new ArrayList<>();
    private final List<Runnable> detectors;

    public FailureScenarioDetector(String sourceCode, String classFqn, FailureModel fModel) {
        this.sourceCode = sourceCode == null ? "" : sourceCode;
        this.classFqn = classFqn;
        this.fModel = fModel;
        // Python str.split('\n') keeps trailing empty strings -> limit -1.
        this.lines = this.sourceCode.split("\n", -1);
        // Registration order is load-bearing (contract O11): it is the tie-break order
        // within a risk level after the stable sort in detect(). 21 detectors, matching
        // the active Python list at failure_scenarios.py:47-69.
        this.detectors = List.of(
                this::detectOperatorPrecedenceBugs,      // 1
                this::detectOffByOneBugs,                // 2
                this::detectBoundaryConditionBugs,       // 3
                this::detectNullHandlingBugs,            // 4
                this::detectStringComparisonBugs,        // 5
                this::detectBooleanBugs,                 // 6
                this::detectResourceLeaks,               // 7
                this::detectStateCorruptionBugs,         // 8
                this::detectIntegerOverflowBugs,         // 9
                this::detectCopyPasteBugs,               // 10
                this::detectFloatingPointComparison,     // 11
                this::detectExceptionHandlingBugs,       // 12
                this::detectComplexLoopConditions,       // 13
                this::detectResourceManagementDefects,   // 14
                this::detectDataOperationBugs,           // 15
                this::detectConcurrencyIssues,           // 16
                this::detectErrorPropagationIssues,      // 17
                this::detectImproperValidation,          // 18
                this::detectSecurityVulnerabilities,     // 19
                this::detectStringIndexBoundsBugs,       // 20
                this::detectArrayIndexBoundsBugs);       // 21
    }

    /** Number of registered detectors (Python failure_scenarios.py:47-69). */
    int detectorCount() {
        return detectors.size();
    }

    /**
     * Python FS_Detector.detect_patterns(): run every detector inside its own exception
     * boundary, then stable-sort by risk weight descending (failure_scenarios.py:104).
     */
    public List<FailureScenario> detect() {
        for (Runnable detector : detectors) {
            try {
                detector.run();
            } catch (Exception e) {
                // Python parity (failure_scenarios.py:97-101): abort THIS detector only;
                // patterns it already appended survive, remaining detectors still run.
                // Contract X5: java.util.regex aborts on interpolated fragments (e.g. a
                // stray '{') that Python re tolerates — accepted engine divergence.
            }
        }
        // Stable sort: high=3 > medium=2 > low=1 > critical=0 (RiskLevel.sortWeight);
        // equal weights keep registration order then per-detector match order.
        patterns.sort(Comparator.comparingInt(
                (FailureScenario s) -> s.riskLevel().sortWeight()).reversed());
        return new ArrayList<>(patterns);
    }

    private void add(String type, String subtype, int line, RiskLevel risk,
                     String code, String description) {
        patterns.add(new FailureScenario(type, subtype, line, risk, code, description));
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    // ------------------------------------------------------------------
    // 1. failure_scenarios.py:108 _detect_operator_precedence_bugs
    // ------------------------------------------------------------------
    private void detectOperatorPrecedenceBugs() {
        Matcher m = p("([^()]*?[&|<>=!^]+[^()]*?[&|<>=!^]+[^()]*?)").matcher(sourceCode);
        while (m.find()) {
            String expr = m.group(1).strip();
            int lineNum = lineNumberAt(m.start());
            if (isInCommentOrString(m.start())) {
                continue;
            }
            if ((expr.contains("&&") && expr.contains("||"))
                    || (expr.contains("&") && expr.contains("|"))) {
                // Note: expr can never contain parentheses (the char class excludes them),
                // so this negative guard is always true — kept verbatim from Python.
                if (!p("\\([^()]*?(?:&&|\\|\\|)[^()]*?\\)").matcher(expr).find()) {
                    add("operator_precedence", null, lineNum, RiskLevel.HIGH, expr,
                            "Mixed logical operators (AND/OR) without clarifying parentheses");
                }
            }
            if ((expr.contains("&") && !expr.contains("&&"))
                    || (expr.contains("|") && !expr.contains("||"))) {
                // Dead branch in Python too (expr never contains parens); kept for parity.
                if (p("if\\s*\\([^)]*[&|][^)]*\\)").matcher(expr).find()) {
                    add("bitwise_logical_confusion", null, lineNum, RiskLevel.HIGH, expr,
                            "Possible confusion between bitwise (&, |) and logical (&&, ||) operators");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. failure_scenarios.py:146 _detect_off_by_one_bugs
    // ------------------------------------------------------------------
    private void detectOffByOneBugs() {
        // Python char class [^][] -> Java needs the brackets escaped.
        Matcher m = p("(\\w+)\\s*\\[\\s*([^\\]\\[]+)\\s*\\]").matcher(sourceCode);
        while (m.find()) {
            String arrayName = m.group(1);
            String indexExpr = m.group(2);
            int lineNum = lineNumberAt(m.start());
            if (p("^\\d+$").matcher(indexExpr).find()) {
                String context = context(lineNum, 5);
                if (context.contains(arrayName + ".length")) {
                    add("off_by_one", null, lineNum, RiskLevel.MEDIUM,
                            arrayName + "[" + indexExpr + "]",
                            "Hardcoded array index (" + indexExpr + ") near length check");
                }
            } else if (indexExpr.contains(".length - 1") || indexExpr.contains(".length")) {
                add("off_by_one", null, lineNum, RiskLevel.MEDIUM,
                        arrayName + "[" + indexExpr + "]",
                        "Array access using length expression, potential off-by-one");
            }
        }
        String[] loopPatterns = {
                "for\\s*\\([^;]*;\\s*(\\w+)\\s*([<>=!]+)\\s*([^;]+);",
                "while\\s*\\(\\s*(\\w+)\\s*([<>=!]+)\\s*([^)]+)\\)"
        };
        for (String loopPattern : loopPatterns) {
            Matcher lm = p(loopPattern).matcher(sourceCode);
            while (lm.find()) {
                String varName = lm.group(1);
                String operator = lm.group(2);
                String boundary = lm.group(3).strip();
                int lineNum = lineNumberAt(lm.start());
                boolean inclusiveOp = operator.equals("<=") || operator.equals(">=");
                if ((boundary.contains(".length") || boundary.contains(".size()")) && inclusiveOp) {
                    add("off_by_one", null, lineNum, RiskLevel.HIGH,
                            varName + " " + operator + " " + boundary,
                            "Potential off-by-one in loop condition using " + operator
                                    + " with length/size");
                } else if (p("\\d+").matcher(boundary).find() && inclusiveOp) {
                    String context = context(lineNum, 5);
                    if (p("(\\w+)\\.(?:length|size)").matcher(context).find()) {
                        add("off_by_one", null, lineNum, RiskLevel.MEDIUM,
                                varName + " " + operator + " " + boundary,
                                "Loop using <= or >= with constant boundary near array/list access");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. failure_scenarios.py:215 _detect_boundary_condition_bugs
    // ------------------------------------------------------------------
    private void detectBoundaryConditionBugs() {
        Matcher m = p("if\\s*\\(\\s*([^)]+?)\\s*([<>=!]+)\\s*([^)]+?)\\s*\\)").matcher(sourceCode);
        while (m.find()) {
            String left = m.group(1).strip();
            String operator = m.group(2);
            String right = m.group(3).strip();
            int lineNum = lineNumberAt(m.start());
            if (isInCommentOrString(m.start())) {
                continue;
            }
            if (right.equals("0") || right.equals("1") || left.equals("0") || left.equals("1")) {
                String context = context(lineNum, 3);
                if (context.contains("[") && context.contains("]")) {
                    add("boundary_condition", null, lineNum, RiskLevel.HIGH,
                            left + " " + operator + " " + right,
                            "Boundary check against " + right + " near array access");
                }
            }
            if (left.contains(".length()") || left.contains(".size()") || left.contains(".length")) {
                if (operator.equals("==") || operator.equals("<=") || operator.equals(">=")) {
                    if (right.equals("0")) {
                        add("boundary_condition", null, lineNum, RiskLevel.MEDIUM,
                                left + " " + operator + " " + right,
                                "Empty check using length/size");
                    } else {
                        add("boundary_condition", null, lineNum, RiskLevel.MEDIUM,
                                left + " " + operator + " " + right,
                                "Size/length comparison using " + operator);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. failure_scenarios.py:265 _detect_null_handling_bugs
    // ------------------------------------------------------------------
    private void detectNullHandlingBugs() {
        Set<String> nullCheckedVars = new HashSet<>();
        Matcher nc = p("if\\s*\\(\\s*(\\w+)\\s*(?:==|!=)\\s*null\\s*\\)").matcher(sourceCode);
        while (nc.find()) {
            nullCheckedVars.add(nc.group(1));
        }
        Matcher m = p("(\\w+)\\.(\\w+)\\(").matcher(sourceCode);
        while (m.find()) {
            String objName = m.group(1);
            String methodName = m.group(2);
            int lineNum = lineNumberAt(m.start());
            if (NON_NULL_RECEIVERS.contains(objName)) {
                continue;
            }
            if (nullCheckedVars.contains(objName)) {
                continue;
            }
            if (isLikelyParameter(objName)) {
                add("null_handling", null, lineNum, RiskLevel.HIGH,
                        objName + "." + methodName + "(...)",
                        "Method call on potential parameter " + objName + " without null check");
            }
        }
        Matcher nested = p("(\\w+)\\.(\\w+)\\.(\\w+)").matcher(sourceCode);
        while (nested.find()) {
            String objName = nested.group(1);
            int lineNum = lineNumberAt(nested.start());
            if (!isPropertyAccess(nested.group(2))) {
                continue;
            }
            if (!nullCheckedVars.contains(objName)) {
                add("null_handling", null, lineNum, RiskLevel.MEDIUM, nested.group(),
                        "Nested property access without null checking intermediate results");
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. failure_scenarios.py:325 _detect_string_comparison_bugs
    // ------------------------------------------------------------------
    private void detectStringComparisonBugs() {
        Matcher m = p("(\\w+)\\s*(==|!=)\\s*([\"\\w]+)").matcher(sourceCode);
        while (m.find()) {
            String left = m.group(1);
            String operator = m.group(2);
            String right = m.group(3);
            int lineNum = lineNumberAt(m.start());
            if (isInCommentOrString(m.start()) || right.equals("null") || left.equals("null")) {
                continue;
            }
            String context = context(lineNum, 5);
            // Python operator precedence: '"' in right OR ("String" in context AND isalpha).
            if (right.contains("\"")
                    || (context.contains("String")
                        && (pythonIsAlpha(right) || pythonIsAlpha(left)))) {
                add("string_comparison", null, lineNum, RiskLevel.HIGH,
                        left + " " + operator + " " + right,
                        "Possible string comparison using " + operator + " instead of .equals()");
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. failure_scenarios.py:353 _detect_boolean_bugs
    // ------------------------------------------------------------------
    private void detectBooleanBugs() {
        Matcher m = p("(?:if|while)\\s*\\(\\s*([^{};()]+?(?:&&|\\|\\|)[^{};()]+?)\\s*\\)")
                .matcher(sourceCode);
        while (m.find()) {
            String expr = m.group(1).strip();
            int lineNum = lineNumberAt(m.start());
            if (countOccurrences(expr, "!") > 1) {
                add("boolean_bug", null, lineNum, RiskLevel.MEDIUM, expr,
                        "Multiple negations in boolean expression, possible logic error");
            }
            if (p("!\\s*\\(\\s*([^()]+?)\\s*(?:&&|\\|\\|)\\s*([^()]+?)\\s*\\)")
                    .matcher(expr).find()) {
                add("boolean_bug", null, lineNum, RiskLevel.HIGH, expr,
                        "Negated AND/OR expression, potential DeMorgan's Law error");
            }
            // Contract X4: Python re.split KEEPS trailing empty strings -> split limit -1.
            String[] parts = p("&&|\\|\\|").split(expr, -1);
            Set<String> uniqueParts = new HashSet<>();
            for (String part : parts) {
                uniqueParts.add(part.strip());
            }
            if (parts.length != uniqueParts.size()) {
                add("boolean_bug", null, lineNum, RiskLevel.MEDIUM, expr,
                        "Redundant conditions in boolean expression");
            }
            if ((expr.contains("true") && expr.contains("||"))
                    || (expr.contains("false") && expr.contains("&&"))) {
                add("boolean_bug", null, lineNum, RiskLevel.MEDIUM, expr,
                        "Potential tautology or contradiction in boolean expression");
            }
        }
    }

    // ------------------------------------------------------------------
    // 7. failure_scenarios.py:406 _detect_resource_leaks
    // ------------------------------------------------------------------
    private void detectResourceLeaks() {
        Matcher m = p("new\\s+(FileInputStream|FileOutputStream|BufferedReader|Scanner|Connection)[\\s\\(]")
                .matcher(sourceCode);
        while (m.find()) {
            String resourceType = m.group(1);
            int lineNum = lineNumberAt(m.start());
            String context = context(lineNum, 10);
            // Python: "try (" in context and ")" in context.split("try (")[1].split("{")[0]
            int tryIdx = context.indexOf("try (");
            if (tryIdx >= 0) {
                String afterTry = context.substring(tryIdx + "try (".length());
                int nextTry = afterTry.indexOf("try (");
                if (nextTry >= 0) {
                    afterTry = afterTry.substring(0, nextTry);
                }
                int braceIdx = afterTry.indexOf('{');
                String segment = braceIdx >= 0 ? afterTry.substring(0, braceIdx) : afterTry;
                if (segment.contains(")")) {
                    continue;
                }
            }
            if (!context.contains(".close()")) {
                add("resource_leak", null, lineNum, RiskLevel.HIGH,
                        "new " + resourceType + "(...)",
                        "Resource allocation without proper closing or try-with-resources");
            }
        }
    }

    // ------------------------------------------------------------------
    // 8. failure_scenarios.py:431 _detect_state_corruption_bugs
    // ------------------------------------------------------------------
    private void detectStateCorruptionBugs() {
        Matcher m = p("for\\s*\\(\\s*(?:\\w+\\s+)?(\\w+)\\s*:\\s*(\\w+)\\s*\\)").matcher(sourceCode);
        while (m.find()) {
            String loopVar = m.group(1);
            String collection = m.group(2);
            int lineNum = lineNumberAt(m.start());
            int loopStart = sourceCode.indexOf('{', m.end());
            if (loopStart == -1) {
                continue;
            }
            int loopEnd = matchingBraceEnd(loopStart);
            String loopBody = sourceCode.substring(loopStart, loopEnd);
            if (loopBody.contains(collection + ".add") || loopBody.contains(collection + ".remove")) {
                add("state_corruption", null, lineNum, RiskLevel.HIGH,
                        "for (" + loopVar + " : " + collection + ")",
                        "Collection " + collection + " modified during iteration, "
                                + "possible ConcurrentModificationException");
            }
        }
    }

    // ------------------------------------------------------------------
    // 9. failure_scenarios.py:469 _detect_integer_overflow_bugs
    // ------------------------------------------------------------------
    private void detectIntegerOverflowBugs() {
        Matcher m = p("(Integer\\.MAX_VALUE|Long\\.MAX_VALUE)").matcher(sourceCode);
        while (m.find()) {
            int lineNum = lineNumberAt(m.start());
            String context = context(lineNum, 3);
            if (p("[+\\-*/]").matcher(context).find()) {
                int nl = context.indexOf('\n');
                String firstLine = nl >= 0 ? context.substring(0, nl) : context;
                add("integer_overflow", null, lineNum, RiskLevel.HIGH, firstLine,
                        "Arithmetic operation near " + m.group(1) + ", possible overflow");
            }
        }
        // Python char class [^]] -> Java needs the bracket escaped.
        Matcher a = p("new\\s+\\w+\\[([^\\]]+)\\]").matcher(sourceCode);
        while (a.find()) {
            String sizeExpr = a.group(1);
            int lineNum = lineNumberAt(a.start());
            if (p("[+\\-*/]").matcher(sizeExpr).find() || p("\\d{6,}").matcher(sizeExpr).find()) {
                add("integer_overflow", null, lineNum, RiskLevel.MEDIUM,
                        "new ...[" + sizeExpr + "]",
                        "Array allocation with complex size expression, possible overflow");
            }
        }
    }

    // ------------------------------------------------------------------
    // 10. failure_scenarios.py:507 _detect_copy_paste_bugs
    // ------------------------------------------------------------------
    private void detectCopyPasteBugs() {
        String[] srcLines = sourceCode.split("\n", -1);
        for (int i = 0; i < srcLines.length - 1; i++) {
            if (srcLines[i].strip().length() < 10) {
                continue;
            }
            String current = srcLines[i].strip();
            String next = srcLines[i + 1].strip();
            if (!current.equals(next) && similarity(current, next) > 0.8) {
                int minLen = Math.min(current.length(), next.length());
                List<Integer> diffIndices = new ArrayList<>();
                for (int j = 0; j < minLen; j++) {
                    if (current.charAt(j) != next.charAt(j)) {
                        diffIndices.add(j);
                    }
                }
                if (!diffIndices.isEmpty() && diffIndices.size() <= 5) {
                    StringBuilder currentDiff = new StringBuilder();
                    StringBuilder nextDiff = new StringBuilder();
                    for (int j : diffIndices) {
                        if (j < current.length()) {
                            currentDiff.append(current.charAt(j));
                        }
                    }
                    for (int j : diffIndices) {
                        if (j < next.length()) {
                            nextDiff.append(next.charAt(j));
                        }
                    }
                    if (pythonIsAlnum(currentDiff.toString()) && pythonIsAlnum(nextDiff.toString())) {
                        add("copy_paste", null, i + 1, RiskLevel.MEDIUM,
                                current + "\n" + next,
                                "Similar consecutive lines with small differences, "
                                        + "potential copy-paste error");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 11. failure_scenarios.py:538 _detect_floating_point_comparison
    // ------------------------------------------------------------------
    private void detectFloatingPointComparison() {
        Matcher m = p("([^=!><]|^)(==|!=)\\s*(\\d+\\.\\d+)").matcher(sourceCode);
        while (m.find()) {
            String operator = m.group(2);
            String floatValue = m.group(3);
            int lineNum = lineNumberAt(m.start());
            if (isInCommentOrString(m.start())) {
                continue;
            }
            String context = context(lineNum, 3);
            if (context.contains("float") || context.contains("double")
                    || context.contains("Float") || context.contains("Double")) {
                add("floating_point_comparison", null, lineNum, RiskLevel.HIGH,
                        "... " + operator + " " + floatValue,
                        "Exact comparison of floating point values using " + operator);
            }
        }
        Matcher v = p("(\\w+)\\s+(==|!=)\\s+(\\w+)").matcher(sourceCode);
        while (v.find()) {
            String var1 = v.group(1);
            String operator = v.group(2);
            String var2 = v.group(3);
            int lineNum = lineNumberAt(v.start());
            String context = context(lineNum, 5);
            if ((context.contains("float") || context.contains("double")
                    || context.contains("Float") || context.contains("Double"))
                    && !context.contains("int ") && !context.contains("Integer")) {
                add("floating_point_comparison", null, lineNum, RiskLevel.HIGH,
                        var1 + " " + operator + " " + var2,
                        "Potential exact comparison of floating point variables using " + operator);
            }
        }
    }

    // ------------------------------------------------------------------
    // 12. failure_scenarios.py:585 _detect_exception_handling_bugs
    // ------------------------------------------------------------------
    private void detectExceptionHandlingBugs() {
        Matcher empty = p("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(sourceCode);
        while (empty.find()) {
            add("exception_handling", null, lineNumberAt(empty.start()), RiskLevel.MEDIUM,
                    empty.group(), "Empty catch block, silently swallowing exception");
        }
        Matcher comment = p("catch\\s*\\([^)]+\\)\\s*\\{\\s*(?://[^\\n]*|/\\*[^*]*\\*/)\\s*\\}")
                .matcher(sourceCode);
        while (comment.find()) {
            add("exception_handling", null, lineNumberAt(comment.start()), RiskLevel.LOW,
                    comment.group(), "Catch block with only comments, effectively swallowing exception");
        }
        Matcher generic = p("catch\\s*\\(\\s*(?:Exception|Throwable|RuntimeException)\\s+")
                .matcher(sourceCode);
        while (generic.find()) {
            add("exception_handling", null, lineNumberAt(generic.start()), RiskLevel.MEDIUM,
                    generic.group() + "...",
                    "Catching generic Exception/Throwable, may mask important errors");
        }
        Matcher throwFinally = p("finally\\s*\\{[^}]*throw\\s+").matcher(sourceCode);
        while (throwFinally.find()) {
            add("exception_handling", null, lineNumberAt(throwFinally.start()), RiskLevel.HIGH,
                    "finally { ... throw ...",
                    "Throwing exception from finally block, may mask original exception");
        }
    }

    // ------------------------------------------------------------------
    // 13. failure_scenarios.py:643 _detect_complex_loop_conditions
    // ------------------------------------------------------------------
    private void detectComplexLoopConditions() {
        Matcher w = p("while\\s*\\(\\s*([^{};()]+?(?:&&|\\|\\|)[^{};()]+?)\\s*\\)").matcher(sourceCode);
        while (w.find()) {
            String condition = w.group(1);
            int lineNum = lineNumberAt(w.start());
            int opCount = countOccurrences(condition, "&&") + countOccurrences(condition, "||");
            if (opCount >= 2) {
                add("complex_loop_condition", null, lineNum, RiskLevel.MEDIUM,
                        "while (" + condition + ")",
                        "Complex loop condition with " + opCount + " logical operators");
            }
        }
        Matcher f = p("for\\s*\\(\\s*(?:\\w+\\s+)?(\\w+)[^;]*;\\s*\\1[^;]*;\\s*\\1\\s*([+\\-*/%]=|\\+\\+|--)")
                .matcher(sourceCode);
        while (f.find()) {
            String varName = f.group(1);
            String updateOp = f.group(2);
            int lineNum = lineNumberAt(f.start());
            int loopStart = sourceCode.indexOf('{', f.end());
            if (loopStart == -1) {
                continue;
            }
            int loopEnd = matchingBraceEnd(loopStart);
            String loopBody = sourceCode.substring(loopStart, loopEnd);
            // varName came from a (\w+) group, so raw interpolation is safe here.
            if (p("\\b" + varName + "\\s*([+\\-*/%]=|\\+\\+|--)").matcher(loopBody).find()) {
                add("complex_loop_condition", null, lineNum, RiskLevel.HIGH,
                        "for (..." + varName + "...;..." + varName + "...;..."
                                + varName + updateOp + "...)",
                        "Loop variable " + varName + " updated both in loop control and loop body, "
                                + "potential logic error");
            }
        }
    }

    // ------------------------------------------------------------------
    // 14. failure_scenarios.py:704 _detect_resource_management_defects
    // ------------------------------------------------------------------
    private void detectResourceManagementDefects() {
        String[][] resourcePairs = {
                {"new FileInputStream\\([^)]+\\)", "\\.close\\(\\)"},
                {"new FileOutputStream\\([^)]+\\)", "\\.close\\(\\)"},
                {"new FileReader\\([^)]+\\)", "\\.close\\(\\)"},
                {"new FileWriter\\([^)]+\\)", "\\.close\\(\\)"},
                {"getConnection\\([^)]+\\)", "\\.close\\(\\)"},
                {"createStatement\\(\\)", "\\.close\\(\\)"},
                {"prepareStatement\\([^)]+\\)", "\\.close\\(\\)"},
                {"\\.lock\\(\\)", "\\.unlock\\(\\)"},
                {"\\.acquire\\(\\)", "\\.release\\(\\)"}
        };
        for (String[] pair : resourcePairs) {
            Matcher m = p(pair[0]).matcher(sourceCode);
            while (m.find()) {
                int lineNum = lineNumberAt(m.start());
                if (isInCommentOrString(m.start())) {
                    continue;
                }
                String contextAfter = sourceCode.substring(
                        m.end(), Math.min(sourceCode.length(), m.end() + 500));
                // Python re.escape -> Pattern.quote (literal fragment, cannot throw).
                boolean tryWithResources = p("try\\s*\\(\\s*[^)]*" + Pattern.quote(m.group()))
                        .matcher(contextAfter).find();
                if (!tryWithResources && !p(pair[1]).matcher(contextAfter).find()) {
                    add("resource_management", "resource_leak", lineNum, RiskLevel.HIGH,
                            m.group(),
                            "Resource acquired but not properly released: " + m.group());
                }
            }
        }
        Matcher c = p("(\\w+)\\.close\\(\\)").matcher(sourceCode);
        while (c.find()) {
            String resourceVar = c.group(1);
            int lineNum = lineNumberAt(c.start());
            if (isInCommentOrString(c.start())) {
                continue;
            }
            String contextAfter = sourceCode.substring(
                    c.end(), Math.min(sourceCode.length(), c.end() + 500));
            if (p("\\b" + Pattern.quote(resourceVar) + "\\.\\w+").matcher(contextAfter).find()) {
                add("resource_management", "use_after_close", lineNum, RiskLevel.HIGH,
                        resourceVar + ".close()",
                        "Resource " + resourceVar + " might be used after being closed");
            }
        }
    }

    // ------------------------------------------------------------------
    // 15. failure_scenarios.py:778 _detect_data_operation_bugs
    // ------------------------------------------------------------------
    private void detectDataOperationBugs() {
        String[][] riskyConversions = {
                {"(\\w+)\\s*=\\s*\\(int\\)\\s*(\\w+)", "integer_truncation"},
                {"(\\w+)\\s*=\\s*\\(float\\)\\s*(\\w+)", "precision_loss"},
                {"(\\w+)\\s*=\\s*\\(int\\)\\s*(\\w+)\\.(\\w+)(?:\\(\\))?", "long_to_int_conversion"}
        };
        for (String[] entry : riskyConversions) {
            Matcher m = p(entry[0]).matcher(sourceCode);
            while (m.find()) {
                int lineNum = lineNumberAt(m.start());
                if (isInCommentOrString(m.start())) {
                    continue;
                }
                add("data_operation", entry[1], lineNum, RiskLevel.MEDIUM, m.group(),
                        "Potentially risky type conversion: " + m.group());
            }
        }
        Matcher division = p("(\\b\\d+)\\s*/\\s*(\\b\\d+)").matcher(sourceCode);
        while (division.find()) {
            int lineNum = lineNumberAt(division.start());
            if (isInCommentOrString(division.start())) {
                continue;
            }
            String context = context(lineNum, 2);
            if (context.contains("double") || context.contains("float")) {
                add("data_operation", "integer_division", lineNum, RiskLevel.MEDIUM,
                        division.group(),
                        "Integer division in floating-point context may cause precision loss");
            }
        }
        Matcher signed = p("([a-zA-Z0-9_.]+)\\.length\\s*([<>=!]+)\\s*(-\\d+)").matcher(sourceCode);
        while (signed.find()) {
            if (signed.group(3).startsWith("-")) { // always true; kept verbatim from Python
                int lineNum = lineNumberAt(signed.start());
                add("data_operation", "signed_unsigned_comparison", lineNum, RiskLevel.MEDIUM,
                        signed.group(),
                        "Comparison of .length (always >= 0) with negative value");
            }
        }
    }

    // ------------------------------------------------------------------
    // 16. failure_scenarios.py:846 _detect_concurrency_issues
    // ------------------------------------------------------------------
    private void detectConcurrencyIssues() {
        Set<String> synchronizedFields = new HashSet<>();
        Matcher sync = p("synchronized\\s*\\(\\s*(\\w+|\\bthis\\b)\\s*\\)").matcher(sourceCode);
        while (sync.find()) {
            synchronizedFields.add(sync.group(1));
        }
        Matcher field = p("(private|protected|public)(?:\\s+static)?\\s+(?!final)\\s*(\\w+)(?:<[^>]+>)?\\s+(\\w+)\\s*[=;]")
                .matcher(sourceCode);
        while (field.find()) {
            String fieldType = field.group(2);
            if (PRIMITIVE_FIELD_TYPES.contains(fieldType)) {
                continue;
            }
            String fieldName = field.group(3);
            int lineNum = lineNumberAt(field.start());
            if (isInCommentOrString(field.start())) {
                continue;
            }
            if (!field.group().contains("synchronized") && !synchronizedFields.contains(fieldName)) {
                if (p("Thread|Runnable|Callable|ExecutorService").matcher(sourceCode).find()) {
                    add("concurrency", "unsynchronized_shared_state", lineNum, RiskLevel.HIGH,
                            field.group(),
                            "Potentially shared mutable field '" + fieldName
                                    + "' without proper synchronization");
                }
            }
        }
        // Python char class [\w<>[\],\s] tolerates the raw inner '[' ; Java parses an
        // unescaped '[' inside a class as a nested class union, so it must be escaped —
        // same matched language, dialect-only translation.
        Matcher method = p("(?:public|protected|private)\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{([^}]+)")
                .matcher(sourceCode);
        while (method.find()) {
            String methodName = method.group(1);
            String methodBody = method.group(2);
            List<String> syncedObjects = new ArrayList<>();
            Matcher bodySync = p("synchronized\\s*\\(\\s*(\\w+|\\bthis\\b)\\s*\\)").matcher(methodBody);
            while (bodySync.find()) {
                syncedObjects.add(bodySync.group(1));
            }
            if (syncedObjects.size() > 1) {
                int lineNum = lineNumberAt(method.start());
                add("concurrency", "potential_deadlock", lineNum, RiskLevel.HIGH,
                        "Method " + methodName + " with multiple synchronized blocks",
                        "Multiple synchronized blocks in method '" + methodName
                                + "' may lead to deadlocks");
            }
        }
    }

    // ------------------------------------------------------------------
    // 17. failure_scenarios.py:911 _detect_error_propagation_issues
    // ------------------------------------------------------------------
    private void detectErrorPropagationIssues() {
        Matcher empty = p("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(sourceCode);
        while (empty.find()) {
            int lineNum = lineNumberAt(empty.start());
            if (isInCommentOrString(empty.start())) {
                continue;
            }
            add("exception_handling", "empty_catch", lineNum, RiskLevel.HIGH, empty.group(),
                    "Empty catch block swallows exception without handling");
        }
        Matcher broad = p("catch\\s*\\(\\s*(Throwable|Exception)\\s+\\w+\\s*\\)").matcher(sourceCode);
        while (broad.find()) {
            int lineNum = lineNumberAt(broad.start());
            if (isInCommentOrString(broad.start())) {
                continue;
            }
            String contextAfter = sourceCode.substring(
                    broad.end(), Math.min(sourceCode.length(), broad.end() + 300));
            if (!p("(?:throw|log|report|printStackTrace)").matcher(contextAfter).find()) {
                add("exception_handling", "swallowed_exception", lineNum, RiskLevel.HIGH,
                        broad.group(),
                        "Catching " + broad.group(1)
                                + " without proper handling may swallow important exceptions");
            }
        }
    }

    // ------------------------------------------------------------------
    // 18. failure_scenarios.py:1200 _detect_improper_validation
    // ------------------------------------------------------------------
    private void detectImproperValidation() {
        Matcher method = p("(?:public|protected|private)\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{")
                .matcher(sourceCode);
        while (method.find()) {
            String methodName = method.group(1);
            String params = method.group(2);
            Matcher param = p("(String|List|Map|Set|Collection|Array)(?:<[^>]+>)?\\s+(\\w+)")
                    .matcher(params);
            while (param.find()) {
                String paramType = param.group(1);
                String paramName = param.group(2);
                int lineNum = lineNumberAt(method.start());
                String methodBody = context(lineNum, 20);
                // paramName came from a (\w+) group, so raw interpolation is safe here.
                boolean nullCheck = p(paramName + "\\s*==\\s*null").matcher(methodBody).find()
                        || p("Objects\\.requireNonNull").matcher(methodBody).find();
                boolean emptyCheck = p(paramName + "\\.isEmpty\\(\\)").matcher(methodBody).find()
                        || p(paramName + "\\.length\\s*==\\s*0").matcher(methodBody).find();
                boolean checkedType = paramType.equals("String") || paramType.equals("List")
                        || paramType.equals("Map") || paramType.equals("Set")
                        || paramType.equals("Collection");
                if (!nullCheck && checkedType) {
                    add("validation", "missing_null_check", lineNum, RiskLevel.MEDIUM,
                            "Method " + methodName + ", parameter " + paramName,
                            "Parameter '" + paramName + "' of type '" + paramType
                                    + "' is not checked for null");
                }
                if (!emptyCheck && checkedType) {
                    add("validation", "missing_empty_check", lineNum, RiskLevel.MEDIUM,
                            "Method " + methodName + ", parameter " + paramName,
                            "Parameter '" + paramName + "' of type '" + paramType
                                    + "' is not checked for empty");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 19. failure_scenarios.py:1247 _detect_security_vulnerabilities
    // ------------------------------------------------------------------
    private void detectSecurityVulnerabilities() {
        String[][] credentialPatterns = {
                {"(?:password|passwd|pwd|secret|key)\\s*=\\s*\"([^\"]+)\"", "hardcoded_password"},
                {"(?:getConnection|DriverManager\\.getConnection)\\([^,]+,\\s*\"[^\"]+\",\\s*\"([^\"]+)\"",
                        "hardcoded_db_password"},
                {"(?:private|static)\\s+(?:final)?\\s*String\\s+\\w*(?:PASSWORD|SECRET|KEY)\\w*\\s*=\\s*\"([^\"]+)\"",
                        "hardcoded_credential"}
        };
        for (String[] entry : credentialPatterns) {
            Matcher m = p(entry[0]).matcher(sourceCode);
            while (m.find()) {
                String credential = m.group(1);
                int lineNum = lineNumberAt(m.start());
                if (isInCommentOrString(m.start())) {
                    continue;
                }
                if (CREDENTIAL_PLACEHOLDERS.contains(credential.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                add("security", entry[1], lineNum, RiskLevel.CRITICAL,
                        "Redacted for security reasons", "Hardcoded credential detected");
            }
        }
        String[] sqlInjectionPatterns = {
                "executeQuery\\(\\s*\"[^\"]*\\s*\\+\\s*\\w+",
                "executeUpdate\\(\\s*\"[^\"]*\\s*\\+\\s*\\w+",
                "prepareStatement\\(\\s*\"[^\"]*\\s*\\+\\s*\\w+"
        };
        for (String sqlPattern : sqlInjectionPatterns) {
            Matcher m = p(sqlPattern).matcher(sourceCode);
            while (m.find()) {
                int lineNum = lineNumberAt(m.start());
                if (isInCommentOrString(m.start())) {
                    continue;
                }
                add("security", "sql_injection", lineNum, RiskLevel.CRITICAL, m.group(),
                        "Potential SQL injection vulnerability - string concatenation in SQL query");
            }
        }
    }

    // ------------------------------------------------------------------
    // 20. failure_scenarios.py:957 _detect_string_index_bounds_bugs
    // ------------------------------------------------------------------
    private void detectStringIndexBoundsBugs() {
        Matcher charAt = p("(\\w+)\\.charAt\\(\\s*([^)]+)\\s*\\)").matcher(sourceCode);
        while (charAt.find()) {
            String stringVar = charAt.group(1);
            String indexExpr = charAt.group(2);
            int lineNum = lineNumberAt(charAt.start());
            if (isInCommentOrString(charAt.start())) {
                continue;
            }
            if (pythonIsDigit(indexExpr) || indexExpr.contains("-") || indexExpr.contains("+")) {
                String context = context(lineNum, 5);
                if (!context.contains(stringVar + ".length()")) {
                    add("string_index_bounds", null, lineNum, RiskLevel.HIGH,
                            stringVar + ".charAt(" + indexExpr + ")",
                            "String charAt() without proper length check, "
                                    + "potential StringIndexOutOfBoundsException");
                }
            }
        }
        Matcher substring = p("(\\w+)\\.substring\\(\\s*([^,)]+)(?:\\s*,\\s*([^)]+))?\\s*\\)")
                .matcher(sourceCode);
        while (substring.find()) {
            String stringVar = substring.group(1);
            String startIdx = substring.group(2);
            String endIdx = substring.group(3); // null for single-arg substring
            int lineNum = lineNumberAt(substring.start());
            if (isInCommentOrString(substring.start())) {
                continue;
            }
            boolean risky = pythonIsDigit(startIdx) || startIdx.contains("-") || startIdx.contains("+")
                    || (endIdx != null
                        && (pythonIsDigit(endIdx) || endIdx.contains("-") || endIdx.contains("+")));
            if (risky) {
                String context = context(lineNum, 5);
                if (!context.contains(stringVar + ".length()")) {
                    add("string_index_bounds", null, lineNum, RiskLevel.HIGH,
                            stringVar + ".substring(" + startIdx
                                    + (endIdx != null ? ", " + endIdx : "") + ")",
                            "String substring() without proper length check, "
                                    + "potential StringIndexOutOfBoundsException");
                }
            }
        }
        Matcher indexAccess = p("(\\w+)\\s*\\[\\s*([^\\]]+)\\s*\\]").matcher(sourceCode);
        while (indexAccess.find()) {
            String varName = indexAccess.group(1);
            String indexExpr = indexAccess.group(2);
            int lineNum = lineNumberAt(indexAccess.start());
            if (isInCommentOrString(indexAccess.start())) {
                continue;
            }
            String context = context(lineNum, 3);
            if (context.contains("String") && context.contains("length()")) {
                if (pythonIsDigit(indexExpr) || indexExpr.contains("-") || indexExpr.contains("+")) {
                    add("string_index_bounds", null, lineNum, RiskLevel.HIGH,
                            varName + "[" + indexExpr + "]",
                            "Potential string index access without proper bounds check");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 21. failure_scenarios.py:1038 _detect_array_index_bounds_bugs
    // ------------------------------------------------------------------
    private void detectArrayIndexBoundsBugs() {
        Set<String> checkedArrays = new HashSet<>();
        // Python iterates all_arrays (a set) in arbitrary order; we pin insertion order
        // (LinkedHashSet) for reproducibility, per the contract's ordering guidance (O-series).
        Set<String> allArrays = new LinkedHashSet<>();

        Matcher lengthCheck = p("(\\w+)\\.length").matcher(sourceCode);
        while (lengthCheck.find()) {
            checkedArrays.add(lengthCheck.group(1));
        }
        Matcher sizeCheck = p("(\\w+)\\.size\\(\\)").matcher(sourceCode);
        while (sizeCheck.find()) {
            checkedArrays.add(sizeCheck.group(1));
        }

        Matcher access = p("(\\w+)\\s*\\[\\s*([^\\]]+)\\s*\\]").matcher(sourceCode);
        while (access.find()) {
            String arrayName = access.group(1);
            String indexExpr = access.group(2);
            int lineNum = lineNumberAt(access.start());
            if (isInCommentOrString(access.start())) {
                continue;
            }
            allArrays.add(arrayName);
            // 1. Fixed indices without bounds checking
            if (pythonIsDigit(indexExpr) && !checkedArrays.contains(arrayName)) {
                String context = context(lineNum, 3);
                if (p("\\[\\]").matcher(context).find()
                        || p("new\\s+\\w+\\s*\\[").matcher(context).find()) {
                    add("array_index_bounds", null, lineNum, RiskLevel.MEDIUM,
                            arrayName + "[" + indexExpr + "]",
                            "Array access with constant index " + indexExpr
                                    + " without length check");
                }
            }
            // 2. Complex index expressions without bounds checking
            if ((indexExpr.contains("+") || indexExpr.contains("-") || indexExpr.contains("*"))
                    && !checkedArrays.contains(arrayName)) {
                add("array_index_bounds", null, lineNum, RiskLevel.HIGH,
                        arrayName + "[" + indexExpr + "]",
                        "Array access with complex index " + indexExpr
                                + " without length validation");
            }
            // 3. Variable index without bounds checking (re.match ^\w+$ -> full match)
            if (p("^\\w+$").matcher(indexExpr).find() && !checkedArrays.contains(arrayName)) {
                String context = context(lineNum, 5);
                // indexExpr matched ^\w+$, so raw interpolation is safe here.
                if (!p("if\\s*\\([^)]*" + indexExpr + "[^)]*(?:length|size)")
                        .matcher(context).find()) {
                    add("array_index_bounds", null, lineNum, RiskLevel.MEDIUM,
                            arrayName + "[" + indexExpr + "]",
                            "Array access with variable index " + indexExpr + " without validation");
                }
            }
            // 4. Potential negative indices (Python uses re.escape -> Pattern.quote)
            if (indexExpr.contains("-")
                    && !p("if\\s*\\([^)]*" + Pattern.quote(indexExpr) + "\\s*>=\\s*0")
                            .matcher(context(lineNum, 5)).find()) {
                add("array_index_bounds", null, lineNum, RiskLevel.HIGH,
                        arrayName + "[" + indexExpr + "]",
                        "Array access with potentially negative index " + indexExpr);
            }
            // 5. Loop patterns that might cause off-by-one errors
            if (context(lineNum, 5).contains(indexExpr)
                    && p("for\\s*\\([^;]*;\\s*" + Pattern.quote(indexExpr) + "\\s*<=")
                            .matcher(context(lineNum, 5)).find()) {
                add("array_index_bounds", "off_by_one", lineNum, RiskLevel.HIGH,
                        arrayName + "[" + indexExpr + "]",
                        "Potential off-by-one error in array access with loop using <= condition");
            }
        }

        Matcher multidim = p("(\\w+)\\s*\\[\\s*([^\\]]+)\\s*\\]\\s*\\[\\s*([^\\]]+)\\s*\\]")
                .matcher(sourceCode);
        while (multidim.find()) {
            String arrayName = multidim.group(1);
            String firstIndex = multidim.group(2);
            String secondIndex = multidim.group(3);
            int lineNum = lineNumberAt(multidim.start());
            if (isInCommentOrString(multidim.start())) {
                continue;
            }
            String context = context(lineNum, 5);
            // Contract X5: Python interpolates the RAW index fragments (failure_scenarios.py
            // 1151-1152). A malformed fragment aborts this detector via the detect() exception
            // boundary; java.util.regex throws on MORE fragments than Python re (e.g. a stray
            // '{', which Python tolerates as a literal) — accepted engine divergence.
            boolean hasFirstDimCheck = p("if\\s*\\([^)]*" + firstIndex + "[^)]*"
                    + arrayName + "\\.length").matcher(context).find();
            boolean hasSecondDimCheck = p("if\\s*\\([^)]*" + secondIndex + "[^)]*"
                    + arrayName + "\\s*\\[.*\\]\\.length").matcher(context).find();
            if (!hasFirstDimCheck || !hasSecondDimCheck) {
                add("array_index_bounds", "multidimensional", lineNum, RiskLevel.HIGH,
                        arrayName + "[" + firstIndex + "][" + secondIndex + "]",
                        "Multi-dimensional array access without complete bounds checking");
            }
        }

        for (String arrayName : allArrays) {
            // arrayName came from a (\w+) group, so raw interpolation is safe here
            // (mirrors the f-string at failure_scenarios.py:1168).
            Matcher loop = p("for\\s*\\(\\s*(?:int|Integer)\\s+(\\w+)\\s*=\\s*(\\d+)\\s*;\\s*\\1\\s*(?:<|<=|>|>=)\\s*(?:"
                    + arrayName + "\\.length|[^;]+)\\s*;\\s*\\1\\s*(?:\\+\\+|--|\\+=|-=)")
                    .matcher(sourceCode);
            while (loop.find()) {
                String startVal = loop.group(2);
                int lineNum = lineNumberAt(loop.start());
                String loopContext = context(lineNum, 10);
                if (!loopContext.contains("length-1") && !loopContext.contains(".length-1")) {
                    if (loopContext.contains("<=") && loopContext.contains(".length")) {
                        add("array_index_bounds", "off_by_one_loop", lineNum, RiskLevel.HIGH,
                                loop.group(),
                                "Loop using <= with array length may cause off-by-one error");
                    }
                }
                // startVal came from a (\d+) group, so raw interpolation is safe here.
                if (!startVal.equals("0")
                        && !p("if\\s*\\([^)]*" + startVal + "[^)]*>=\\s*0")
                                .matcher(loopContext).find()) {
                    add("array_index_bounds", "non_zero_start", lineNum, RiskLevel.MEDIUM,
                            loop.group(),
                            "Loop starts at non-zero index " + startVal + " without validation");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers (failure_scenarios.py:1306-1358)
    // ------------------------------------------------------------------

    /** Python _get_line_number: newline count before charPos, 1-based. */
    private int lineNumberAt(int charPos) {
        int count = 0;
        for (int i = 0; i < charPos; i++) {
            if (sourceCode.charAt(i) == '\n') {
                count++;
            }
        }
        return count + 1;
    }

    /** Python _get_context: lines[max(0, n-r-1) : min(len, n+r)] joined with '\n'. */
    private String context(int lineNum, int radius) {
        int start = Math.max(0, lineNum - radius - 1);
        int end = Math.min(lines.length, lineNum + radius);
        if (start >= end) {
            return "";
        }
        return String.join("\n", Arrays.asList(lines).subList(start, end));
    }

    /**
     * Python _is_in_comment_or_string: crude single-line heuristic — '//' anywhere before
     * the position on its line, or an odd number of '"' before it. Deliberately does NOT
     * handle block comments or escaped quotes (kept verbatim; detectors are EXPECTED to
     * fire on commented-out code the heuristic misses).
     */
    private boolean isInCommentOrString(int charPos) {
        int lineStart = charPos == 0 ? 0 : sourceCode.lastIndexOf('\n', charPos - 1) + 1;
        String line = sourceCode.substring(lineStart, charPos);
        if (line.contains("//")) {
            return true;
        }
        return countOccurrences(line, "\"") % 2 == 1;
    }

    /** Python _is_likely_parameter (varName always comes from a (\w+) group). */
    private boolean isLikelyParameter(String varName) {
        return p("\\w+\\s+\\w+\\s*\\([^)]*\\b" + varName + "\\b[^)]*\\)").matcher(sourceCode).find();
    }

    /** Python _is_property_access (name always comes from a (\w+) group). */
    private boolean isPropertyAccess(String name) {
        return !p("\\b" + name + "\\s*\\(").matcher(sourceCode).find();
    }

    /**
     * Python _similarity: positional character match ratio, matched / longer.
     * Contract N9: must be floating-point division or copy_paste never fires.
     */
    private static double similarity(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return 0.0;
        }
        int shorter = Math.min(a.length(), b.length());
        int longer = Math.max(a.length(), b.length());
        int matched = 0;
        for (int i = 0; i < shorter; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                matched++;
            }
        }
        return (double) matched / longer;
    }

    /** Python str.count / brace scan helper: non-overlapping substring count. */
    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** Python brace-depth scan (failure_scenarios.py:448-455): end index after depth 0. */
    private int matchingBraceEnd(int openBraceIndex) {
        int depth = 1;
        int end = openBraceIndex + 1;
        while (depth > 0 && end < sourceCode.length()) {
            char c = sourceCode.charAt(end);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            end++;
        }
        return end;
    }

    /**
     * Python str.isdigit() (S7): non-empty, all chars digits. Character.isDigit matches
     * Python for ASCII and standard Unicode decimal digits; Python additionally accepts
     * superscripts/other digit-property chars — documented divergence, irrelevant to
     * Java source input.
     */
    private static boolean pythonIsDigit(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Python str.isalpha(): non-empty, all chars letters (Unicode). */
    private static boolean pythonIsAlpha(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Python str.isalnum(): non-empty, all chars alphanumeric. Character.isLetterOrDigit
     * matches Python for ASCII; Python also accepts numeric-property chars (e.g. Roman
     * numeral letters) — documented divergence (S7).
     */
    private static boolean pythonIsAlnum(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
