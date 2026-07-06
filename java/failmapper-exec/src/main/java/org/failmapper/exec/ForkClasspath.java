package org.failmapper.exec;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Derives the classpath entries the forked test JVM needs beyond the
 * caller-supplied (user-project) classpath: failmapper-exec's own classes
 * (for {@link TestRunnerMain}), failmapper-core, jackson, the JUnit Platform
 * launcher and the Jupiter engine.
 *
 * <p><b>Assumption (documented per contract):</b> all of those artifacts are
 * already on the CURRENT JVM's classpath, because failmapper-exec declares
 * them as compile/runtime dependencies — true for any consumer that launched
 * this JVM through a normal dependency-resolved classpath (mvn exec, surefire,
 * an IDE run configuration, or a packaged distribution's {@code -cp}).</p>
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>{@code surefire.test.class.path} system property — Maven Surefire forks
 *       test JVMs with a manifest-only booter jar by default, in which case
 *       {@code java.class.path} is just that booter jar; Surefire publishes the
 *       real test classpath in this property.</li>
 *   <li>{@code java.class.path}.</li>
 *   <li>If the result is a single jar (manifest-only-jar launchers other than
 *       surefire), its manifest {@code Class-Path} entries are expanded.</li>
 * </ol>
 */
public final class ForkClasspath {

    private ForkClasspath() {
    }

    /** @return classpath entries as filesystem paths, in classloading order */
    public static List<String> currentModuleClasspath() {
        String surefire = System.getProperty("surefire.test.class.path");
        String raw = (surefire != null && !surefire.isBlank())
                ? surefire
                : System.getProperty("java.class.path", "");
        List<String> entries = Arrays.stream(raw.split(File.pathSeparator))
                .filter(s -> !s.isBlank())
                .toList();
        return expandManifestOnlyJar(entries);
    }

    /**
     * If the classpath is exactly one jar whose manifest declares Class-Path
     * (the "manifest-only jar" launcher trick), expand those entries so the
     * fork sees the real classpath.
     */
    private static List<String> expandManifestOnlyJar(List<String> entries) {
        if (entries.size() != 1 || !entries.get(0).endsWith(".jar")) {
            return entries;
        }
        try (JarFile jar = new JarFile(entries.get(0))) {
            Manifest manifest = jar.getManifest();
            String classPath = manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.isBlank()) {
                return entries;
            }
            List<String> expanded = new ArrayList<>(entries);
            for (String token : classPath.split(" ")) {
                if (token.isBlank()) {
                    continue;
                }
                // Manifest Class-Path entries written by launchers are URLs (file:/...).
                expanded.add(token.startsWith("file:")
                        ? Path.of(URI.create(token)).toString()
                        : token);
            }
            return List.copyOf(expanded);
        } catch (IOException | IllegalArgumentException e) {
            return entries; // not expandable — return as-is
        }
    }
}
