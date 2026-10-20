package io.apitomy.common.apps.maven;

import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Base class for mojos that decide whether an artifact is productized. Owns the
 * {@code productizationModes} parameter and the single implementation of the check, so that
 * every goal recognises the same suffixes.
 *
 * @see ProductizationFlavor
 */
public abstract class AbstractProductizationMojo extends AbstractMojo {

    /**
     * The productization flavours to accept. Valid values are {@code ibm} and {@code redhat};
     * each contributes its own version suffixes ({@code -ibm-} and {@code .ibm-}, or
     * {@code -redhat-} and {@code .redhat-}). An artifact is productized if it matches any
     * configured mode, so listing both is the way to validate a mixed repository during a
     * productization migration.
     *
     * <p>Defaults to {@code ibm} when not configured.
     */
    @Parameter(property = "productizationModes")
    List<String> productizationModes;

    /**
     * Enable verbose output for debugging. When enabled, provides detailed information about
     * artifact discovery, dependency tree resolution and validation results.
     */
    @Parameter(property = "verify.verbose", defaultValue = "false")
    boolean verbose;

    private Set<ProductizationFlavor> resolvedModes;

    /**
     * Resolves the configured modes, defaulting to {@link ProductizationFlavor#DEFAULT}.
     *
     * @return the modes to accept, never empty
     * @throws IllegalArgumentException if a configured mode is not recognised
     */
    protected Set<ProductizationFlavor> productizationModes() {
        if (resolvedModes == null) {
            resolvedModes = ProductizationFlavor.parse(productizationModes);
        }
        return resolvedModes;
    }

    /**
     * Validates the configured modes early, so that a typo fails the build with a clear
     * message instead of silently matching nothing.
     *
     * @throws MojoFailureException if a configured mode is not recognised
     */
    protected void validateProductizationModes() throws MojoFailureException {
        try {
            productizationModes();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
        logVerbose("Accepting productized versions with suffixes: " + describeSuffixes());
    }

    /**
     * Checks whether a version string indicates a productized build.
     *
     * @param version the version string to check, may be {@code null}
     * @return {@code true} if the version matches any configured productization mode
     */
    protected boolean isProductized(String version) {
        return ProductizationFlavor.isProductized(version, productizationModes());
    }

    /**
     * @return the suffixes accepted by the configured modes, for log and failure messages
     */
    protected String describeSuffixes() {
        return ProductizationFlavor.describeSuffixes(productizationModes());
    }

    /**
     * Logs a message when verbose mode is enabled.
     *
     * @param message the message to log
     */
    protected void logVerbose(String message) {
        if (verbose) {
            getLog().info("[VERBOSE] " + message);
        }
    }

}
