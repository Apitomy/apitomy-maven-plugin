package io.apicurio.common.apps.maven;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Validates that all compile and runtime scoped transitive dependencies of the current Maven
 * project are productized (have a {@code -redhat-} or {@code .redhat-} version suffix).
 *
 * <p>This mojo is intended to be used in productized builds to ensure that all dependencies
 * in the build have been built from source in PNC. It uses Maven's Aether dependency resolver
 * to collect the full transitive dependency tree of the project, then walks the tree and
 * checks each dependency's version for the required productization suffix.
 *
 * <p>Typical usage is to enable this mojo via a Maven profile that is only activated during
 * productized builds:
 * <pre>{@code
 * <profile>
 *     <id>productized</id>
 *     <build>
 *         <plugins>
 *             <plugin>
 *                 <groupId>io.apicurio</groupId>
 *                 <artifactId>apicurio-maven-plugin</artifactId>
 *                 <executions>
 *                     <execution>
 *                         <goals>
 *                             <goal>verify-project-dependencies</goal>
 *                         </goals>
 *                     </execution>
 *                 </executions>
 *             </plugin>
 *         </plugins>
 *     </build>
 * </profile>
 * }</pre>
 */
@Mojo(name = "verify-project-dependencies", defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true)
public class VerifyProjectDependenciesMojo extends AbstractVerifyMojo {

    private static final String PLUGIN_GROUP_ID = "io.apicurio";
    private static final String PLUGIN_ARTIFACT_ID = "apicurio-maven-plugin";
    private static final String GOAL_NAME = "verify-project-dependencies";

    static final ConcurrentHashMap<Long, ConcurrentHashMap<String, ModuleResults>>
            SESSION_RESULTS = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Long, AtomicInteger>
            SESSION_COUNTERS = new ConcurrentHashMap<>();

    /**
     * The current Maven project whose dependencies will be validated.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @SuppressWarnings("deprecation")
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            if (ignoreGAVs == null) {
                ignoreGAVs = List.of();
            }

            String projectGav = project.getGroupId() + ":" + project.getArtifactId()
                    + ":" + project.getVersion();
            getLog().info("Verifying dependencies for: " + projectGav);

            // Collect the full transitive dependency tree using Aether.
            CollectResult result = collectDependencyTree();

            Set<String> resolutionErrors = new TreeSet<>();
            if (result == null) {
                throw new MojoFailureException(
                        "Failed to collect dependency tree for " + projectGav);
            }

            // Report any collection exceptions as resolution errors.
            if (!result.getExceptions().isEmpty()) {
                for (Exception ex : result.getExceptions()) {
                    resolutionErrors.add(projectGav + ": " + ex.getMessage());
                }
            }

            // Walk the dependency tree and check each dependency's version.
            Set<String> unproductizedDependencies = new TreeSet<>();
            Set<String> unproductizedGavs = new TreeSet<>();

            if (result.getRoot() != null) {
                logVerboseDependencyTree(result.getRoot());
                validateDependencyTree(result.getRoot(), projectGav,
                        unproductizedDependencies, unproductizedGavs);
            }

            // Write CSV report (before any failure logic).
            writeCsvReport(unproductizedGavs);

            // Log per-module counts immediately.
            getLog().info("=== Project Dependency Verification Results ===");
            getLog().info("  Unproductized dependencies: "
                    + unproductizedDependencies.size());
            getLog().info("  Resolution errors:          " + resolutionErrors.size());

            // Defer failure to end of reactor build for aggregate reporting.
            deferOrReport(projectGav, unproductizedDependencies, unproductizedGavs,
                    resolutionErrors);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Error verifying project dependencies", e);
        }
    }

    /**
     * Counts the number of modules in the reactor that have this goal configured.
     *
     * @return the expected number of executions, at least 1
     */
    int countExpectedExecutions() {
        int count = 0;
        for (MavenProject reactorProject : session.getProjects()) {
            if (hasVerifyGoal(reactorProject)) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private boolean hasVerifyGoal(MavenProject reactorProject) {
        for (Plugin plugin : reactorProject.getBuildPlugins()) {
            if (PLUGIN_GROUP_ID.equals(plugin.getGroupId())
                    && PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) {
                for (PluginExecution execution : plugin.getExecutions()) {
                    if (execution.getGoals().contains(GOAL_NAME)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void deferOrReport(String projectGav, Set<String> unproductizedDependencies,
            Set<String> unproductizedGavs,
            Set<String> resolutionErrors) throws MojoFailureException {

        long sessionId = session.getStartTime().getTime();

        ConcurrentHashMap<String, ModuleResults> results =
                SESSION_RESULTS.computeIfAbsent(sessionId,
                        k -> new ConcurrentHashMap<>());

        results.put(projectGav, new ModuleResults(projectGav,
                unproductizedDependencies, unproductizedGavs, resolutionErrors));

        boolean hasLocalFailures = !unproductizedDependencies.isEmpty()
                || !resolutionErrors.isEmpty();

        if (!hasLocalFailures) {
            getLog().info("All dependencies are properly productized.");
        } else {
            getLog().warn("Unproductized dependencies found in " + projectGav
                    + " (will report at end of reactor build).");
            for (String dep : unproductizedDependencies) {
                getLog().warn("  " + dep.replace("\n", "\n  "));
            }
            for (String err : resolutionErrors) {
                getLog().warn("  Resolution error: " + err);
            }
        }

        AtomicInteger counter = SESSION_COUNTERS.computeIfAbsent(sessionId,
                k -> new AtomicInteger(0));
        int completedCount = counter.incrementAndGet();

        if (completedCount >= countExpectedExecutions()) {
            try {
                reportAggregateResults(results);
            } finally {
                SESSION_RESULTS.remove(sessionId);
                SESSION_COUNTERS.remove(sessionId);
            }
        }
    }

    void reportAggregateResults(ConcurrentHashMap<String, ModuleResults> allResults)
            throws MojoFailureException {

        boolean anyFailures = false;
        int totalUnproductized = 0;
        int totalErrors = 0;
        int failedModules = 0;

        for (ModuleResults moduleResult : allResults.values()) {
            if (moduleResult.hasFailures()) {
                anyFailures = true;
                failedModules++;
                totalUnproductized += moduleResult.unproductizedDependencies.size();
                totalErrors += moduleResult.resolutionErrors.size();
            }
        }

        if (!anyFailures) {
            getLog().info("All dependencies in all reactor modules are properly "
                    + "productized.");
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append("=== Aggregate Dependency Verification Results ===\n");

        for (ModuleResults moduleResult : allResults.values()) {
            if (!moduleResult.hasFailures()) {
                continue;
            }

            message.append("\n--- ").append(moduleResult.moduleGav)
                    .append(" ---\n");

            if (!moduleResult.unproductizedDependencies.isEmpty()) {
                message.append("  Unproductized dependencies:\n");
                for (String dep : moduleResult.unproductizedDependencies) {
                    message.append("    ")
                            .append(dep.replace("\n", "\n    "))
                            .append("\n\n");
                }
            }
            if (!moduleResult.resolutionErrors.isEmpty()) {
                message.append("  Resolution errors:\n");
                for (String err : moduleResult.resolutionErrors) {
                    message.append("    ").append(err).append("\n");
                }
            }
        }

        message.append("\n=== Summary: ")
                .append(failedModules).append(" module(s) with failures, ")
                .append(totalUnproductized).append(" unproductized dep(s), ")
                .append(totalErrors).append(" resolution error(s) ===\n");

        Set<String> allGavs = new TreeSet<>();
        for (ModuleResults moduleResult : allResults.values()) {
            allGavs.addAll(moduleResult.unproductizedGavs);
        }
        if (!allGavs.isEmpty()) {
            message.append("\n==================================================================\n");
            for (String gav : allGavs) {
                message.append("- ").append(gav).append("\n");
            }
            message.append("==================================================================\n");
        }

        throw new MojoFailureException(message.toString());
    }

    /**
     * Collects the transitive dependency tree for the current project using Maven's
     * Aether resolver. Uses the project's own artifact as the root and the project's
     * configured remote repositories.
     *
     * @return the collect result containing the dependency tree, or null on complete failure
     */
    private CollectResult collectDependencyTree() {
        try {
            String extension = "pom".equals(project.getPackaging()) ? "pom" : "jar";
            CollectRequest request = new CollectRequest();
            request.setRoot(new Dependency(
                    new DefaultArtifact(project.getGroupId(), project.getArtifactId(),
                            extension, project.getVersion()),
                    "compile"));
            request.setRepositories(remoteRepositories);

            return repositorySystem.collectDependencies(repoSession, request);

        } catch (DependencyCollectionException e) {
            getLog().warn("Dependency collection errors for "
                    + project.getGroupId() + ":" + project.getArtifactId()
                    + ": " + e.getMessage());
            // Return the partial result so we can still validate what was resolved.
            return e.getResult();
        }
    }

    static final class ModuleResults {
        final String moduleGav;
        final Set<String> unproductizedDependencies;
        final Set<String> unproductizedGavs;
        final Set<String> resolutionErrors;

        ModuleResults(String moduleGav, Set<String> unproductizedDependencies,
                Set<String> unproductizedGavs, Set<String> resolutionErrors) {
            this.moduleGav = moduleGav;
            this.unproductizedDependencies = unproductizedDependencies;
            this.unproductizedGavs = unproductizedGavs;
            this.resolutionErrors = resolutionErrors;
        }

        boolean hasFailures() {
            return !unproductizedDependencies.isEmpty() || !resolutionErrors.isEmpty();
        }
    }

}
