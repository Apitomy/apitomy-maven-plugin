package io.apitomy.common.apps.maven;

/**
 * Root wrapper for the JSON results file written by the collect mojo and read by the
 * report mojo.
 *
 * <p>The {@code buildId} identifies the Maven invocation that produced the file. Results
 * files survive in {@code target} across builds, so the report mojo uses it to tell this
 * build's findings apart from a previous build's leftovers.
 */
public class VerifyResultsFile {

    private ModuleReport module;
    private String buildId;

    public VerifyResultsFile() {
    }

    public VerifyResultsFile(ModuleReport module) {
        this.module = module;
    }

    public VerifyResultsFile(ModuleReport module, String buildId) {
        this.module = module;
        this.buildId = buildId;
    }

    /**
     * @return the identifier of the Maven invocation that produced this file, or
     *         {@code null} if it was written before build identifiers were recorded
     */
    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    public ModuleReport getModule() {
        return module;
    }

    public void setModule(ModuleReport module) {
        this.module = module;
    }

}
