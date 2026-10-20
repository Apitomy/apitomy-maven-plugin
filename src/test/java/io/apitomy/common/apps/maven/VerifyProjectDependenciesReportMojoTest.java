package io.apitomy.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @BeforeEach
    void setUp() {
        mojo = new VerifyProjectDependenciesReportMojo();
        mojo.setLog(mockLog);
        mojo.session = session;
        lenient().when(session.getResult()).thenReturn(executionResult);
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
        Path dir = Path.of(project.getBuild().getDirectory(),
                VerifyProjectDependenciesCollectMojo.RESULTS_DIR);
        Files.createDirectories(dir);
        new ObjectMapper().writeValue(dir.resolve("results.json").toFile(),
                new VerifyResultsFile(report));
    }

}
