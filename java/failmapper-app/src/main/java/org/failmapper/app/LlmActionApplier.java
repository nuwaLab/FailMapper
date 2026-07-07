package org.failmapper.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.failmapper.analysis.SymbolApiRetriever;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.llm.CodeExtractor;
import org.failmapper.llm.LlmClient;
import org.failmapper.llm.prompt.ActionPromptBuilder;
import org.failmapper.llm.prompt.BusinessLogicPromptBuilder;
import org.failmapper.search.BusinessLogicIssue;
import org.failmapper.search.Evaluator;
import org.failmapper.search.FaMcts;
import org.failmapper.search.FaTestState;
import org.failmapper.search.SearchAction;

/**
 * D6 — the LLM half of {@code _apply_action} ({@code fa_mcts.py:2659-2790}):
 * <ol>
 *   <li>prompt per action type: {@code business_logic_test} →
 *       {@link BusinessLogicPromptBuilder} (P1); everything else →
 *       {@link ActionPromptBuilder} (P2, whose {@code fix_compilation_errors} branch
 *       renders the analyzed error list);</li>
 *   <li>I16 (registered improvement): for fix actions whose errors include
 *       {@code cannot find symbol} / {@code package ... does not exist}, the missing
 *       symbols are resolved against the build model's classpath
 *       ({@link SymbolApiRetriever}) and a {@code REAL API OF MISSING SYMBOL} section
 *       is appended to the prompt;</li>
 *   <li>LLM call → {@link CodeExtractor}; extraction failure returns null (Python
 *       {@code return None}, {@code fa_mcts.py:2707-2709});</li>
 *   <li>child state with the D6 carry-forward
 *       ({@link FaTestState#carryForwardFrom}: metadata, business-logic analysis,
 *       previous compilation errors, coverage and covered-set copies);</li>
 *   <li>evaluation via the injected {@link Evaluator} (the {@link TestPipeline}
 *       composition in production), then the post-evaluate coverage restore
 *       ({@code fa_mcts.py:2772-2775}: a non-positive coverage falls back to the
 *       parent's).</li>
 * </ol>
 * Any exception maps to null, like the Python catch-all ({@code fa_mcts.py:2787-2790}).
 * The failed-fix-path marking of {@code fa_mcts.py:2762-2768} lives in the
 * {@link FaMcts} orchestrator (it owns the tracker).
 */
public final class LlmActionApplier implements FaMcts.ActionApplier {

    /** Extracts `symbol: class Foo` names from javac cannot-find-symbol diagnostics. */
    private static final Pattern MISSING_SYMBOL = Pattern.compile(
            "symbol:\\s+(?:class|interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** `package org.foo does not exist` diagnostics (failed imports). */
    private static final Pattern MISSING_PACKAGE = Pattern.compile(
            "package\\s+([\\w.]+)\\s+does not exist");

    /** Max distinct symbols resolved per fix prompt (prompt size discipline). */
    private static final int MAX_SYMBOL_LOOKUPS = 3;

    private final LlmClient client;
    private final CodeExtractor codeExtractor = new CodeExtractor();
    private final Evaluator evaluator;
    private final ActionPromptBuilder actionPromptBuilder;
    private final BusinessLogicPromptBuilder businessLogicPromptBuilder;
    private final SymbolApiRetriever symbolApiRetriever;
    private final List<String> classpath;
    private final FailureModel fModel;
    private final List<FailureScenario> failures;

    /**
     * @param client             LLM transport
     * @param evaluator          the evaluate stage ({@link TestPipeline} in production)
     * @param className          {@code self.class_name}
     * @param sourceCode         {@code self.source_code}
     * @param testPrompt         the initial prompt content (dependency-context source
     *                           for P1/P2); nullable
     * @param symbolApiRetriever I16 resolver; nullable disables the retrieval
     * @param classpath          classpath for I16 symbol resolution
     * @param fModel             failure model handed to child states
     * @param failures           failure scenarios handed to child states
     */
    public LlmActionApplier(LlmClient client,
                            Evaluator evaluator,
                            String className,
                            String sourceCode,
                            String testPrompt,
                            SymbolApiRetriever symbolApiRetriever,
                            List<String> classpath,
                            FailureModel fModel,
                            List<FailureScenario> failures) {
        this.client = client;
        this.evaluator = evaluator;
        this.actionPromptBuilder = new ActionPromptBuilder(className, sourceCode, testPrompt);
        this.businessLogicPromptBuilder = new BusinessLogicPromptBuilder(className, sourceCode, testPrompt);
        this.symbolApiRetriever = symbolApiRetriever;
        this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
        this.fModel = fModel;
        this.failures = failures;
    }

    @Override
    public FaTestState apply(SearchAction action, FaTestState parentState) {
        if (parentState == null) {
            return null; // fa_mcts.py:2670-2671
        }
        try {
            String prompt = buildPrompt(action, parentState);

            String reply = client.complete(null, prompt);
            Optional<String> extracted = codeExtractor.extract(reply);
            if (extracted.isEmpty()) {
                return null; // fa_mcts.py:2707-2709
            }

            FaTestState newState = new FaTestState(extracted.get(), fModel, failures);
            newState.carryForwardFrom(parentState, action); // fa_mcts.py:2715-2755

            evaluator.evaluate(newState); // fa_mcts.py:2757-2759 (batch mode: no immediate verify)

            // fa_mcts.py:2772-2775 — never lose coverage after evaluation.
            if (newState.coverage <= 0) {
                newState.coverage = parentState.coverage;
            }
            return newState;
        } catch (RuntimeException e) {
            return null; // fa_mcts.py:2787-2790
        }
    }

    /** Prompt routing of D6 (fa_mcts.py:2685-2691) + the I16 fix-prompt augmentation. */
    String buildPrompt(SearchAction action, FaTestState parentState) {
        String actionType = action.type() == null ? "unknown" : action.type();
        Map<String, Object> actionMap = toActionMap(action);

        String prompt;
        if ("business_logic_test".equals(actionType)) {
            prompt = businessLogicPromptBuilder.build(
                    parentState.coverage,
                    parentState.testCode,
                    potentialBugMaps(parentState),
                    actionMap);
        } else {
            prompt = actionPromptBuilder.build(
                    parentState.coverage, parentState.testCode, actionMap);
        }

        if ("fix_compilation_errors".equals(actionType) && symbolApiRetriever != null) {
            String apiSection = missingSymbolApiSection(
                    parentState.compilationErrors, parentState.testCode);
            if (!apiSection.isEmpty()) {
                prompt = prompt + apiSection;
            }
        }
        return prompt;
    }

    /**
     * I16 — resolve missing symbols from compile diagnostics against the classpath and
     * render the {@code REAL API OF MISSING SYMBOL} section; empty when nothing
     * resolvable was found.
     */
    String missingSymbolApiSection(List<String> compilationErrors, String testCode) {
        if (compilationErrors == null || compilationErrors.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (String error : compilationErrors) {
            if (error == null) {
                continue;
            }
            if (error.contains("cannot find symbol")) {
                Matcher m = MISSING_SYMBOL.matcher(error);
                while (m.find()) {
                    symbols.add(m.group(1));
                }
            }
            Matcher pkg = MISSING_PACKAGE.matcher(error);
            while (pkg.find()) {
                // A failed import: derive the imported type(s) from the test code.
                String packageName = pkg.group(1);
                Matcher imports = Pattern
                        .compile("import\\s+" + Pattern.quote(packageName) + "\\.(\\w+)\\s*;")
                        .matcher(testCode == null ? "" : testCode);
                while (imports.find()) {
                    symbols.add(packageName + "." + imports.group(1));
                }
            }
        }

        StringBuilder section = new StringBuilder();
        int lookups = 0;
        for (String symbol : symbols) {
            if (lookups >= MAX_SYMBOL_LOOKUPS) {
                break;
            }
            lookups++;
            List<String> api = symbolApiRetriever.lookup(symbol, classpath);
            if (api.isEmpty()) {
                continue;
            }
            if (section.length() == 0) {
                section.append("\n\nREAL API OF MISSING SYMBOL (resolved from the project"
                        + " classpath — use EXACTLY these declared signatures):\n");
            }
            for (String line : api) {
                section.append(line).append('\n');
            }
        }
        return section.toString();
    }

    /** SearchAction → the Python action dict shape the prompt builders consume. */
    private static Map<String, Object> toActionMap(SearchAction action) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (action.type() != null) {
            map.put("type", action.type());
        }
        map.putAll(action.attributes());
        return map;
    }

    /**
     * {@code state.business_logic_analysis['potential_bugs']} as the dict list P1 reads
     * ({@code fa_mcts.py:2809-2815}); null when the state carries no analysis (Python
     * hasattr false).
     */
    private static List<Map<String, Object>> potentialBugMaps(FaTestState state) {
        if (state.businessLogicIssues.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> bugs = new ArrayList<>();
        for (BusinessLogicIssue issue : state.businessLogicIssues) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("method", issue.method());
            map.put("type", issue.type());
            map.put("description", issue.description());
            map.put("confidence", issue.confidenceOrZero());
            bugs.add(map);
        }
        return bugs;
    }
}
