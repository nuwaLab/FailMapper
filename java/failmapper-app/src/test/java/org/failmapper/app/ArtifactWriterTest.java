package org.failmapper.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.failmapper.search.FaMcts;
import org.failmapper.search.PotentialBug;
import org.failmapper.search.VerifiedBugMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactWriterTest {

    @TempDir
    Path outputDir;

    @Test
    void writesAllArtifacts() throws Exception {
        PotentialBug bug = new PotentialBug();
        bug.testMethod = "testAdd";
        bug.bugType = "assertion_failure";
        bug.error = "expected: <5> but was: <4>";
        bug.bugSignature = "testAdd:0cd5c944d95f";
        bug.foundInIteration = 2;

        VerifiedBugMethod verified = new VerifiedBugMethod();
        verified.methodName = "testAdd";
        verified.bugType = "assertion_failure";
        verified.verified = true;
        verified.isRealBug = true;
        verified.verificationConfidence = 0.8;
        verified.verificationReasoning = "scripted";

        FaMcts.SearchResult result = new FaMcts.SearchResult(
                "public class CalcTest { }",
                61.54,
                0.39,
                3,
                List.of(new FaMcts.IterationRecord(
                        1, "exception_test", 0.39, 61.54, 0, 2, 3, 1, 0.39)),
                List.of(bug),
                List.of(verified),
                1,
                0);

        Path bestTest = new ArtifactWriter().write(
                outputDir, "com.acme.Calc", 42L, "deepseek-v4-pro", result);

        assertThat(bestTest).exists();
        assertThat(Files.readString(bestTest)).isEqualTo("public class CalcTest { }");

        ObjectMapper mapper = new ObjectMapper();

        JsonNode log = mapper.readTree(outputDir.resolve("iteration_log.json").toFile());
        assertThat(log.isArray()).isTrue();
        assertThat(log.get(0).get("iteration").asInt()).isEqualTo(1);
        assertThat(log.get(0).get("actionType").asText()).isEqualTo("exception_test");
        assertThat(log.get(0).get("reward").asDouble()).isEqualTo(0.39);
        assertThat(log.get(0).get("coverage").asDouble()).isEqualTo(61.54);

        JsonNode bugs = mapper.readTree(outputDir.resolve("verified_bugs.json").toFile());
        assertThat(bugs.get(0).get("methodName").asText()).isEqualTo("testAdd");
        assertThat(bugs.get(0).get("isRealBug").asBoolean()).isTrue();

        JsonNode potential = mapper.readTree(outputDir.resolve("potential_bugs.json").toFile());
        assertThat(potential.get(0).get("bugSignature").asText())
                .isEqualTo("testAdd:0cd5c944d95f");

        JsonNode summary = mapper.readTree(outputDir.resolve("summary.json").toFile());
        assertThat(summary.get("target_class").asText()).isEqualTo("com.acme.Calc");
        assertThat(summary.get("seed").asLong()).isEqualTo(42L);
        assertThat(summary.get("model").asText()).isEqualTo("deepseek-v4-pro");
        assertThat(summary.get("real_bugs").asInt()).isEqualTo(1);
        assertThat(summary.get("best_coverage").asDouble()).isEqualTo(61.54);
    }
}
