package io.apitomy.common.apps.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Aggregates dependency verification results from all reactor modules and reports
 * failures. This mojo reads JSON result files written by the
 * {@code verify-project-dependencies} goal and produces a consolidated report.
 *
 * <p>This mojo is an aggregator: Maven executes it once, during the execution root's own
 * build. The execution root is the <em>first</em> project in the reactor, so binding this
 * goal to a lifecycle phase makes it run before the modules it is meant to aggregate. It
 * therefore verifies that every other reactor module has already been built and fails with
 * an explanatory message if not, rather than reporting an all-clear on results that do not
 * exist yet.
 *
 * <p>Invoke it from the command line, after the reactor has reached {@code package}:
 * <pre>{@code
 * mvn clean package io.apitomy:apitomy-maven-plugin:verify-project-dependencies-report
 * }</pre>
 *
 * <p>See {@code USAGE.md} for the full workflow.
 *
 * @see VerifyProjectDependenciesCollectMojo
 */
@Mojo(name = "verify-project-dependencies-report", defaultPhase = LifecyclePhase.VERIFY,
        aggregator = true, threadSafe = true)
public class VerifyProjectDependenciesReportMojo extends AbstractMojo {

    static final String GOAL_NAME = "verify-project-dependencies-report";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Only run the report if this is the project root
        if (!project.isExecutionRoot()) {
            return;
        }

        failIfReactorIncomplete();

        try {
            List<ModuleReport> allResults = readAllResults();

            if (allResults.isEmpty()) {
                throw new MojoFailureException("No dependency verification results found,"
                        + " so nothing was verified."
                        + "\n\nThe '" + GOAL_NAME + "' goal aggregates the results written"
                        + " by the 'verify-project-dependencies' goal. Configure that goal"
                        + " in the modules you want verified, and make sure the build"
                        + " reached the 'package' phase before this goal ran."
                        + "\n\nSee USAGE.md for the full workflow.");
            }

            getLog().info("Aggregating dependency verification results from "
                    + allResults.size() + " module(s).");

            List<ModuleReport> failedModules = new ArrayList<>();
            for (ModuleReport report : allResults) {
                if (report.hasFailures()) {
                    failedModules.add(report);
                }
            }

            if (failedModules.isEmpty()) {
                getLog().info("All dependencies in all reactor modules are properly "
                        + "productized.");
                return;
            }

            // De-duplicate and sort the unaligned dependencies across all modules.
            Set<String> allGavs = new TreeSet<>();
            for (ModuleReport module : failedModules) {
                for (UnalignedDependency dep : module.getUnalignedDependencies()) {
                    allGavs.add(dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                            + dep.getVersion());
                }
            }

            writeCsvReport(allGavs);
            reportAggregateFailures(failedModules, allGavs);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Error reading dependency verification results", e);
        }
    }

    /**
     * Fails the build if any other reactor module has not finished building yet.
     *
     * <p>As an aggregator, this mojo executes during the execution root's own lifecycle, and
     * the execution root is the <em>first</em> project in the reactor. Bound to a lifecycle
     * phase it therefore runs before the modules it is meant to aggregate, finds no results,
     * and reports an all-clear that means nothing. Rather than reporting on an incomplete
     * reactor, fail and explain how to invoke the goal correctly.
     *
     * @throws MojoFailureException if any other reactor module has not been built yet
     */
    void failIfReactorIncomplete() throws MojoFailureException {
        List<String> pendingModules = new ArrayList<>();
        for (MavenProject reactorProject : session.getProjects()) {
            if (reactorProject == project) {
                continue;
            }
            if (session.getResult().getBuildSummary(reactorProject) == null) {
                pendingModules.add(reactorProject.getArtifactId());
            }
        }

        if (pendingModules.isEmpty()) {
            return;
        }

        throw new MojoFailureException("The '" + GOAL_NAME + "' goal ran before "
                + pendingModules.size() + " reactor module(s) had been built: "
                + String.join(", ", pendingModules) + "."
                + "\n\nThis goal is an aggregator, so it runs during the execution root's"
                + " own build, which happens first in the reactor. Aggregating at that"
                + " point would report on results that do not exist yet."
                + "\n\nInvoke it from the command line after the modules have been built:"
                + "\n    mvn clean package io.apitomy:apitomy-maven-plugin:" + GOAL_NAME
                + "\n\nSee USAGE.md for the full workflow.");
    }

    /**
     * Reads the JSON result files written by this build from each reactor module's build
     * directory.
     *
     * <p>Results files live in {@code target} and survive across builds, so a module that did
     * not run the collect goal this time — because it was deselected with {@code -pl}, skipped
     * by a profile, or simply not rebuilt without {@code clean} — can still have a file on disk
     * from a previous run. Counting those would either resurrect violations that have since
     * been fixed or pass off an old clean verdict as a current one, so files stamped with a
     * different build are ignored and reported.
     *
     * @return the module reports produced by this build
     * @throws IOException if a results file cannot be read
     */
    List<ModuleReport> readAllResults() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<ModuleReport> results = new ArrayList<>();
        List<String> staleModules = new ArrayList<>();
        List<String> modulesWithoutResults = new ArrayList<>();
        String currentBuildId = currentBuildId();

        for (MavenProject reactorProject : session.getProjects()) {
            Path dir = Paths.get(reactorProject.getBuild().getDirectory(),
                    VerifyProjectDependenciesCollectMojo.RESULTS_DIR);
            if (!Files.isDirectory(dir)) {
                modulesWithoutResults.add(reactorProject.getArtifactId());
                continue;
            }

            boolean contributed = false;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : (Iterable<Path>) files.filter(
                        p -> p.toString().endsWith(".json"))::iterator) {
                    VerifyResultsFile resultsFile = mapper.readValue(
                            file.toFile(), VerifyResultsFile.class);
                    if (resultsFile.getModule() == null) {
                        continue;
                    }
                    if (isFromThisBuild(resultsFile, currentBuildId)) {
                        results.add(resultsFile.getModule());
                        contributed = true;
                    } else {
                        staleModules.add(reactorProject.getArtifactId());
                        getLog().debug("Ignoring results from another build: " + file);
                    }
                }
            }
            if (!contributed) {
                modulesWithoutResults.add(reactorProject.getArtifactId());
            }
        }

        reportContributions(results, staleModules, modulesWithoutResults);
        return results;
    }

    /**
     * Logs which modules contributed to this report, and which did not.
     *
     * @param results the reports gathered from this build
     * @param staleModules modules whose results came from another build
     * @param modulesWithoutResults modules that produced no results for this build
     */
    private void reportContributions(List<ModuleReport> results, List<String> staleModules,
            List<String> modulesWithoutResults) {

        if (!results.isEmpty()) {
            getLog().info("Modules verified in this build: " + results.stream()
                    .map(ModuleReport::getArtifactId)
                    .collect(Collectors.joining(", ")));
        }
        if (!staleModules.isEmpty()) {
            getLog().warn("Ignored results left over from a previous build in: "
                    + String.join(", ", staleModules)
                    + ". Those modules did not run '"
                    + VerifyProjectDependenciesCollectMojo.GOAL_NAME
                    + "' in this build, so their findings are not current.");
        }
        if (!modulesWithoutResults.isEmpty()) {
            getLog().warn("No dependency verification results for: "
                    + String.join(", ", modulesWithoutResults)
                    + ". Those modules were not verified.");
        }
    }

    /**
     * Checks whether a results file was written by the build that is now reporting. A file
     * without a build identifier predates them, so it cannot be attributed to this build.
     *
     * @param resultsFile the results file to check
     * @param currentBuildId the identifier of the current build, or {@code null} if unknown
     * @return {@code true} if the file belongs to this build
     */
    private boolean isFromThisBuild(VerifyResultsFile resultsFile, String currentBuildId) {
        if (currentBuildId == null) {
            // The build identifier could not be determined, so staleness cannot be judged.
            return true;
        }
        return currentBuildId.equals(resultsFile.getBuildId());
    }

    /**
     * @return an identifier for the current Maven invocation, or {@code null} if unavailable
     */
    private String currentBuildId() {
        return buildIdOf(session);
    }

    /**
     * Derives a build identifier shared by every module of a single Maven invocation.
     *
     * @param session the Maven session
     * @return the identifier, or {@code null} if the session has no start time
     */
    static String buildIdOf(MavenSession session) {
        return session.getStartTime() == null ? null
                : String.valueOf(session.getStartTime().getTime());
    }

    /**
     * Builds and throws the aggregate failure report.
     *
     * @param failedModules the modules that have unaligned dependencies
     * @param allGavs the de-duplicated, sorted set of unaligned GAV strings
     * @throws MojoFailureException always, containing the formatted report
     */
    void reportAggregateFailures(List<ModuleReport> failedModules, Set<String> allGavs)
            throws MojoFailureException {

        int totalUnaligned = allGavs.size();

        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append("=== Aggregate Dependency Verification Results ===\n");
        message.append("=== Summary: ")
                .append(failedModules.size()).append(" module(s) with failures, ")
                .append(totalUnaligned).append(" unaligned dep(s) ===\n");

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
     * Writes a CSV file listing all unaligned GAVs to the root project's target directory.
     * The file contains columns {@code groupId}, {@code artifactId}, and {@code version}.
     *
     * @param allGavs the de-duplicated, sorted set of unaligned GAV strings
     * @throws IOException if the file cannot be written
     */
    void writeCsvReport(Set<String> allGavs) throws IOException {
        if (allGavs.isEmpty()) {
            return;
        }

        Path targetDir = Paths.get(project.getBuild().getDirectory());
        Files.createDirectories(targetDir);
        Path csvPath = targetDir.resolve("unaligned-dependencies.csv");

        StringBuilder csv = new StringBuilder();
        csv.append("groupId,artifactId,version\n");
        for (String gav : allGavs) {
            String[] parts = gav.split(":", 3);
            csv.append(parts[0]).append(",").append(parts[1]).append(",")
                    .append(parts[2]).append("\n");
        }

        Files.writeString(csvPath, csv.toString());
        getLog().info("CSV report written to: " + csvPath.toAbsolutePath());
    }

}
