package io.apitomy.common.apps.maven;

/**
 * Represents a single unaligned (unproductized) dependency found during verification.
 */
public class UnalignedDependency {

    private String groupId;
    private String artifactId;
    private String version;
    private String hierarchy;

    public UnalignedDependency() {
    }

    public UnalignedDependency(String groupId, String artifactId, String version,
            String hierarchy) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.hierarchy = hierarchy;
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

    public String getHierarchy() {
        return hierarchy;
    }

    public void setHierarchy(String hierarchy) {
        this.hierarchy = hierarchy;
    }

}
