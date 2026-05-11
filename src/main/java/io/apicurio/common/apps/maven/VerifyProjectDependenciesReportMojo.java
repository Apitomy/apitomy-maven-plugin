package io.apicurio.common.apps.maven;

import java.io.File;
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

/**
 * Aggregates dependency verification results from all reactor modules and reports
 * failures. This mojo reads JSON result files written by the
 * {@code verify-project-dependencies} goal and produces a consolidated report.
 *
 * <p>This mojo uses {@code aggregator = true}, which means Maven executes it exactly
 * once for the entire reactor build rather than once per module.
 *
 * @see VerifyProjectDependenciesCollectMojo
 */
@Mojo(name = "verify-project-dependencies-report", defaultPhase = LifecyclePhase.VERIFY,
        aggregator = true, threadSafe = true)
public class VerifyProjectDependenciesReportMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
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

            reportAggregateFailures(failedModules);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Error reading dependency verification results", e);
        }
    }

    /**
     * Reads all JSON result files from the results directory.
     */
    List<ModuleReport> readAllResults() throws IOException {
        Path dir = getResultsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        ObjectMapper mapper = new ObjectMapper();
        List<ModuleReport> results = new ArrayList<>();

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

        return results;
    }

    /**
     * Builds and throws the aggregate failure report.
     */
    void reportAggregateFailures(List<ModuleReport> failedModules)
            throws MojoFailureException {

        int totalUnaligned = 0;
        for (ModuleReport module : failedModules) {
            totalUnaligned += module.getUnalignedDependencies().size();
        }

        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append("=== Aggregate Dependency Verification Results ===\n");

        for (ModuleReport module : failedModules) {
            message.append("\n--- ").append(module.gav()).append(" ---\n");
            message.append("  Unaligned dependencies:\n");
            for (UnalignedDependency dep : module.getUnalignedDependencies()) {
                message.append("    ")
                        .append(dep.getHierarchy().replace("\n", "\n    "))
                        .append("\n\n");
            }
        }

        message.append("\n=== Summary: ")
                .append(failedModules.size()).append(" module(s) with failures, ")
                .append(totalUnaligned).append(" unaligned dep(s) ===\n");

        Set<String> allGavs = new TreeSet<>();
        for (ModuleReport module : failedModules) {
            for (UnalignedDependency dep : module.getUnalignedDependencies()) {
                allGavs.add(dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                        + dep.getVersion());
            }
        }
        if (!allGavs.isEmpty()) {
            message.append("\n======================================================="
                    + "===========\n");
            for (String gav : allGavs) {
                message.append("- ").append(gav).append("\n");
            }
            message.append("======================================================="
                    + "===========\n");
        }

        throw new MojoFailureException(message.toString());
    }

    Path getResultsDir() {
        return Paths.get(session.getTopLevelProject().getBuild().getDirectory(),
                VerifyProjectDependenciesCollectMojo.RESULTS_DIR);
    }

}
