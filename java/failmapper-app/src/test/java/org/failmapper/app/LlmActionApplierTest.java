package org.failmapper.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.failmapper.analysis.SymbolApiRetriever;
import org.failmapper.llm.LlmClient;
import org.failmapper.search.Evaluator;
import org.failmapper.search.FaTestState;
import org.failmapper.search.SearchAction;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmActionApplier} (D6) with a FAKE {@link LlmClient} — no network. Covers the
 * prompt routing, carry-forward + coverage restore, the null paths, and the I16
 * missing-symbol API section (resolved against the REAL junit jar on this test's own
 * classpath).
 */
class LlmActionApplierTest {

    private static final String PARENT_CODE = """
            public class CalcTest {
                @Test
                public void testOld() { }
            }
            """;

    private static final String CHILD_CODE = """
            public class CalcTest {
                @Test
                public void testNew() { }
            }
            """;

    /** Fake client: records prompts, returns a scripted reply (or throws). */
    private static final class FakeClient implements LlmClient {
        final List<String> prompts = new ArrayList<>();
        String reply;
        boolean fail = false;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            prompts.add(userPrompt);
            if (fail) {
                throw new LlmException("scripted failure");
            }
            return reply;
        }
    }

    /** Fake evaluator: records states and stamps a scripted coverage. */
    private static final class FakeEvaluator implements Evaluator {
        final List<FaTestState> evaluated = new ArrayList<>();
        double coverageToSet = 0.0;

        @Override
        public void evaluate(FaTestState state) {
            evaluated.add(state);
            state.coverage = coverageToSet;
            state.executed = true;
        }
    }

    private static LlmActionApplier applier(FakeClient client, FakeEvaluator evaluator,
                                            List<String> classpath) {
        return new LlmActionApplier(client, evaluator, "Calc", "public class Calc {}",
                null, new SymbolApiRetriever(), classpath, null, null);
    }

    private static String junitApiJar() {
        try {
            return Path.of(Test.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void appliesActionWithCarryForwardAndCoverageRestore() {
        FakeClient client = new FakeClient();
        client.reply = "Here is the test:\n```java\n" + CHILD_CODE + "```\n";
        FakeEvaluator evaluator = new FakeEvaluator();
        evaluator.coverageToSet = 0.0; // failed run: evaluation leaves coverage 0

        FaTestState parent = new FaTestState(PARENT_CODE, null, null);
        parent.coverage = 40.0;
        parent.coveredFailures.add("off_by_one_12");

        SearchAction action = new SearchAction("exception_test",
                Map.of("strategy", "exception_handling", "description", "d"));

        FaTestState child = applier(client, evaluator, List.of()).apply(action, parent);

        assertThat(child).isNotNull();
        assertThat(child.testCode).isEqualTo(CHILD_CODE);
        assertThat(evaluator.evaluated).containsExactly(child);
        // D6 carry-forward (fa_mcts.py:2715-2755).
        assertThat(child.metadataAction).isEqualTo(action);
        assertThat(child.parentCoverage).isEqualTo(40.0);
        assertThat(child.coveredFailures).containsExactly("off_by_one_12");
        // Post-evaluate restore (fa_mcts.py:2772-2775): 0 -> parent's 40.
        assertThat(child.coverage).isEqualTo(40.0);
        // P2 prompt was used (base template marker) and carried the parent's code.
        assertThat(client.prompts).hasSize(1);
        assertThat(client.prompts.get(0)).contains("CRITICAL REQUIREMENTS - READ CAREFULLY:");
        assertThat(client.prompts.get(0)).contains("testOld");
    }

    @Test
    void extractionFailureReturnsNull() {
        FakeClient client = new FakeClient();
        client.reply = "Sorry, I cannot help with that."; // no parseable Java anywhere
        FakeEvaluator evaluator = new FakeEvaluator();

        FaTestState parent = new FaTestState(PARENT_CODE, null, null);
        FaTestState child = applier(client, evaluator, List.of())
                .apply(SearchAction.of("general_exploration"), parent);

        assertThat(child).isNull();
        assertThat(evaluator.evaluated).isEmpty();
    }

    @Test
    void clientFailureReturnsNull() {
        FakeClient client = new FakeClient();
        client.fail = true;
        FakeEvaluator evaluator = new FakeEvaluator();

        FaTestState parent = new FaTestState(PARENT_CODE, null, null);
        FaTestState child = applier(client, evaluator, List.of())
                .apply(SearchAction.of("general_exploration"), parent);

        assertThat(child).isNull();
    }

    @Test
    void nullParentReturnsNull() {
        FakeClient client = new FakeClient();
        assertThat(applier(client, new FakeEvaluator(), List.of())
                .apply(SearchAction.of("x"), null)).isNull();
        assertThat(client.prompts).isEmpty();
    }

    @Test
    void fixPromptCarriesErrorsAndI16ApiSection() {
        FakeClient client = new FakeClient();
        client.reply = "```java\n" + CHILD_CODE + "```";
        FakeEvaluator evaluator = new FakeEvaluator();

        FaTestState parent = new FaTestState(PARENT_CODE, null, null);
        parent.compilationErrors = new ArrayList<>(List.of(
                "cannot find symbol\n  symbol:   class Assertions\n  location: class CalcTest"));

        SearchAction action = new SearchAction("fix_compilation_errors", Map.of(
                "description", "Fix compilation errors in test code",
                "errors", List.copyOf(parent.compilationErrors),
                "attempt", 1,
                "path_signature", ""));

        LlmActionApplier applier = applier(client, evaluator, List.of(junitApiJar()));
        FaTestState child = applier.apply(action, parent);

        assertThat(child).isNotNull();
        String prompt = client.prompts.get(0);
        // P2 fix branch (fa_mcts.py:2934-2970).
        assertThat(prompt).contains("IMPORTANT: The current test code has COMPILATION ERRORS");
        assertThat(prompt).contains("1. ERROR: cannot find symbol");
        // I16: the REAL declared API of the missing symbol, from the classpath
        // (member lines are sorted and capped at 40 with an overflow marker).
        assertThat(prompt).contains("REAL API OF MISSING SYMBOL");
        assertThat(prompt).contains("class org.junit.jupiter.api.Assertions");
        assertThat(prompt).contains("method static void assertAll(");
        assertThat(prompt).contains(" more members");
    }

    @Test
    void i16ResolvesFailedImportsFromPackageErrors() {
        FakeClient client = new FakeClient();
        FakeEvaluator evaluator = new FakeEvaluator();
        LlmActionApplier applier = applier(client, evaluator, List.of(junitApiJar()));

        String testCode = "import org.junit.jupiter.api.Assertions;\nclass T {}";
        String section = applier.missingSymbolApiSection(
                List.of("package org.junit.jupiter.api does not exist"), testCode);

        assertThat(section).contains("REAL API OF MISSING SYMBOL");
        assertThat(section).contains("class org.junit.jupiter.api.Assertions");
    }

    @Test
    void i16SectionEmptyWhenNothingResolvable() {
        LlmActionApplier applier = applier(new FakeClient(), new FakeEvaluator(), List.of());
        assertThat(applier.missingSymbolApiSection(
                List.of("cannot find symbol\n  symbol:   class NoSuchClazz"), "")).isEmpty();
        assertThat(applier.missingSymbolApiSection(List.of(), "")).isEmpty();
        assertThat(applier.missingSymbolApiSection(null, "")).isEmpty();
    }

    @Test
    void businessLogicActionRoutesToP1Prompt() {
        FakeClient client = new FakeClient();
        client.reply = "```java\n" + CHILD_CODE + "```";
        FakeEvaluator evaluator = new FakeEvaluator();

        FaTestState parent = new FaTestState(PARENT_CODE, null, null);
        SearchAction action = new SearchAction("business_logic_test", Map.of(
                "issue_type", "calculation",
                "method", "add",
                "description", "Test for potential business logic issue: wrong operator",
                "confidence", 0.8,
                "business_logic", Boolean.TRUE));

        FaTestState child = applier(client, evaluator, List.of()).apply(action, parent);

        assertThat(child).isNotNull();
        String prompt = client.prompts.get(0);
        // P1's header lacks the "- READ CAREFULLY" suffix of P2.
        assertThat(prompt).contains("CRITICAL REQUIREMENTS:");
        assertThat(prompt).doesNotContain("CRITICAL REQUIREMENTS - READ CAREFULLY:");
    }
}
