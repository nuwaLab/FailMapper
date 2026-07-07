package org.failmapper.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.failmapper.search.FaMcts;

/**
 * Writes the run's artifacts under a caller-supplied OUTPUT directory — never the
 * target project tree (the runner enforces the separation before any write):
 * <ul>
 *   <li>{@code best_test.java} — the best test code found;</li>
 *   <li>{@code iteration_log.json} — per-iteration records (iteration, action, reward,
 *       coverage, bug/pattern/branch counters, node stats);</li>
 *   <li>{@code verified_bugs.json} — the batch-verification results;</li>
 *   <li>{@code potential_bugs.json} — the raw D7 collection (signatures, methods);</li>
 *   <li>{@code summary.json} — headline numbers for the run.</li>
 * </ul>
 * JSON is pretty-printed and key order follows insertion order (LinkedHashMap), giving
 * stable, diffable artifacts (contract S8 direction).
 */
public final class ArtifactWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * @return the path of the written best-test file
     */
    public Path write(Path outputDir, String targetFqn, long seed, String model,
                      FaMcts.SearchResult result) {
        try {
            Files.createDirectories(outputDir);

            Path bestTest = outputDir.resolve("best_test.java");
            Files.writeString(bestTest,
                    result.bestTestCode() == null ? "" : result.bestTestCode(),
                    StandardCharsets.UTF_8);

            mapper.writeValue(outputDir.resolve("iteration_log.json").toFile(),
                    result.history());
            mapper.writeValue(outputDir.resolve("verified_bugs.json").toFile(),
                    result.verifiedBugMethods());
            mapper.writeValue(outputDir.resolve("potential_bugs.json").toFile(),
                    result.potentialBugs());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("target_class", targetFqn);
            summary.put("model", model);
            summary.put("seed", seed);
            summary.put("iterations_run", result.iterationsRun());
            summary.put("best_coverage", result.bestCoverage());
            summary.put("best_reward", result.bestReward());
            summary.put("potential_bugs", result.potentialBugs().size());
            summary.put("verified_methods", result.verifiedBugMethods().size());
            summary.put("real_bugs", result.realBugsCount());
            summary.put("false_positives", result.falsePositivesCount());
            mapper.writeValue(outputDir.resolve("summary.json").toFile(), summary);

            return bestTest;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write artifacts to " + outputDir, e);
        }
    }
}
