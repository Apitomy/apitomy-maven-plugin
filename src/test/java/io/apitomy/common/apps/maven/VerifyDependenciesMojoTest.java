package io.apitomy.common.apps.maven;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for VerifyDependenciesMojo.
 */
class VerifyDependenciesMojoTest {

    private VerifyDependenciesMojo mojo;
    private Log mockLog;
    private Method isValidMethod;

    /**
     * Sets up the test environment before each test.
     *
     * @throws Exception if setup fails
     */
    @BeforeEach
    void setUp() throws Exception {
        mojo = new VerifyDependenciesMojo();
        mockLog = Mockito.mock(Log.class);
        mojo.setLog(mockLog);

        // Use reflection to access the private isValid method
        isValidMethod = VerifyDependenciesMojo.class.getDeclaredMethod("isValid", String.class);
        isValidMethod.setAccessible(true);
    }

    /**
     * Invokes the private isValid method using reflection.
     *
     * @param artifactPath the artifact path to validate
     * @return true if valid, false otherwise
     * @throws Exception if invocation fails
     */
    private boolean invokeIsValid(String artifactPath) throws Exception {
        return (boolean) isValidMethod.invoke(mojo, artifactPath);
    }

    @Test
    @DisplayName("Valid artifact with -ibm- in file name from directory")
    void testValidArtifact_IbmDash_FromDirectory() throws Exception {
        String artifactPath = "/path/to/directory::hibernate-core-ibm-1.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with -ibm- in file name");
    }

    @Test
    @DisplayName("Valid artifact with .ibm- in file name from directory")
    void testValidArtifact_IbmDot_FromDirectory() throws Exception {
        String artifactPath = "/path/to/directory::hibernate-core.ibm-00002.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with .ibm- in file name");
    }

    @Test
    @DisplayName("Valid artifact with -ibm- in file name from zip distribution")
    void testValidArtifact_IbmDash_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::lib/hibernate-core-ibm-1.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with -ibm- in file name");
    }

    @Test
    @DisplayName("Valid artifact with .ibm- in file name from zip distribution")
    void testValidArtifact_IbmDot_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::lib/hibernate-core.ibm-00002.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with .ibm- in file name");
    }

    @Test
    @DisplayName("Valid artifact with nested path in zip distribution")
    void testValidArtifact_NestedPath_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::path/to/nested/lib/hibernate-core-ibm-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with -ibm- in file name even with nested path");
    }

    @Test
    @DisplayName("Invalid artifact without a productization suffix from directory")
    void testInvalidArtifact_NoSuffix_FromDirectory() throws Exception {
        String artifactPath = "/path/to/directory::hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath), "Should be invalid without a productization suffix");
    }

    @Test
    @DisplayName("Invalid artifact without a productization suffix from zip distribution")
    void testInvalidArtifact_NoSuffix_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::lib/hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath), "Should be invalid without a productization suffix");
    }

    @Test
    @DisplayName("BUG FIX: Invalid artifact when only zip name has -ibm- but jar doesn't")
    void testInvalidArtifact_SuffixInZipNameOnly() throws Exception {
        String artifactPath = "my-app-ibm-123.zip::lib/hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID when only zip name has -ibm- but jar file doesn't");
    }

    @Test
    @DisplayName("BUG FIX: Invalid artifact when only zip name has .ibm- but jar doesn't")
    void testInvalidArtifact_DotSuffixInZipNameOnly() throws Exception {
        String artifactPath = "my-app.ibm-00002.zip::lib/hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID when only zip name has .ibm- but jar file doesn't");
    }

    @Test
    @DisplayName("BUG FIX: Invalid artifact when directory path contains the suffix but jar doesn't")
    void testInvalidArtifact_SuffixInDirectoryPathOnly() throws Exception {
        String artifactPath = "/path/to/ibm/directory::hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID when only directory path has the suffix but jar file doesn't");
    }

    @Test
    @DisplayName("Valid artifact when both zip and jar have the productization suffix")
    void testValidArtifact_SuffixInBothZipAndJar() throws Exception {
        String artifactPath = "my-app-ibm-123.zip::lib/hibernate-core-ibm-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid when both zip and jar have the productization suffix");
    }

    @Test
    @DisplayName("Valid artifact with Windows-style path separators")
    void testValidArtifact_WindowsPathSeparators() throws Exception {
        String artifactPath = "my-app-dist.zip::lib\\hibernate-core-ibm-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with Windows-style path separators");
    }

    @Test
    @DisplayName("Invalid artifact with Windows-style path separators")
    void testInvalidArtifact_WindowsPathSeparators() throws Exception {
        String artifactPath = "my-app-ibm-123.zip::lib\\hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID with Windows-style separators when only zip has the suffix");
    }

    @Test
    @DisplayName("Valid artifact without :: separator (fallback case)")
    void testValidArtifact_NoSeparator() throws Exception {
        String artifactPath = "hibernate-core-ibm-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid for simple file name with -ibm-");
    }

    @Test
    @DisplayName("Invalid artifact without :: separator (fallback case)")
    void testInvalidArtifact_NoSeparator() throws Exception {
        String artifactPath = "hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be invalid for simple file name without a productization suffix");
    }

    @Test
    @DisplayName("Valid artifact with -ibm- in middle of file name")
    void testValidArtifact_SuffixInMiddle() throws Exception {
        String artifactPath = "my-dist.zip::lib/hibernate-ibm-core-1.2.3.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with -ibm- anywhere in file name");
    }

    @Test
    @DisplayName("Artifact with both -ibm- and .ibm- is valid")
    void testValidArtifact_BothIbmPatterns() throws Exception {
        String artifactPath = "my-dist.zip::lib/hibernate-ibm-core.ibm-00002.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with both -ibm- and .ibm- patterns");
    }

    // ========================================================================
    // Productization mode tests
    // ========================================================================

    @Test
    @DisplayName("Red Hat suffix is not valid under the default (ibm) mode")
    void testInvalidArtifact_RedhatRejectedByDefault() throws Exception {
        String artifactPath = "my-dist.zip::lib/hibernate-core-redhat-00001.jar";
        assertFalse(invokeIsValid(artifactPath),
                "The default mode is 'ibm', so a Red Hat suffix is not productized");
    }

    @Test
    @DisplayName("Red Hat suffix is valid when the redhat mode is configured")
    void testValidArtifact_RedhatWhenRedhatModeConfigured() throws Exception {
        mojo.productizationModes = List.of("redhat");
        assertTrue(invokeIsValid("my-dist.zip::lib/hibernate-core-redhat-00001.jar"));
        assertTrue(invokeIsValid("my-dist.zip::lib/hibernate-core.redhat-00001.jar"));
    }

    @Test
    @DisplayName("IBM suffix is not valid when only the redhat mode is configured")
    void testInvalidArtifact_IbmWhenOnlyRedhatModeConfigured() throws Exception {
        mojo.productizationModes = List.of("redhat");
        assertFalse(invokeIsValid("my-dist.zip::lib/hibernate-core-ibm-00002.jar"));
    }

    @Test
    @DisplayName("Both suffixes are valid when both modes are configured")
    void testValidArtifact_BothModesConfigured() throws Exception {
        mojo.productizationModes = List.of("ibm", "redhat");
        assertTrue(invokeIsValid("my-dist.zip::lib/hibernate-core-ibm-00002.jar"));
        assertTrue(invokeIsValid("my-dist.zip::lib/hibernate-core-redhat-00001.jar"));
    }

    // ========================================================================
    // Verbose logging tests
    // ========================================================================

    @Test
    @DisplayName("Verbose mode logs invalid artifacts")
    void testVerboseMode_LogsInvalidArtifacts() throws Exception {
        mojo.verbose = true;
        when(mockLog.isInfoEnabled()).thenReturn(true);

        String artifactPath = "my-app-dist.zip::lib/hibernate-core-1.2.3.jar";
        boolean result = invokeIsValid(artifactPath);

        assertFalse(result, "Should be invalid");
        verify(mockLog).info(anyString());
    }

    @Test
    @DisplayName("Non-verbose mode does not log valid artifacts")
    void testNonVerboseMode_DoesNotLogValidArtifacts() throws Exception {
        // Verbose is false by default
        String artifactPath = "my-app-dist.zip::lib/hibernate-core-ibm-1.jar";
        boolean result = invokeIsValid(artifactPath);

        assertTrue(result, "Should be valid");
        verify(mockLog, never()).info(anyString());
    }

    @Test
    @DisplayName("Edge case: Empty path after ::")
    void testEdgeCase_EmptyPathAfterSeparator() throws Exception {
        String artifactPath = "my-dist.zip::";
        assertFalse(invokeIsValid(artifactPath),
                "Should be invalid for empty path after separator");
    }

    @Test
    @DisplayName("Edge case: Only separator")
    void testEdgeCase_OnlySeparator() throws Exception {
        String artifactPath = "::";
        assertFalse(invokeIsValid(artifactPath),
                "Should be invalid for only separator");
    }
}
