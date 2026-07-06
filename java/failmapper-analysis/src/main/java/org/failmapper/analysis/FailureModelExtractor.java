package org.failmapper.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.DecisionPoint;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.LogicalOperation;
import org.failmapper.core.model.MethodComplexity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Java-native port of the Python {@code extractor.py} {@code Extractor} class: builds the
 * failure model ("f_model") of a class under test from its source code.
 *
 * <p>Semantics preserved from the Python AST path ({@code _extract_boundary_conditions},
 * {@code _analyze_decision_points}, {@code _compute_method_complexity}):
 * <ul>
 *   <li>boundary-condition {@code type} vocabulary is kept verbatim ({@code if_condition},
 *       {@code while_loop}, {@code for_loop}, {@code for_each_loop}, {@code do_while_loop},
 *       {@code switch_statement}) because strategy routing and coverage counting key on
 *       these exact strings (contract D2/D3, F10);</li>
 *   <li>decision points carry only the kinds the Python AST path emitted: {@code switch_case}
 *       (during boundary extraction), then {@code if}, then {@code while} — classic/enhanced
 *       for loops, do-while loops and the switch statement itself are deliberately NOT
 *       decision points, so per-method decision counts feeding F11 match extractor.py;</li>
 *   <li>logical operations record only the TOP-LEVEL operator of an if/while condition:
 *       {@code &&}/{@code ||} for both, plus relational operators
 *       ({@code > >= < <= == !=}) for if-conditions only, exactly as extractor.py;</li>
 *   <li>method complexity uses contract F11 via {@link MethodComplexity#of}
 *       (cyclomatic = decisions + 1; cognitive = decisions + logicalOps + 2 * nested) and is
 *       returned as a {@link LinkedHashMap} in source declaration order (contract O5);</li>
 *   <li>the nested count reproduces extractor.py's line-proximity heuristic (a single global
 *       stack over if-statements with a 10-line staleness window), NOT true AST nesting.</li>
 * </ul>
 *
 * <p>Deliberate deviations: parsing uses JavaParser at language level Java 21 (javalang topped
 * out at Java 8), method attribution uses the true enclosing method/constructor via AST
 * ancestry (contract improvement register I2), and on parse failure the null-object
 * {@link FailureModel#empty} is returned instead of Python's regex fallback (failmapper.py
 * null-object semantics). The Python side-products not represented in {@link FailureModel}
 * (control-flow paths, data dependencies) are not ported.
 */
public final class FailureModelExtractor {

    /** Operators extractor.py treats as logical connectors (if and while conditions). */
    private static final Set<BinaryExpr.Operator> LOGICAL_OPERATORS =
            Set.of(BinaryExpr.Operator.AND, BinaryExpr.Operator.OR);

    /** Operators extractor.py treats as comparisons (if conditions only). */
    private static final Set<BinaryExpr.Operator> COMPARISON_OPERATORS = Set.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS,
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS);

    /** Nested-if staleness window of extractor.py's decision stack (lines). */
    private static final int NESTED_IF_LINE_WINDOW = 10;

    /**
     * Extracts the failure model of {@code classFqn} from {@code sourceCode}.
     * Never throws: on missing/blank source or any parse failure the null-object
     * {@link FailureModel#empty} is returned and the pipeline continues with it.
     */
    public FailureModel extract(String sourceCode, String classFqn) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return FailureModel.empty(classFqn);
        }
        try {
            JavaParser parser = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            ParseResult<CompilationUnit> result = parser.parse(sourceCode);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return FailureModel.empty(classFqn);
            }
            return buildModel(result.getResult().get(), classFqn);
        } catch (Exception e) {
            return FailureModel.empty(classFqn);
        }
    }

    private static FailureModel buildModel(CompilationUnit cu, String classFqn) {
        List<BoundaryCondition> boundaries = new ArrayList<>();
        List<LogicalOperation> operations = new ArrayList<>();
        List<DecisionPoint> ifPoints = new ArrayList<>();
        List<DecisionPoint> whilePoints = new ArrayList<>();
        List<DecisionPoint> switchCasePoints = new ArrayList<>();
        Map<String, Integer> nestedCountByMethod = new HashMap<>();

        // Pass order mirrors extractor.py._extract_boundary_conditions: the boundary list is
        // grouped by construct — ifs, whiles, fors, for-eaches, do-whiles, switches.

        // extractor.py nested heuristic: one GLOBAL stack of if-lines across the whole
        // compilation unit (not reset per method); entries more than NESTED_IF_LINE_WINDOW
        // lines above the current if are popped; the current if counts as nested when any
        // prior if survives. Only if-statements participate; whiles/loops never do.
        Deque<Integer> ifLineStack = new ArrayDeque<>();

        for (IfStmt stmt : cu.findAll(IfStmt.class)) {
            int line = lineOf(stmt);
            String method = enclosingCallableName(stmt);
            String condition = render(stmt.getCondition());
            boundaries.add(new BoundaryCondition(method, line, "if_condition", condition));
            topLevelOperator(stmt.getCondition(), true).ifPresent(operator ->
                    operations.add(new LogicalOperation(method, line, condition, List.of(operator))));
            ifPoints.add(new DecisionPoint(method, line, "if", condition));

            while (!ifLineStack.isEmpty() && ifLineStack.peek() < line - NESTED_IF_LINE_WINDOW) {
                ifLineStack.pop();
            }
            ifLineStack.push(line);
            if (ifLineStack.size() > 1) {
                nestedCountByMethod.merge(method, 1, Integer::sum);
            }
        }

        for (WhileStmt stmt : cu.findAll(WhileStmt.class)) {
            int line = lineOf(stmt);
            String method = enclosingCallableName(stmt);
            String condition = render(stmt.getCondition());
            boundaries.add(new BoundaryCondition(method, line, "while_loop", condition));
            topLevelOperator(stmt.getCondition(), false).ifPresent(operator ->
                    operations.add(new LogicalOperation(method, line, condition, List.of(operator))));
            whilePoints.add(new DecisionPoint(method, line, "while", condition));
        }

        for (ForStmt stmt : cu.findAll(ForStmt.class)) {
            boundaries.add(new BoundaryCondition(
                    enclosingCallableName(stmt), lineOf(stmt), "for_loop", renderForControl(stmt)));
        }

        for (ForEachStmt stmt : cu.findAll(ForEachStmt.class)) {
            boundaries.add(new BoundaryCondition(
                    enclosingCallableName(stmt), lineOf(stmt), "for_each_loop",
                    "for-each: " + render(stmt.getIterable())));
        }

        for (DoStmt stmt : cu.findAll(DoStmt.class)) {
            boundaries.add(new BoundaryCondition(
                    enclosingCallableName(stmt), lineOf(stmt), "do_while_loop",
                    render(stmt.getCondition())));
        }

        for (SwitchStmt stmt : cu.findAll(SwitchStmt.class)) {
            extractSwitch(stmt, stmt.getSelector(), stmt.getEntries(), boundaries, switchCasePoints);
        }
        // Switch expressions did not exist for javalang; the Java-21 grammar surfaces them
        // under the same "switch_statement"/"switch_case" vocabulary (input improvement).
        for (SwitchExpr expr : cu.findAll(SwitchExpr.class)) {
            extractSwitch(expr, expr.getSelector(), expr.getEntries(), boundaries, switchCasePoints);
        }

        // decision_points order mirrors extractor.py append order: switch cases first (added
        // during boundary extraction), then ifs, then whiles (_analyze_decision_points).
        List<DecisionPoint> decisionPoints = new ArrayList<>(switchCasePoints);
        decisionPoints.addAll(ifPoints);
        decisionPoints.addAll(whilePoints);

        return new FailureModel(
                classFqn,
                List.copyOf(boundaries),
                List.copyOf(operations),
                List.copyOf(decisionPoints),
                Collections.unmodifiableMap(
                        methodComplexity(cu, decisionPoints, operations, nestedCountByMethod)));
    }

    /**
     * Contract F11: cyclomatic = decisions + 1; cognitive = decisions + logicalOps + 2 * nested,
     * counted per attributed method name. Insertion order is source declaration order
     * (contract O5 — downstream target-method selection breaks complexity ties by this order,
     * hence the LinkedHashMap). As in extractor.py, only MethodDeclarations get a row
     * (constructors are attribution targets, not complexity rows) and overloads collapse into
     * one name-keyed entry aggregating all same-named declarations.
     */
    private static LinkedHashMap<String, MethodComplexity> methodComplexity(
            CompilationUnit cu,
            List<DecisionPoint> decisionPoints,
            List<LogicalOperation> operations,
            Map<String, Integer> nestedCountByMethod) {
        Map<String, Integer> decisionsByMethod = new HashMap<>();
        decisionPoints.forEach(point -> decisionsByMethod.merge(point.method(), 1, Integer::sum));
        Map<String, Integer> operationsByMethod = new HashMap<>();
        operations.forEach(operation -> operationsByMethod.merge(operation.method(), 1, Integer::sum));

        LinkedHashMap<String, MethodComplexity> complexity = new LinkedHashMap<>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            String name = method.getNameAsString();
            complexity.put(name, MethodComplexity.of(
                    decisionsByMethod.getOrDefault(name, 0),
                    operationsByMethod.getOrDefault(name, 0),
                    nestedCountByMethod.getOrDefault(name, 0)));
        }
        return complexity;
    }

    private static void extractSwitch(
            Node switchNode,
            Expression selector,
            NodeList<SwitchEntry> entries,
            List<BoundaryCondition> boundaries,
            List<DecisionPoint> switchCasePoints) {
        int line = lineOf(switchNode);
        String method = enclosingCallableName(switchNode);
        String selectorText = render(selector);
        boundaries.add(new BoundaryCondition(method, line, "switch_statement", selectorText));
        for (SwitchEntry entry : entries) {
            String caseValue = entry.getLabels().isEmpty()
                    ? "default"
                    : entry.getLabels().stream()
                            .map(FailureModelExtractor::render)
                            .collect(Collectors.joining(", "));
            switchCasePoints.add(new DecisionPoint(
                    method, lineOf(entry), "switch_case", selectorText + " == " + caseValue));
        }
    }

    /**
     * The operator of a condition whose top level is a binary logical ({@code &&}/{@code ||})
     * expression — or, for if-conditions only ({@code includeComparisons}), a relational one.
     * extractor.py inspects only the top-level BinaryOperation, never nested operands;
     * redundant enclosing parentheses are transparent (javalang built the expression tree
     * without paren nodes, so JavaParser's EnclosedExpr wrappers are unwrapped here).
     *
     * <p>Prefix unary operators are transparent too: javalang stores them as a
     * {@code prefix_operators} ATTRIBUTE on the operand node rather than as a node of their
     * own, so extractor.py's {@code isinstance(condition, BinaryOperation)} check sees
     * straight through negation — {@code !(a || b)} counts as a {@code ||} operation and
     * {@code !(a == b)} as a {@code ==} comparison (Layer B alignment: DefaultParser.java:526,
     * Parser.java:295). JavaParser's UnaryExpr wrappers must be unwrapped to match.
     */
    private static Optional<String> topLevelOperator(Expression condition, boolean includeComparisons) {
        Expression expression = condition;
        while (true) {
            if (expression instanceof EnclosedExpr enclosed) {
                expression = enclosed.getInner();
            } else if (expression instanceof UnaryExpr unary && unary.getOperator().isPrefix()) {
                expression = unary.getExpression();
            } else {
                break;
            }
        }
        if (expression instanceof BinaryExpr binary) {
            BinaryExpr.Operator operator = binary.getOperator();
            if (LOGICAL_OPERATORS.contains(operator)
                    || (includeComparisons && COMPARISON_OPERATORS.contains(operator))) {
                return Optional.of(operator.asString());
            }
        }
        return Optional.empty();
    }

    /**
     * The TRUE enclosing method/constructor of a node via AST ancestry — contract I2: fixes the
     * Python _find_containing_method bug that returned the first method with start_line <= line.
     */
    private static String enclosingCallableName(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                return method.getNameAsString();
            }
            if (current instanceof ConstructorDeclaration constructor) {
                // A JavaParser constructor's name is the class simple name.
                return constructor.getNameAsString();
            }
            current = current.getParentNode().orElse(null);
        }
        return "unknown"; // field initializer, static/instance initializer block (Python default)
    }

    /** 1-based start line from the JavaParser Range; 0 when absent (Python position default). */
    private static int lineOf(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(0);
    }

    /** Renders an expression as single-line Java source text (replaces javalang node reprs). */
    private static String render(Expression expression) {
        return expression.toString().replaceAll("\\s+", " ").trim();
    }

    /** Classic for-loop control rendered as "init; compare; update" (Python: str(node.control)). */
    private static String renderForControl(ForStmt stmt) {
        String init = stmt.getInitialization().stream()
                .map(FailureModelExtractor::render)
                .collect(Collectors.joining(", "));
        String compare = stmt.getCompare().map(FailureModelExtractor::render).orElse("");
        String update = stmt.getUpdate().stream()
                .map(FailureModelExtractor::render)
                .collect(Collectors.joining(", "));
        return (init + "; " + compare + "; " + update).trim();
    }
}
