package io.apitomy.common.apps.maven;

/**
 * Root wrapper for the JSON results file written by the collect mojo and read by the
 * report mojo.
 */
public class VerifyResultsFile {

    private ModuleReport module;

    public VerifyResultsFile() {
    }

    public VerifyResultsFile(ModuleReport module) {
        this.module = module;
    }

    public ModuleReport getModule() {
        return module;
    }

    public void setModule(ModuleReport module) {
        this.module = module;
    }

}
