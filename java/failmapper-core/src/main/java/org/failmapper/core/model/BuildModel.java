package org.failmapper.core.model;

import java.util.List;

/**
 * Build-system model of a user project: the reactor's modules with their resolved source roots,
 * output directories and transitive test classpaths.
 *
 * <p>Produced by build oracles ({@code org.failmapper.build.BuildOracles#detect} →
 * {@code MavenBuildOracle}/{@code GradleBuildOracle}); consumed by the exec and coverage layers to
 * compile and run generated tests WITHOUT ever mutating the user project's build files (contract
 * root-cause fix: the Python version rewrote pom.xml and parsed console output).
 *
 * <p>{@code rootDir} is the absolute path of the analyzed project root. {@code modules} is in
 * reactor discovery order (root first, then depth-first module declaration order).
 */
public record BuildModel(
        String rootDir,
        List<ModuleModel> modules) {
}
