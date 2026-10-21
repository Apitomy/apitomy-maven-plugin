package io.apitomy.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link VerifyProjectDependenciesReportMojo}.
 */
@ExtendWith(MockitoExtension.class)
class VerifyProjectDependenciesReportMojoTest {

    private VerifyProjectDependenciesReportMojo mojo;

    @Mock
    private Log mockLog;

    @Mock
    private MavenSession session;

    @Mock
    private MavenExecutionResult executionResult;

    @TempDir
    Path tempDir;

    private static final Date BUILD_START = new Date(1_700_000_000_000L);

    @BeforeEach
    void setUp() {
        mojo = new VerifyProjectDependenciesReportMojo();
        mojo.setLog(mockLog);
        mojo.session = session;
        lenient().when(session.getResult()).thenReturn(executionResult);
        lenient().when(session.getStartTime()).thenReturn(BUILD_START);
    }

    // ========================================================================
    // Reactor completeness tests
    // ========================================================================

    @Test
    void testExecute_failsWhenOtherReactorModulesHaveNotBeenBuilt() {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA));
        markBuilt(root);
        // mod-a has no build summary: the reactor has not reached it yet.

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> mojo.execute());
        assertTrue(e.getMessage().contains("mod-a"),
                "Failure should name the module(s) that had not been built: "
                        + e.getMessage());
    }

    @Test
    void testExecute_doesNotFailForModulesThatHaveBeenBuilt() throws Exception {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA));
        markBuilt(root);
        markBuilt(modA);
        writeResults(modA, new ModuleReport("amp.test", "mod-a", "1.0.0"));

        assertDoesNotThrow(() -> mojo.execute());
    }

    // ========================================================================
    // Missing / present results tests
    // ========================================================================

    @Test
    void testExecute_failsWhenNoModuleProducedResults() {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA));
        markBuilt(root);
        markBuilt(modA);
        // No module ran the collect goal, so nothing was actually verified.

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> mojo.execute());
        assertTrue(e.getMessage().contains("verify-project-dependencies"),
                "Failure should point at the collect goal: " + e.getMessage());
    }

    @Test
    void testExecute_failsWithAggregateReportWhenAModuleHasUnalignedDependencies()
            throws Exception {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA));
        markBuilt(root);
        markBuilt(modA);

        ModuleReport report = new ModuleReport("amp.test", "mod-a", "1.0.0");
        report.setUnalignedDependencies(List.of(new UnalignedDependency(
                "com.example", "unaligned-lib", "1.0", "hierarchy")));
        writeResults(modA, report);

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> mojo.execute());
        assertTrue(e.getMessage().contains("com.example:unaligned-lib:1.0"),
                "Failure should list the unaligned GAV: " + e.getMessage());
    }

    // ========================================================================
    // Stale results tests
    // ========================================================================

    @Test
    void testExecute_failsWhenOnlyStaleResultsExist() throws Exception {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA));
        markBuilt(root);
        markBuilt(modA);
        writeResults(modA, new ModuleReport("amp.test", "mod-a", "1.0.0"),
                "a-previous-build");

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> mojo.execute());
        assertTrue(e.getMessage().contains("No dependency verification results"),
                "Results from another build must not count as this build's verdict: "
                        + e.getMessage());
    }

    @Test
    void testExecute_ignoresStaleFailuresFromAPreviousBuild() throws Exception {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        MavenProject modB = createProject("mod-b", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA, modB));
        markBuilt(root);
        markBuilt(modA);
        markBuilt(modB);

        // mod-a was fixed and rebuilt this time; mod-b still holds last build's violations.
        writeResults(modA, new ModuleReport("amp.test", "mod-a", "1.0.0"), currentBuildId());
        ModuleReport staleReport = new ModuleReport("amp.test", "mod-b", "1.0.0");
        staleReport.setUnalignedDependencies(List.of(new UnalignedDependency(
                "com.example", "already-fixed", "1.0", "hierarchy")));
        writeResults(modB, staleReport, "a-previous-build");

        assertDoesNotThrow(() -> mojo.execute(),
                "A violation from a previous build must not fail this one");
    }

    @Test
    void testExecute_usesResultsFromTheCurrentBuild() throws Exception {
        MavenProject root = createProject("root", true);
        MavenProject modA = createProject("mod-a", false);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root, modA));
        markBuilt(root);
        markBuilt(modA);

        ModuleReport report = new ModuleReport("amp.test", "mod-a", "1.0.0");
        report.setUnalignedDependencies(List.of(new UnalignedDependency(
                "com.example", "unaligned-lib", "1.0", "hierarchy")));
        writeResults(modA, report, currentBuildId());

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> mojo.execute());
        assertTrue(e.getMessage().contains("com.example:unaligned-lib:1.0"), e.getMessage());
    }

    // ========================================================================
    // Aggregate CSV tests
    // ========================================================================

    @Test
    void testExecute_writesHeaderOnlyCsvWhenTheReactorIsClean() throws Exception {
        MavenProject root = createProject("root", true);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root));
        markBuilt(root);
        writeResults(root, new ModuleReport("amp.test", "root", "1.0.0"));

        mojo.execute();

        assertEquals(List.of("groupId,artifactId,version"), aggregateCsvLines(root),
                "A clean reactor must still produce a report saying so");
    }

    @Test
    void testExecute_replacesAnAggregateCsvFromAnEarlierRun() throws Exception {
        MavenProject root = createProject("root", true);
        mojo.project = root;

        when(session.getProjects()).thenReturn(List.of(root));
        markBuilt(root);
        writeResults(root, new ModuleReport("amp.test", "root", "1.0.0"));

        Path csv = aggregateCsvPath(root);
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, "groupId,artifactId,version\ncom.example,already-fixed,1.0\n");

        mojo.execute();

        assertEquals(List.of("groupId,artifactId,version"), aggregateCsvLines(root),
                "Violations fixed since the last run must not survive in the report");
    }

    @Test
    void testExecute_skipsSilentlyWhenNotExecutionRoot() {
        MavenProject modA = createProject("mod-a", false);
        mojo.project = modA;

        assertDoesNotThrow(() -> mojo.execute());
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private MavenProject createProject(String artifactId, boolean executionRoot) {
        MavenProject project = new MavenProject();
        project.setGroupId("amp.test");
        project.setArtifactId(artifactId);
        project.setVersion("1.0.0");
        project.setExecutionRoot(executionRoot);
        project.getBuild().setDirectory(tempDir.resolve(artifactId).toString());
        return project;
    }

    private void markBuilt(MavenProject project) {
        lenient().when(executionResult.getBuildSummary(project))
                .thenReturn(new BuildSuccess(project, 1L));
    }

    private void writeResults(MavenProject project, ModuleReport report)
            throws IOException {
        writeResults(project, report, currentBuildId());
    }

    private void writeResults(MavenProject project, ModuleReport report, String buildId)
            throws IOException {
        Path dir = Path.of(project.getBuild().getDirectory(),
                VerifyProjectDependenciesCollectMojo.RESULTS_DIR);
        Files.createDirectories(dir);
        VerifyResultsFile resultsFile = new VerifyResultsFile(report);
        resultsFile.setBuildId(buildId);
        new ObjectMapper().writeValue(dir.resolve("results.json").toFile(), resultsFile);
    }

    private String currentBuildId() {
        return String.valueOf(BUILD_START.getTime());
    }

    private Path aggregateCsvPath(MavenProject project) {
        return Path.of(project.getBuild().getDirectory(), "unaligned-dependencies.csv");
    }

    private List<String> aggregateCsvLines(MavenProject project) throws IOException {
        Path csv = aggregateCsvPath(project);
        assertTrue(Files.isRegularFile(csv), "Expected an aggregate CSV at " + csv);
        return Files.readAllLines(csv);
    }

}
