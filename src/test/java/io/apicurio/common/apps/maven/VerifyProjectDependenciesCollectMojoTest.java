package io.apicurio.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VerifyProjectDependenciesCollectMojo}.
 */
@ExtendWith(MockitoExtension.class)
class VerifyProjectDependenciesCollectMojoTest {

    private VerifyProjectDependenciesCollectMojo mojo;

    @Mock
    private Log mockLog;

    @Mock
    private MavenSession mockSession;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mojo = new VerifyProjectDependenciesCollectMojo();
        mojo.setLog(mockLog);
        mojo.session = mockSession;
        mojo.ignoreGAVs = List.of();
        mojo.verbose = false;

        mojo.project = mock(MavenProject.class);
        lenient().when(mojo.project.getGroupId()).thenReturn("io.apicurio");
        lenient().when(mojo.project.getArtifactId()).thenReturn("test-module");
        lenient().when(mojo.project.getVersion()).thenReturn("1.0.0");

        MavenProject topLevelProject = mock(MavenProject.class);
        org.apache.maven.model.Build build = mock(org.apache.maven.model.Build.class);
        when(build.getDirectory()).thenReturn(tempDir.toString());
        when(topLevelProject.getBuild()).thenReturn(build);
        when(mockSession.getTopLevelProject()).thenReturn(topLevelProject);
    }

    @Test
    void testWriteResultsFile_noFailures() throws Exception {
        List<UnalignedDependency> deps = new ArrayList<>();

        assertDoesNotThrow(() -> mojo.writeResultsFile(deps));

        Path file = tempDir.resolve("verify-deps").resolve("test-module.json");
        assertTrue(Files.exists(file));

        ObjectMapper mapper = new ObjectMapper();
        VerifyResultsFile result = mapper.readValue(file.toFile(),
                VerifyResultsFile.class);

        assertEquals("io.apicurio", result.getModule().getGroupId());
        assertEquals("test-module", result.getModule().getArtifactId());
        assertEquals("1.0.0", result.getModule().getVersion());
        assertTrue(result.getModule().getUnalignedDependencies().isEmpty());
    }

    @Test
    void testWriteResultsFile_withFailures() throws Exception {
        List<UnalignedDependency> deps = List.of(
                new UnalignedDependency("com.example", "bad-lib", "1.0",
                        "root -> bad-lib:1.0"),
                new UnalignedDependency("com.example", "another", "2.0",
                        "root -> another:2.0"));

        mojo.writeResultsFile(deps);

        Path file = tempDir.resolve("verify-deps").resolve("test-module.json");
        ObjectMapper mapper = new ObjectMapper();
        VerifyResultsFile result = mapper.readValue(file.toFile(),
                VerifyResultsFile.class);

        assertEquals(2, result.getModule().getUnalignedDependencies().size());
        assertEquals("com.example",
                result.getModule().getUnalignedDependencies().get(0).getGroupId());
        assertEquals("bad-lib",
                result.getModule().getUnalignedDependencies().get(0).getArtifactId());
        assertEquals("1.0",
                result.getModule().getUnalignedDependencies().get(0).getVersion());
        assertEquals("root -> bad-lib:1.0",
                result.getModule().getUnalignedDependencies().get(0).getHierarchy());
    }

    @Test
    void testWriteResultsFile_jsonFormat() throws Exception {
        List<UnalignedDependency> deps = List.of(
                new UnalignedDependency("com.example", "lib", "1.0", "hierarchy"));

        mojo.writeResultsFile(deps);

        Path file = tempDir.resolve("verify-deps").resolve("test-module.json");
        String json = Files.readString(file);

        assertTrue(json.contains("\"module\""));
        assertTrue(json.contains("\"groupId\" : \"io.apicurio\""));
        assertTrue(json.contains("\"artifactId\" : \"test-module\""));
        assertTrue(json.contains("\"unalignedDependencies\""));
    }

    @Test
    void testGetResultsDir() {
        Path dir = mojo.getResultsDir();
        assertEquals(tempDir.resolve("verify-deps"), dir);
    }

}
