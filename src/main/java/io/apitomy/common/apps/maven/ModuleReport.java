package io.apitomy.common.apps.maven;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the dependency verification results for a single Maven module.
 */
public class ModuleReport {

    private String groupId;
    private String artifactId;
    private String version;
    private List<UnalignedDependency> unalignedDependencies = new ArrayList<>();

    public ModuleReport() {
    }

    public ModuleReport(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<UnalignedDependency> getUnalignedDependencies() {
        return unalignedDependencies;
    }

    public void setUnalignedDependencies(List<UnalignedDependency> unalignedDependencies) {
        this.unalignedDependencies = unalignedDependencies;
    }

    /**
     * @return the module's GAV string in the format groupId:artifactId:version
     */
    public String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /**
     * @return true if this module has any unaligned dependencies
     */
    public boolean hasFailures() {
        return unalignedDependencies != null && !unalignedDependencies.isEmpty();
    }

}
