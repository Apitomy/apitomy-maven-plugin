package io.apitomy.common.apps.maven;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Represents a single unaligned (unproductized) dependency found during verification.
 *
 * <p>{@code hierarchy} shows one representative path from the artifact being checked down to
 * this dependency — the shortest one found, since a dependency can be reachable by many.
 */
public class UnalignedDependency {

    private String groupId;
    private String artifactId;
    private String version;
    private String hierarchy;

    /**
     * Length of the recorded path, used to keep the shortest one while collecting. Not part of
     * the serialized results file.
     */
    @JsonIgnore
    private int depth;

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

    /**
     * @return the length of the recorded dependency path
     */
    @JsonIgnore
    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

}
