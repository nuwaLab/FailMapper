package org.failmapper.analysis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import org.failmapper.analysis.ClassModelExtractor;
import org.failmapper.analysis.FailureModelExtractor;
import org.failmapper.analysis.FailureScenarioDetector;
import org.failmapper.analysis.SourceAnalyzer;
import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Layer B alignment CLI: dumps the {@link FailureModel} and {@link ClassModel}s
 * extracted from one source file as JSON on stdout, so the outputs can be
 * diffed against the Python oracle ({@code extractor.py}).
 *
 * <p>Usage: {@code AnalysisDumper <source-file> <fqn-guess>}
 */
public final class AnalysisDumper {

    private AnalysisDumper() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: AnalysisDumper <source-file> <fqn-guess>");
            System.exit(2);
        }
        Path sourceFile = Path.of(args[0]);
        String fqn = args[1];
        String source = Files.readString(sourceFile);

        FailureModel failureModel = new FailureModelExtractor().extract(source, fqn);
        Optional<CompilationUnit> cu = new SourceAnalyzer().parse(source);
        List<ClassModel> classModels = cu
                .map(unit -> new ClassModelExtractor().extractAll(unit, sourceFile.toString()))
                .orElse(List.of());
        // FS-detector alignment: mirrors failmapper.py:171-176 (FS_Detector over the same
        // source, f_model = the extracted failure model, which no detector reads).
        List<FailureScenario> failureScenarios =
                new FailureScenarioDetector(source, fqn, failureModel).detect();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fqn", fqn);
        out.put("parsed", cu.isPresent());
        out.put("failureModel", failureModel);
        out.put("classModels", classModels);
        out.put("failureScenarios", failureScenarios);
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }
}
