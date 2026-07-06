package org.failmapper.coverage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

import org.jacoco.agent.AgentJar;

/**
 * Materializes the JaCoCo runtime agent jar and builds the
 * {@code -javaagent:<jar>=destfile=<exec>} argument for
 * {@code ForkedTestRunner.RunSpec.javaAgentArg}.
 *
 * <p>This is the contract fix for the Python port's build-file mutation: the
 * agent is attached to OUR forked JVM's command line only — no user
 * {@code pom.xml}/{@code build.gradle} is ever edited to wire JaCoCo in.</p>
 *
 * <p>The agent jar bytes come from the {@code org.jacoco:org.jacoco.agent}
 * artifact's embedded runtime jar via
 * {@link AgentJar#extractToTempLocation()}; extraction happens once per JVM
 * (the temp file is marked delete-on-exit by JaCoCo).</p>
 */
public final class JacocoAgent {

    private static volatile File extractedAgentJar;

    private JacocoAgent() {
    }

    /**
     * @param execFile where the forked JVM writes JaCoCo execution data on exit
     * @return a complete {@code -javaagent:...=destfile=...} JVM argument
     */
    public static String javaAgentArg(Path execFile) {
        Objects.requireNonNull(execFile, "execFile");
        return "-javaagent:" + agentJar().getAbsolutePath()
                + "=destfile=" + execFile.toAbsolutePath();
    }

    /** The extracted agent jar (extracted lazily, once per JVM). */
    public static File agentJar() {
        File jar = extractedAgentJar;
        if (jar == null) {
            synchronized (JacocoAgent.class) {
                jar = extractedAgentJar;
                if (jar == null) {
                    try {
                        jar = AgentJar.extractToTempLocation();
                    } catch (IOException e) {
                        throw new UncheckedIOException("failed to extract the JaCoCo agent jar", e);
                    }
                    extractedAgentJar = jar;
                }
            }
        }
        return jar;
    }
}
