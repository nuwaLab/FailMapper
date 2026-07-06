package org.failmapper.build;

import java.nio.file.Path;
import org.failmapper.core.model.BuildModel;

/**
 * A build-system oracle: asks the build system itself (never regexes over build files, never
 * console scraping) for the facts the exec/coverage layers need — module list, source roots,
 * output directories, transitive test classpath.
 *
 * <p>Oracles are strictly read-only with respect to the user project: they must never write into
 * the project tree or mutate its build files.
 */
public interface BuildOracle {

    /**
     * Analyzes the project rooted at {@code projectRoot}.
     *
     * @throws BuildOracleException if the build model itself cannot be constructed (missing or
     *         unreadable build files, unresolvable parent POM, Gradle connection failure/timeout).
     *         Failure to resolve an individual dependency artifact is NOT fatal: the oracle
     *         records a warning and returns the classpath entries that did resolve.
     */
    BuildModel analyze(Path projectRoot);
}
