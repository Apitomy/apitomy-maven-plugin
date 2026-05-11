package io.apicurio.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VerifyProjectDependenciesReportMojo}.
 */
@ExtendWith(MockitoExtension.class)
class VerifyProjectDependenciesReportMojoTest {

    private VerifyProjectDependenciesReportMojo mojo;

    @Mock
    private Log mockLog;

    @Mock
    private MavenSession mockSession;

    @TempDir
    Path tempDir;

    private Path resultsDir;

    @BeforeEach
    void setUp() throws Exception {
        mojo = new VerifyProjectDependenciesReportMojo();
        mojo.setLog(mockLog);
        mojo.session = mockSession;

        MavenProject topLevelProject = mock(MavenProject.class);
        org.apache.maven.model.Build build = mock(org.apache.maven.model.Build.class);
        when(build.getDirectory()).thenReturn(tempDir.toString());
        when(topLevelProject.getBuild()).thenReturn(build);
        when(mockSession.getTopLevelProject()).thenReturn(topLevelProject);

        resultsDir = tempDir.resolve(VerifyProjectDependenciesCollectMojo.RESULTS_DIR);
        Files.createDirectories(resultsDir);
    }

    private void writeResultFile(String artifactId, ModuleReport report)
            throws Exception {
        VerifyResultsFile file = new VerifyResultsFile(report);
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(resultsDir.resolve(artifactId + ".json").toFile(), file);
    }

    // ========================================================================
    // readAllResults tests
    // ========================================================================

    @Test
    void testReadAllResults_noDirectory() throws Exception {
        Files.delete(resultsDir);

        List<ModuleReport> results = mojo.readAllResults();

        assertTrue(results.isEmpty());
    }

    @Test
    void testReadAllResults_emptyDirectory() throws Exception {
        List<ModuleReport> results = mojo.readAllResults();

        assertTrue(results.isEmpty());
    }

    @Test
    void testReadAllResults_multipleFiles() throws Exception {
        ModuleReport reportA = new ModuleReport("io.apicurio", "module-a", "1.0.0");
        ModuleReport reportB = new ModuleReport("io.apicurio", "module-b", "1.0.0");
        reportB.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "bad", "1.0", "hierarchy")));

        writeResultFile("module-a", reportA);
        writeResultFile("module-b", reportB);

        List<ModuleReport> results = mojo.readAllResults();

        assertTrue(results.size() == 2);
    }

    // ========================================================================
    // execute tests
    // ========================================================================

    @Test
    void testExecute_noResultsDirectory() throws Exception {
        Files.delete(resultsDir);

        assertDoesNotThrow(() -> mojo.execute());
    }

    @Test
    void testExecute_allModulesPass() throws Exception {
        ModuleReport reportA = new ModuleReport("io.apicurio", "module-a", "1.0.0");
        ModuleReport reportB = new ModuleReport("io.apicurio", "module-b", "1.0.0");
        writeResultFile("module-a", reportA);
        writeResultFile("module-b", reportB);

        assertDoesNotThrow(() -> mojo.execute());
    }

    @Test
    void testExecute_singleModuleFailure() throws Exception {
        ModuleReport reportA = new ModuleReport("io.apicurio", "module-a", "1.0.0");
        reportA.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "bad", "1.0",
                        "root -> bad:1.0")));
        writeResultFile("module-a", reportA);

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.execute());

        assertTrue(ex.getMessage().contains("module-a"));
        assertTrue(ex.getMessage().contains("1 module(s) with failures"));
        assertTrue(ex.getMessage().contains("1 unaligned dep(s)"));
    }

    @Test
    void testExecute_multiModuleFailures() throws Exception {
        ModuleReport reportA = new ModuleReport("io.apicurio", "module-a", "1.0.0");
        reportA.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "bad-a", "1.0",
                        "root -> bad-a:1.0")));

        ModuleReport reportB = new ModuleReport("io.apicurio", "module-b", "1.0.0");
        reportB.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "bad-b", "2.0",
                        "root -> bad-b:2.0")));

        writeResultFile("module-a", reportA);
        writeResultFile("module-b", reportB);

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.execute());

        assertTrue(ex.getMessage().contains("module-a"));
        assertTrue(ex.getMessage().contains("module-b"));
        assertTrue(ex.getMessage().contains("2 module(s) with failures"));
        assertTrue(ex.getMessage().contains("2 unaligned dep(s)"));
    }

    @Test
    void testExecute_mixedSuccessAndFailure() throws Exception {
        ModuleReport reportA = new ModuleReport("io.apicurio", "module-a", "1.0.0");
        reportA.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "bad", "1.0",
                        "root -> bad:1.0")));

        ModuleReport reportB = new ModuleReport("io.apicurio", "module-b", "1.0.0");

        writeResultFile("module-a", reportA);
        writeResultFile("module-b", reportB);

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.execute());

        assertTrue(ex.getMessage().contains("1 module(s) with failures"));
        assertTrue(ex.getMessage().contains("module-a"));
    }

    @Test
    void testExecute_deduplicatedGavList() throws Exception {
        ModuleReport reportA = new ModuleReport("io.apicurio", "module-a", "1.0.0");
        reportA.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "shared-lib", "1.0",
                        "module-a -> shared-lib:1.0")));

        ModuleReport reportB = new ModuleReport("io.apicurio", "module-b", "1.0.0");
        reportB.setUnalignedDependencies(List.of(
                new UnalignedDependency("com.example", "shared-lib", "1.0",
                        "module-b -> shared-lib:1.0")));

        writeResultFile("module-a", reportA);
        writeResultFile("module-b", reportB);

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.execute());

        String msg = ex.getMessage();
        int firstIndex = msg.indexOf("- com.example:shared-lib:1.0");
        int lastIndex = msg.lastIndexOf("- com.example:shared-lib:1.0");
        assertTrue(firstIndex == lastIndex, "GAV should appear only once in summary");
    }

}
