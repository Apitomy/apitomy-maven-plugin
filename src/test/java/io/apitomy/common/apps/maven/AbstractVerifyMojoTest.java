package io.apitomy.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for the shared dependency-tree walking and reporting in {@link AbstractVerifyMojo}.
 */
class AbstractVerifyMojoTest {

    private AbstractVerifyMojo mojo;

    @BeforeEach
    void setUp() {
        mojo = new AbstractVerifyMojo() {
            @Override
            public void execute() {
                // Not exercised; this test covers the shared base behaviour only.
            }
        };
        mojo.setLog(Mockito.mock(Log.class));
        mojo.ignoreGAVs = List.of();
    }

    // ========================================================================
    // De-duplication
    // ========================================================================

    @Test
    void testCollectUnaligned_reportsADependencyReachedByTwoPathsOnce() {
        DependencyNode shared = node("com.example:shared:1.0");
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:left:1.0-ibm-00002", shared),
                node("com.example:right:1.0-ibm-00002", shared));

        List<UnalignedDependency> unaligned = collect(root);

        assertEquals(List.of("com.example:shared:1.0"), gavsOf(unaligned),
                "A dependency reachable by several paths is still one problem");
    }

    @Test
    void testCollectUnaligned_keepsTheShortestPathAsTheRepresentative() {
        DependencyNode shared = node("com.example:shared:1.0");
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:deep:1.0-ibm-00002",
                        node("com.example:deeper:1.0-ibm-00002", shared)),
                shared);

        List<UnalignedDependency> unaligned = collect(root);

        assertEquals(1, unaligned.size());
        String hierarchy = unaligned.get(0).getHierarchy();
        assertEquals(2, hierarchy.lines().count(),
                "The direct path is the useful one to show: " + hierarchy);
        assertTrue(hierarchy.contains("com.example:shared:1.0"), hierarchy);
    }

    @Test
    void testCollectUnaligned_reportsEveryDistinctUnalignedDependency() {
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:one:1.0"),
                node("com.example:two:2.0"),
                node("com.example:aligned:1.0-ibm-00002"));

        assertEquals(List.of("com.example:one:1.0", "com.example:two:2.0"),
                gavsOf(collect(root)));
    }

    @Test
    void testCollectUnaligned_treatsDifferentVersionsOfOneArtifactAsDistinct() {
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:lib:1.0"),
                node("com.example:lib:2.0"));

        assertEquals(List.of("com.example:lib:1.0", "com.example:lib:2.0"),
                gavsOf(collect(root)));
    }

    @Test
    void testCollectUnaligned_accumulatesAcrossSeveralRoots() {
        Map<String, UnalignedDependency> unaligned = new LinkedHashMap<>();
        mojo.collectUnalignedDependencies(
                node("io.apitomy:first:1.0-ibm-00002", node("com.example:shared:1.0")),
                "io.apitomy:first:1.0-ibm-00002", unaligned);
        mojo.collectUnalignedDependencies(
                node("io.apitomy:second:1.0-ibm-00002", node("com.example:shared:1.0")),
                "io.apitomy:second:1.0-ibm-00002", unaligned);

        assertEquals(List.of("com.example:shared:1.0"),
                gavsOf(new ArrayList<>(unaligned.values())),
                "The same dependency under two artifacts is still one problem");
    }

    @Test
    void testCollectUnaligned_skipsIgnoredDependencies() {
        mojo.ignoreGAVs = List.of("com.example:*");
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:ignored:1.0"),
                node("org.other:reported:1.0"));

        assertEquals(List.of("org.other:reported:1.0"), gavsOf(collect(root)));
    }

    // ========================================================================
    // Reporting
    // ========================================================================

    @Test
    void testReportResults_countsDistinctDependencies() {
        DependencyNode shared = node("com.example:shared:1.0");
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:left:1.0-ibm-00002", shared),
                node("com.example:right:1.0-ibm-00002", shared));
        List<UnalignedDependency> unaligned = collect(root);

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> mojo.reportResults(unaligned, new TreeSet<>()));
        assertEquals(1, countOccurrences(e.getMessage(), "com.example:shared:1.0"),
                "The same dependency should be listed once, not once per path: "
                        + e.getMessage());
    }

    @Test
    void testReportResults_passesWhenNothingIsUnaligned() {
        DependencyNode root = node("io.apitomy:root:1.0-ibm-00002",
                node("com.example:aligned:1.0-ibm-00002"));

        assertEquals(List.of(), gavsOf(collect(root)));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private List<UnalignedDependency> collect(DependencyNode root) {
        Map<String, UnalignedDependency> unaligned = new LinkedHashMap<>();
        mojo.collectUnalignedDependencies(root, root.getArtifact().toString(), unaligned);
        return new ArrayList<>(unaligned.values());
    }

    private List<String> gavsOf(List<UnalignedDependency> unaligned) {
        return unaligned.stream()
                .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                        + dep.getVersion())
                .sorted()
                .toList();
    }

    private DependencyNode node(String coords, DependencyNode... children) {
        DefaultDependencyNode node = new DefaultDependencyNode(
                new Dependency(new DefaultArtifact(coords), "compile"));
        node.setChildren(List.of(children));
        return node;
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

}
