package io.apitomy.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
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

/**
 * Tests for {@link VerifyProjectDependenciesCollectMojo}.
 */
@ExtendWith(MockitoExtension.class)
class VerifyProjectDependenciesCollectMojoTest {

    private static final Date BUILD_START = new Date(1_700_000_000_000L);

    private VerifyProjectDependenciesCollectMojo mojo;

    @Mock
    private Log mockLog;

    @Mock
    private MavenSession session;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mojo = new VerifyProjectDependenciesCollectMojo();
        mojo.setLog(mockLog);
        mojo.session = session;
        mojo.project = createProject();
        lenient().when(session.getStartTime()).thenReturn(BUILD_START);
    }

    @Test
    void testWriteResultsFile_stampsTheResultsWithTheCurrentBuild() throws Exception {
        mojo.writeResultsFile(List.of());

        VerifyResultsFile written = readResultsFile();
        assertEquals(String.valueOf(BUILD_START.getTime()), written.getBuildId(),
                "The report goal relies on this stamp to tell this build's results from"
                        + " a previous build's leftovers");
    }

    @Test
    void testWriteResultsFile_recordsTheModuleAndItsFindings() throws Exception {
        mojo.writeResultsFile(List.of(new UnalignedDependency(
                "com.example", "unaligned-lib", "1.0", "hierarchy")));

        VerifyResultsFile written = readResultsFile();
        assertEquals("amp.test:mod-a:1.0.0", written.getModule().gav());
        assertEquals(1, written.getModule().getUnalignedDependencies().size());
        assertEquals("unaligned-lib",
                written.getModule().getUnalignedDependencies().get(0).getArtifactId());
    }

    @Test
    void testWriteResultsFile_omitsTheStampWhenTheSessionHasNoStartTime() throws Exception {
        lenient().when(session.getStartTime()).thenReturn(null);

        mojo.writeResultsFile(List.of());

        assertNull(readResultsFile().getBuildId());
    }

    private MavenProject createProject() {
        MavenProject project = new MavenProject();
        project.setGroupId("amp.test");
        project.setArtifactId("mod-a");
        project.setVersion("1.0.0");
        project.getBuild().setDirectory(tempDir.resolve("target").toString());
        return project;
    }

    private VerifyResultsFile readResultsFile() throws Exception {
        Path file = mojo.getResultsDir().resolve("results.json");
        assertTrue(Files.isRegularFile(file), "Expected a results file at " + file);
        return new ObjectMapper().readValue(file.toFile(), VerifyResultsFile.class);
    }

}
