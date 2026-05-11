package io.apicurio.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VerifyProjectDependenciesMojo}.
 */
@ExtendWith(MockitoExtension.class)
class VerifyProjectDependenciesMojoTest {

    private VerifyProjectDependenciesMojo mojo;

    @Mock
    private Log mockLog;

    @Mock
    private MavenSession mockSession;

    private final long sessionId = 123456789L;

    @BeforeEach
    void setUp() {
        mojo = new VerifyProjectDependenciesMojo();
        mojo.setLog(mockLog);
        mojo.session = mockSession;
        mojo.ignoreGAVs = List.of();
        mojo.verbose = false;
    }

    private void stubSessionStartTime() {
        when(mockSession.getStartTime()).thenReturn(new Date(sessionId));
    }

    @AfterEach
    void tearDown() {
        VerifyProjectDependenciesMojo.SESSION_RESULTS.clear();
        VerifyProjectDependenciesMojo.SESSION_COUNTERS.clear();
    }

    // ========================================================================
    // countExpectedExecutions tests
    // ========================================================================

    @Test
    void testCountExpectedExecutions_multiProjectMixed() {
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"),
                createProjectWithoutPlugin("io.apicurio", "module-b"),
                createProjectWithGoal("io.apicurio", "module-c",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertEquals(2, mojo.countExpectedExecutions());
    }

    @Test
    void testCountExpectedExecutions_noProjectsWithGoal() {
        List<MavenProject> projects = List.of(
                createProjectWithoutPlugin("io.apicurio", "module-a"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertEquals(1, mojo.countExpectedExecutions());
    }

    @Test
    void testCountExpectedExecutions_emptyReactor() {
        when(mockSession.getProjects()).thenReturn(List.of());

        assertEquals(1, mojo.countExpectedExecutions());
    }

    // ========================================================================
    // deferOrReport tests
    // ========================================================================

    @Test
    void testDeferOrReport_firstModuleDoesNotThrow() {
        stubSessionStartTime();
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"),
                createProjectWithGoal("io.apicurio", "module-b",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        Set<String> unproductized = Set.of("com.example:bad-lib:1.0.0");
        Set<String> gavs = Set.of("com.example:bad-lib:1.0.0");
        Set<String> errors = Set.of();

        assertDoesNotThrow(() ->
                mojo.deferOrReport("io.apicurio:module-a:1.0.0", unproductized, gavs, errors));
    }

    @Test
    void testDeferOrReport_lastModuleThrowsWhenFailures() {
        stubSessionStartTime();
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"),
                createProjectWithGoal("io.apicurio", "module-b",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertDoesNotThrow(() ->
                mojo.deferOrReport("io.apicurio:module-a:1.0.0",
                        Set.of("com.example:bad-lib:1.0.0"),
                        Set.of("com.example:bad-lib:1.0.0"), Set.of()));

        MojoFailureException ex = assertThrows(MojoFailureException.class, () ->
                mojo.deferOrReport("io.apicurio:module-b:1.0.0",
                        Set.of(), Set.of(), Set.of()));

        assertTrue(ex.getMessage().contains("module-a"));
        assertTrue(ex.getMessage().contains("bad-lib"));
    }

    @Test
    void testDeferOrReport_lastModuleSucceedsWhenNoFailures() {
        stubSessionStartTime();
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"),
                createProjectWithGoal("io.apicurio", "module-b",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertDoesNotThrow(() ->
                mojo.deferOrReport("io.apicurio:module-a:1.0.0", Set.of(), Set.of(), Set.of()));

        assertDoesNotThrow(() ->
                mojo.deferOrReport("io.apicurio:module-b:1.0.0", Set.of(), Set.of(), Set.of()));
    }

    @Test
    void testDeferOrReport_bothModulesHaveFailures() {
        stubSessionStartTime();
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"),
                createProjectWithGoal("io.apicurio", "module-b",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertDoesNotThrow(() ->
                mojo.deferOrReport("io.apicurio:module-a:1.0.0",
                        Set.of("com.example:bad-a:1.0"),
                        Set.of("com.example:bad-a:1.0"), Set.of()));

        MojoFailureException ex = assertThrows(MojoFailureException.class, () ->
                mojo.deferOrReport("io.apicurio:module-b:1.0.0",
                        Set.of("com.example:bad-b:2.0"),
                        Set.of("com.example:bad-b:2.0"), Set.of()));

        assertTrue(ex.getMessage().contains("module-a"));
        assertTrue(ex.getMessage().contains("bad-a"));
        assertTrue(ex.getMessage().contains("module-b"));
        assertTrue(ex.getMessage().contains("bad-b"));
        assertTrue(ex.getMessage().contains("2 module(s) with failures"));
    }

    @Test
    void testDeferOrReport_cleansUpStaticState() {
        stubSessionStartTime();
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertDoesNotThrow(() ->
                mojo.deferOrReport("io.apicurio:module-a:1.0.0", Set.of(), Set.of(), Set.of()));

        assertTrue(VerifyProjectDependenciesMojo.SESSION_RESULTS.isEmpty());
        assertTrue(VerifyProjectDependenciesMojo.SESSION_COUNTERS.isEmpty());
    }

    @Test
    void testDeferOrReport_cleansUpStaticStateOnFailure() {
        stubSessionStartTime();
        List<MavenProject> projects = List.of(
                createProjectWithGoal("io.apicurio", "module-a",
                        "verify-project-dependencies"));
        when(mockSession.getProjects()).thenReturn(projects);

        assertThrows(MojoFailureException.class, () ->
                mojo.deferOrReport("io.apicurio:module-a:1.0.0",
                        Set.of("com.example:bad:1.0"),
                        Set.of("com.example:bad:1.0"), Set.of()));

        assertTrue(VerifyProjectDependenciesMojo.SESSION_RESULTS.isEmpty());
        assertTrue(VerifyProjectDependenciesMojo.SESSION_COUNTERS.isEmpty());
    }

    // ========================================================================
    // reportAggregateResults tests
    // ========================================================================

    @Test
    void testReportAggregateResults_noFailures() {
        ConcurrentHashMap<String, VerifyProjectDependenciesMojo.ModuleResults> results =
                new ConcurrentHashMap<>();
        results.put("io.apicurio:module-a:1.0.0",
                new VerifyProjectDependenciesMojo.ModuleResults(
                        "io.apicurio:module-a:1.0.0", Set.of(), Set.of(), Set.of()));

        assertDoesNotThrow(() -> mojo.reportAggregateResults(results));
    }

    @Test
    void testReportAggregateResults_singleModuleFailure() {
        ConcurrentHashMap<String, VerifyProjectDependenciesMojo.ModuleResults> results =
                new ConcurrentHashMap<>();
        results.put("io.apicurio:module-a:1.0.0",
                new VerifyProjectDependenciesMojo.ModuleResults(
                        "io.apicurio:module-a:1.0.0",
                        new TreeSet<>(Set.of("com.example:bad:1.0")),
                        new TreeSet<>(Set.of("com.example:bad:1.0")),
                        Set.of()));

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.reportAggregateResults(results));

        assertTrue(ex.getMessage().contains("module-a"));
        assertTrue(ex.getMessage().contains("bad:1.0"));
        assertTrue(ex.getMessage().contains("1 module(s) with failures"));
        assertTrue(ex.getMessage().contains("1 unproductized dep(s)"));
    }

    @Test
    void testReportAggregateResults_multiModuleFailures() {
        ConcurrentHashMap<String, VerifyProjectDependenciesMojo.ModuleResults> results =
                new ConcurrentHashMap<>();
        results.put("io.apicurio:module-a:1.0.0",
                new VerifyProjectDependenciesMojo.ModuleResults(
                        "io.apicurio:module-a:1.0.0",
                        new TreeSet<>(Set.of("com.example:bad-a:1.0", "com.example:bad-a2:2.0")),
                        new TreeSet<>(Set.of("com.example:bad-a:1.0", "com.example:bad-a2:2.0")),
                        Set.of()));
        results.put("io.apicurio:module-b:1.0.0",
                new VerifyProjectDependenciesMojo.ModuleResults(
                        "io.apicurio:module-b:1.0.0",
                        Set.of(),
                        Set.of(),
                        new TreeSet<>(Set.of("io.apicurio:module-b:1.0.0: could not resolve"))));

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.reportAggregateResults(results));

        assertTrue(ex.getMessage().contains("2 module(s) with failures"));
        assertTrue(ex.getMessage().contains("2 unproductized dep(s)"));
        assertTrue(ex.getMessage().contains("1 resolution error(s)"));
    }

    @Test
    void testReportAggregateResults_mixedSuccessAndFailure() {
        ConcurrentHashMap<String, VerifyProjectDependenciesMojo.ModuleResults> results =
                new ConcurrentHashMap<>();
        results.put("io.apicurio:module-a:1.0.0",
                new VerifyProjectDependenciesMojo.ModuleResults(
                        "io.apicurio:module-a:1.0.0",
                        new TreeSet<>(Set.of("com.example:bad:1.0")),
                        new TreeSet<>(Set.of("com.example:bad:1.0")),
                        Set.of()));
        results.put("io.apicurio:module-b:1.0.0",
                new VerifyProjectDependenciesMojo.ModuleResults(
                        "io.apicurio:module-b:1.0.0",
                        Set.of(), Set.of(), Set.of()));

        MojoFailureException ex = assertThrows(MojoFailureException.class,
                () -> mojo.reportAggregateResults(results));

        assertTrue(ex.getMessage().contains("1 module(s) with failures"));
        assertTrue(ex.getMessage().contains("module-a"));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private MavenProject createProjectWithGoal(String groupId, String artifactId,
            String goal) {
        MavenProject project = mock(MavenProject.class);
        Plugin plugin = new Plugin();
        plugin.setGroupId("io.apicurio");
        plugin.setArtifactId("apicurio-maven-plugin");
        PluginExecution exec = new PluginExecution();
        exec.addGoal(goal);
        plugin.addExecution(exec);
        lenient().when(project.getBuildPlugins()).thenReturn(List.of(plugin));
        return project;
    }

    private MavenProject createProjectWithoutPlugin(String groupId, String artifactId) {
        MavenProject project = mock(MavenProject.class);
        lenient().when(project.getBuildPlugins()).thenReturn(List.of());
        return project;
    }
}
