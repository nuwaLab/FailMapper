package org.failmapper.core.model;

import java.util.List;

/**
 * One buildable module of a user project, produced by a build oracle
 * ({@code org.failmapper.build.MavenBuildOracle} / {@code GradleBuildOracle}) and consumed by the
 * exec (compilation + test run) and coverage layers.
 *
 * <p>Replaces the Python port's regex-over-pom.xml parsing (dependency_analyzer.py): every path
 * here comes from the build system's own effective model / dependency resolver, never from
 * scraping text. All paths are absolute.
 *
 * <p>Contract notes:
 * <ul>
 *   <li>{@code testClasspath} is the transitive TEST-scope dependency classpath (absolute jar or
 *       directory paths). It includes sibling reactor modules' {@code outputDirectory} entries when
 *       this module depends on a sibling, but does NOT include this module's own
 *       {@code outputDirectory}/{@code testOutputDirectory} — consumers prepend those when
 *       composing an execution classpath.</li>
 *   <li>{@code outputDirectory}/{@code testOutputDirectory} are where the build system compiles
 *       main/test classes ({@code target/classes} for Maven, {@code build/classes/java/main} for
 *       Gradle). They are reported even if not yet built (the directory may not exist).</li>
 *   <li>Aggregator modules (Maven {@code packaging=pom}) are included with empty-on-disk source
 *       roots so the reactor structure is complete; consumers that only want code modules filter
 *       on existing source roots.</li>
 * </ul>
 */
public record ModuleModel(
        String groupId,
        String artifactId,
        String version,
        String moduleDir,
        List<String> sourceRoots,
        List<String> testSourceRoots,
        List<String> testClasspath,
        String outputDirectory,
        String testOutputDirectory) {

    /** Group:artifact key used to match inter-module (reactor) dependencies. */
    public String gaKey() {
        return groupId + ":" + artifactId;
    }
}
