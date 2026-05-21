package io.apitomy.common.apps.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * <p>This mojo runs on every module but skips unless it is the last project in the
 * reactor, ensuring all collect results are available before reporting.
 *
 * @see VerifyProjectDependenciesCollectMojo
 */
@Mojo(name = "verify-project-dependencies-report", defaultPhase = LifecyclePhase.VERIFY,
        aggregator = true, threadSafe = true)
public class VerifyProjectDependenciesReportMojo extends AbstractMojo {

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

        try {
            List<ModuleReport> allResults = readAllResults();

            if (allResults.isEmpty()) {
                getLog().info("No dependency verification results found.");
                return;
            }

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
     * Reads all JSON result files from each reactor module's build directory.
     */
    List<ModuleReport> readAllResults() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<ModuleReport> results = new ArrayList<>();

        for (MavenProject reactorProject : session.getProjects()) {
            Path dir = Paths.get(reactorProject.getBuild().getDirectory(),
                    VerifyProjectDependenciesCollectMojo.RESULTS_DIR);
            if (!Files.isDirectory(dir)) {
                continue;
            }

            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : (Iterable<Path>) files.filter(
                        p -> p.toString().endsWith(".json"))::iterator) {
                    VerifyResultsFile resultsFile = mapper.readValue(
                            file.toFile(), VerifyResultsFile.class);
                    if (resultsFile.getModule() != null) {
                        results.add(resultsFile.getModule());
                    }
                }
            }
        }

        return results;
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
