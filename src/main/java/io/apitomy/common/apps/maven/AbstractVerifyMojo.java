package io.apitomy.common.apps.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.SelectorUtils;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;

/**
 * Base class for verification mojos that check whether Maven dependencies are productized.
 * Provides shared validation logic, reporting, CSV output, and verbose logging utilities.
 *
 * <p>Which version suffixes count as productized is controlled by
 * {@code productizationModes}; see {@link ProductizationFlavor}.
 */
public abstract class AbstractVerifyMojo extends AbstractProductizationMojo {

    /**
     * List of groupId:artifactId patterns for dependencies to ignore during validation.
     * Supports wildcard patterns using {@code *} (e.g. {@code io.apitomy:*}).
     * Matching dependencies will not be flagged even if they are unproductized.
     */
    @Parameter(property = "ignoreGAVs")
    List<String> ignoreGAVs;

    /**
     * Optional path to a CSV output file. When set, a deduplicated flat list of all unproductized
     * dependencies will be written to this file with columns: groupId, artifactId, version.
     * The list is sorted by groupId:artifactId:version.
     */
    @Parameter(property = "outputCsv")
    File outputCsv;

    /**
     * Walks a dependency tree and records any unproductized dependencies. Skips the root node
     * (the artifact being checked) and any dependencies matching ignore patterns. When an
     * unproductized dependency is found, the full dependency path from the root down to the
     * unproductized dependency is included in the output.
     *
     * <p>A dependency that several paths lead to is one problem, not one per path, so results
     * are keyed by {@code groupId:artifactId:version}. The shortest path to each is kept as its
     * representative, since that is the one most useful for deciding what to change. The map
     * can be carried across several trees to accumulate results for a whole repository.
     *
     * @param root the root node of the dependency tree
     * @param artifactGav the GAV of the artifact being checked (for log messages)
     * @param unalignedByGav the map, keyed by GAV, to record unaligned dependencies in
     */
    protected void collectUnalignedDependencies(DependencyNode root, String artifactGav,
            Map<String, UnalignedDependency> unalignedByGav) {
        Deque<DependencyNode> path = new ArrayDeque<>();
        root.accept(new DependencyVisitor() {
            @Override
            public boolean visitEnter(DependencyNode node) {
                path.push(node);

                // Skip the root node (it's the artifact we're checking, not a dependency).
                if (node == root) {
                    return true;
                }

                if (node.getDependency() == null || node.getArtifact() == null) {
                    return true;
                }

                String depGroupId = node.getArtifact().getGroupId();
                String depArtifactId = node.getArtifact().getArtifactId();
                String depVersion = node.getArtifact().getVersion();
                String depScope = node.getDependency().getScope();

                if (isIgnored(depGroupId, depArtifactId)) {
                    logVerbose("Ignoring dependency: " + depGroupId + ":" + depArtifactId
                            + " in tree of " + artifactGav);
                    return true;
                }

                if (!isProductized(depVersion)) {
                    String gav = depGroupId + ":" + depArtifactId + ":" + depVersion;
                    record(unalignedByGav, gav, depGroupId, depArtifactId, depVersion, path);
                    logVerbose("Unaligned dependency: " + gav + " (" + depScope + ")");
                }

                return true;
            }

            @Override
            public boolean visitLeave(DependencyNode node) {
                path.pop();
                return true;
            }
        });
    }

    /**
     * Records an unaligned dependency, keeping the shortest known path to it.
     *
     * @param unalignedByGav the map to record in
     * @param gav the dependency's groupId:artifactId:version
     * @param groupId the dependency's groupId
     * @param artifactId the dependency's artifactId
     * @param version the dependency's version
     * @param path the current path from the root down to the dependency
     */
    private void record(Map<String, UnalignedDependency> unalignedByGav, String gav,
            String groupId, String artifactId, String version, Deque<DependencyNode> path) {

        UnalignedDependency existing = unalignedByGav.get(gav);
        if (existing != null && existing.getDepth() <= path.size()) {
            return;
        }

        UnalignedDependency dependency = new UnalignedDependency(groupId, artifactId, version,
                buildHierarchyMessage(path));
        dependency.setDepth(path.size());
        unalignedByGav.put(gav, dependency);
    }

    /**
     * Builds a human-readable hierarchy message showing the full dependency path from the root
     * down to the unproductized dependency.
     *
     * @param path the stack of dependency nodes from root to the unproductized dependency
     * @return a formatted string showing the full dependency chain
     */
    protected String buildHierarchyMessage(Deque<DependencyNode> path) {
        StringBuilder sb = new StringBuilder();
        Iterator<DependencyNode> it = path.descendingIterator();
        int depth = 0;
        while (it.hasNext()) {
            DependencyNode node = it.next();
            if (node.getArtifact() == null) {
                continue;
            }
            if (depth > 0) {
                sb.append("\n");
                sb.append("  ".repeat(depth));
                sb.append("-> ");
            }
            sb.append(node.getArtifact().getGroupId())
                    .append(":")
                    .append(node.getArtifact().getArtifactId())
                    .append(":")
                    .append(node.getArtifact().getVersion());
            if (node.getDependency() != null && node.getDependency().getScope() != null
                    && !node.getDependency().getScope().isEmpty()) {
                sb.append(" (").append(node.getDependency().getScope()).append(")");
            }
            depth++;
        }
        return sb.toString();
    }

    /**
     * Reports the validation results and throws a {@link MojoFailureException} if any violations
     * or resolution errors were found.
     *
     * <p>The count reported is the number of distinct unaligned dependencies, each shown with
     * one representative dependency path.
     *
     * @param unalignedDependencies the distinct unaligned dependencies
     * @param resolutionErrors the set of dependency resolution error descriptions
     * @throws MojoFailureException if any violations or errors were found
     */
    protected void reportResults(Collection<UnalignedDependency> unalignedDependencies,
            Set<String> resolutionErrors) throws MojoFailureException {

        getLog().info("  Unaligned dependencies: " + unalignedDependencies.size());
        getLog().info("  Resolution errors:      " + resolutionErrors.size());

        boolean hasFailures = !unalignedDependencies.isEmpty()
                || !resolutionErrors.isEmpty();

        if (!hasFailures) {
            getLog().info("All dependencies are properly productized.");
            return;
        }

        StringBuilder failureMessage = new StringBuilder();

        if (!unalignedDependencies.isEmpty()) {
            failureMessage.append("\nUnaligned dependencies (")
                    .append(unalignedDependencies.size())
                    .append("), each shown with its shortest dependency path:\n");
            for (UnalignedDependency dep : unalignedDependencies) {
                failureMessage.append("    ")
                        .append(dep.getHierarchy().replace("\n", "\n    "))
                        .append("\n\n");
            }
        }

        if (!resolutionErrors.isEmpty()) {
            failureMessage.append("\nDependency resolution errors:\n");
            for (String err : resolutionErrors) {
                failureMessage.append("    ").append(err).append("\n");
            }
        }

        throw new MojoFailureException(failureMessage.toString());
    }

    /**
     * Writes a CSV file containing the deduplicated list of unproductized dependencies,
     * sorted by groupId:artifactId:version. Only writes the file if {@link #outputCsv} is
     * configured and there are unproductized dependencies to report.
     *
     * @param unalignedDependencies the distinct unaligned dependencies
     * @throws IOException if the file cannot be written
     */
    protected void writeCsvReport(Collection<UnalignedDependency> unalignedDependencies)
            throws IOException {
        if (outputCsv == null || unalignedDependencies.isEmpty()) {
            return;
        }

        Set<String> unproductizedGavs = unalignedDependencies.stream()
                .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                        + dep.getVersion())
                .collect(Collectors.toCollection(TreeSet::new));

        Path csvPath = outputCsv.toPath();
        if (csvPath.getParent() != null) {
            Files.createDirectories(csvPath.getParent());
        }

        StringBuilder csv = new StringBuilder();
        csv.append("groupId,artifactId,version\n");
        for (String gav : unproductizedGavs) {
            String[] parts = gav.split(":", 3);
            csv.append(parts[0]).append(",").append(parts[1]).append(",")
                    .append(parts[2]).append("\n");
        }

        Files.writeString(csvPath, csv.toString());
        getLog().info("CSV report written to: " + csvPath.toAbsolutePath());
    }

    /**
     * Checks whether a dependency should be ignored based on the configured ignore patterns.
     *
     * @param groupId the dependency's groupId
     * @param artifactId the dependency's artifactId
     * @return {@code true} if the dependency matches any ignore pattern
     */
    protected boolean isIgnored(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) {
            return false;
        }
        for (String pattern : ignoreGAVs) {
            String[] parts = pattern.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            if (SelectorUtils.match(parts[0], groupId)
                    && SelectorUtils.match(parts[1], artifactId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs the full dependency tree when verbose mode is enabled.
     *
     * @param root the root node of the dependency tree
     */
    protected void logVerboseDependencyTree(DependencyNode root) {
        if (!verbose) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Dependency tree:\n");
        appendTreeNode(sb, root, 0);
        getLog().info("[VERBOSE] " + sb);
    }

    /**
     * Recursively appends a dependency tree node and its children to the string builder.
     *
     * @param sb the string builder to append to
     * @param node the current dependency node
     * @param depth the current depth in the tree (for indentation)
     */
    private void appendTreeNode(StringBuilder sb, DependencyNode node, int depth) {
        if (node.getArtifact() != null) {
            sb.append("  ".repeat(depth));
            if (depth > 0) {
                sb.append("+- ");
            }
            sb.append(node.getArtifact().getGroupId())
                    .append(":")
                    .append(node.getArtifact().getArtifactId())
                    .append(":")
                    .append(node.getArtifact().getVersion());
            if (node.getDependency() != null && node.getDependency().getScope() != null
                    && !node.getDependency().getScope().isEmpty()) {
                sb.append(" (").append(node.getDependency().getScope()).append(")");
            }
            if (node.getDependency() != null && node.getDependency().isOptional()) {
                sb.append(" [optional]");
            }
            sb.append("\n");
        }
        for (DependencyNode child : node.getChildren()) {
            appendTreeNode(sb, child, depth + 1);
        }
    }

}
