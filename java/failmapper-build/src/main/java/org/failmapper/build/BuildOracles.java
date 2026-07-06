package org.failmapper.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Build-system detection: picks the right {@link BuildOracle} for a project root. */
public final class BuildOracles {

    private static final List<String> GRADLE_MARKERS = List.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    private BuildOracles() {
    }

    /**
     * Returns a {@link MavenBuildOracle} if {@code pom.xml} exists at the root, else a
     * {@link GradleBuildOracle} if any of build.gradle(.kts)/settings.gradle(.kts) exists.
     * Maven wins when both build systems are present.
     *
     * @throws BuildOracleException if no supported build system is detected
     */
    public static BuildOracle detect(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return new MavenBuildOracle();
        }
        for (String marker : GRADLE_MARKERS) {
            if (Files.isRegularFile(root.resolve(marker))) {
                return new GradleBuildOracle();
            }
        }
        throw new BuildOracleException("No supported build system (pom.xml / build.gradle / "
                + "build.gradle.kts / settings.gradle / settings.gradle.kts) at " + root);
    }
}
