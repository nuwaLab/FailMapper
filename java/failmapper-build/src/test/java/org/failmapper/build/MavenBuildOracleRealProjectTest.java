package org.failmapper.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;
import org.junit.jupiter.api.Test;

/**
 * MavenBuildOracle against real projects:
 *
 * <ol>
 *   <li>the commons-cli corpus checkout (guarded by an assumption — skipped when the corpus
 *       directory is absent, e.g. on a fresh machine);</li>
 *   <li>this repository's own reactor (self-referential smoke: the oracle must understand the
 *       build that builds it).</li>
 * </ol>
 */
class MavenBuildOracleRealProjectTest {

    private static final Path CORPUS_COMMONS_CLI = Path.of(
            "/private/tmp/claude-501/-Users-ruiqidong-Desktop-FailMapper/"
                    + "89452d1e-a3a8-4f00-8069-acc4a693c472/scratchpad/corpus-commons-cli");

    /** This repo's java/ reactor root, resolved relative to this module's basedir. */
    private static final Path OWN_REACTOR = Path.of("..").toAbsolutePath().normalize();

    @Test
    void commonsCliCorpusResolvesSingleModuleWithJunitOnTestClasspath() {
        assumeTrue(Files.isRegularFile(CORPUS_COMMONS_CLI.resolve("pom.xml")),
                "corpus-commons-cli checkout not present; skipping corpus test");

        BuildModel model = new MavenBuildOracle().analyze(CORPUS_COMMONS_CLI);

        assertThat(model.modules()).hasSize(1);
        ModuleModel cli = model.modules().get(0);
        assertThat(cli.artifactId()).isEqualTo("commons-cli");

        assertThat(cli.sourceRoots()).allSatisfy(root ->
                assertThat(Path.of(root)).as("source root must exist: %s", root).isDirectory());
        assertThat(cli.testSourceRoots()).allSatisfy(root ->
                assertThat(Path.of(root)).as("test source root must exist: %s", root).isDirectory());

        // junit-jupiter-* versions come from commons-parent's managed BOM — resolving them
        // proves parent-POM + dependencyManagement handling on a real-world POM chain.
        assertThat(cli.testClasspath())
                .anySatisfy(entry -> assertThat(Path.of(entry).getFileName().toString())
                        .startsWith("junit-jupiter-"));
        assertThat(cli.testClasspath())
                .noneSatisfy(entry -> assertThat(entry).contains("${"));
    }

    @Test
    void ownReactorIsUnderstoodByItsOwnOracle() {
        assertThat(OWN_REACTOR.resolve("pom.xml")).exists();

        BuildModel model = new MavenBuildOracle().analyze(OWN_REACTOR);

        // parent aggregator + core + analysis + llm + build (grows with future milestones)
        assertThat(model.modules()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(model.modules()).extracting(ModuleModel::artifactId)
                .contains("failmapper-parent", "failmapper-core", "failmapper-analysis",
                        "failmapper-llm", "failmapper-build");

        ModuleModel core = byArtifactId(model, "failmapper-core");
        ModuleModel analysis = byArtifactId(model, "failmapper-analysis");
        // Reactor-internal dependency: analysis depends on core -> core's target/classes.
        assertThat(analysis.testClasspath()).contains(core.outputDirectory());
        // And its external dependency resolves to a real jar.
        assertThat(analysis.testClasspath())
                .anySatisfy(entry -> assertThat(entry).contains("javaparser"));
    }

    private static ModuleModel byArtifactId(BuildModel model, String artifactId) {
        return model.modules().stream()
                .filter(m -> m.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("module not found: " + artifactId));
    }
}
