package io.apitomy.common.apps.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.apache.maven.execution.MavenSession;
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
 * Collects and validates transitive dependencies for the current Maven module, writing
 * results to a JSON file for later aggregation by the
 * {@code verify-project-dependencies-report} goal.
 *
 * <p>This mojo checks that all compile and runtime scoped transitive dependencies are
 * productized for the configured {@code productizationModes} (by default {@code ibm}, i.e. a
 * {@code -ibm-} or {@code .ibm-} version suffix). It never
 * fails the build directly; instead it writes its results to disk so the aggregator
 * report mojo can produce a consolidated report across all reactor modules.
 *
 * <p>Typical usage is to enable this goal via a Maven profile in each module whose
 * dependencies should be verified:
 * <pre>{@code
 * <profile>
 *     <id>productized</id>
 *     <build>
 *         <plugins>
 *             <plugin>
 *                 <groupId>io.apitomy</groupId>
 *                 <artifactId>apitomy-maven-plugin</artifactId>
 *                 <executions>
 *                     <execution>
 *                         <goals>
 *                             <goal>verify-project-dependencies</goal>
 *                         </goals>
 *                         <configuration>
 *                             <productizationModes>
 *                                 <productizationMode>ibm</productizationMode>
 *                             </productizationModes>
 *                         </configuration>
 *                     </execution>
 *                 </executions>
 *             </plugin>
 *         </plugins>
 *     </build>
 * </profile>
 * }</pre>
 *
 * <p>Do <strong>not</strong> add {@code verify-project-dependencies-report} to the same
 * execution. That goal is an aggregator and would run during the execution root's build,
 * before any module has collected anything. Invoke it from the command line instead:
 * <pre>{@code
 * mvn clean package -Pproductized \
 *     io.apitomy:apitomy-maven-plugin:verify-project-dependencies-report
 * }</pre>
 *
 * <p>See {@code USAGE.md} for the full workflow.
 */
@Mojo(name = "verify-project-dependencies", defaultPhase = LifecyclePhase.PACKAGE,
        threadSafe = true)
public class VerifyProjectDependenciesCollectMojo extends AbstractVerifyMojo {

    static final String GOAL_NAME = "verify-project-dependencies";

    static final String RESULTS_DIR = "verify-project-dependencies";

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
            validateProductizationModes();

            if (ignoreGAVs == null) {
                ignoreGAVs = List.of();
            }

            String projectGav = project.getGroupId() + ":" + project.getArtifactId()
                    + ":" + project.getVersion();
            getLog().info("Verifying dependencies for: " + projectGav);

            CollectResult result = collectDependencyTree();

            if (result == null) {
                getLog().warn("Failed to collect dependency tree for " + projectGav);
                return;
            }

            if (!result.getExceptions().isEmpty()) {
                for (Exception ex : result.getExceptions()) {
                    getLog().warn("Resolution error: " + projectGav + ": "
                            + ex.getMessage());
                }
            }

            Map<String, UnalignedDependency> unalignedByGav = new TreeMap<>();

            if (result.getRoot() != null) {
                logVerboseDependencyTree(result.getRoot());
                collectUnalignedDependencies(result.getRoot(), projectGav, unalignedByGav);
            }

            List<UnalignedDependency> unalignedDeps = new ArrayList<>(unalignedByGav.values());
            writeCsvReport(unalignedDeps);

            getLog().info("=== Project Dependency Verification Results ===");
            getLog().info("  Unaligned dependencies: " + unalignedDeps.size());

            if (unalignedDeps.isEmpty()) {
                getLog().info("All dependencies are properly productized.");
            } else {
                getLog().warn("Unaligned dependencies found in " + projectGav
                        + " (will report at end of reactor build).");
                for (UnalignedDependency dep : unalignedDeps) {
                    getLog().warn("  " + dep.getHierarchy().replace("\n", "\n  "));
                }
            }

            writeResultsFile(unalignedDeps);

        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Error verifying project dependencies", e);
        }
    }

        /**
     * Writes the module verification results to a JSON file under the module's own
     * target directory.
     *
     * <p>The file is stamped with an identifier for this Maven invocation. Results files
     * outlive the build that wrote them, so the stamp is what lets the report goal tell this
     * build's findings apart from a previous build's leftovers.
     */
    void writeResultsFile(List<UnalignedDependency> unalignedDeps) throws IOException {
        ModuleReport moduleReport = new ModuleReport(
                project.getGroupId(), project.getArtifactId(), project.getVersion());
        moduleReport.setUnalignedDependencies(unalignedDeps);

        VerifyResultsFile resultsFile = new VerifyResultsFile(moduleReport,
                VerifyProjectDependenciesReportMojo.buildIdOf(session));

        Path dir = getResultsDir();
        Files.createDirectories(dir);
        Path file = dir.resolve("results.json");

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(file.toFile(), resultsFile);

        logVerbose("Results written to: " + file.toAbsolutePath());
    }

    Path getResultsDir() {
        return Paths.get(project.getBuild().getDirectory(), RESULTS_DIR);
    }

    /**
     * Collects the transitive dependency tree for the current project.
     *
     * @return the collect result, or null on complete failure
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
            return e.getResult();
        }
    }

}
