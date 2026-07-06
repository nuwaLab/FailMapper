package org.failmapper.build;

/** Fatal build-oracle failure: the build model itself could not be constructed. */
public class BuildOracleException extends RuntimeException {

    public BuildOracleException(String message) {
        super(message);
    }

    public BuildOracleException(String message, Throwable cause) {
        super(message, cause);
    }
}
