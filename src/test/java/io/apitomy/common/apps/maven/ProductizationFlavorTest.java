package io.apitomy.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProductizationFlavor}.
 */
class ProductizationFlavorTest {

    // ========================================================================
    // Suffix matching
    // ========================================================================

    @Test
    void testIbmMatchesDashSuffix() {
        assertTrue(ProductizationFlavor.IBM.matches("3.0.0-ibm-00002"));
    }

    @Test
    void testIbmMatchesDotSuffix() {
        assertTrue(ProductizationFlavor.IBM.matches("2.15.0.ibm-00004"));
    }

    @Test
    void testIbmDoesNotMatchRedhatSuffix() {
        assertFalse(ProductizationFlavor.IBM.matches("3.0.0-redhat-00001"));
    }

    @Test
    void testRedhatMatchesBothSuffixShapes() {
        assertTrue(ProductizationFlavor.REDHAT.matches("3.0.0-redhat-00001"));
        assertTrue(ProductizationFlavor.REDHAT.matches("2.15.0.redhat-00001"));
    }

    @Test
    void testModeDoesNotMatchPlainVersion() {
        assertFalse(ProductizationFlavor.IBM.matches("1.0.0"));
        assertFalse(ProductizationFlavor.REDHAT.matches("1.0.0-SNAPSHOT"));
    }

    @Test
    void testSuffixesAreDerivedFromTheModeToken() {
        assertEquals(List.of("-ibm-", ".ibm-"), ProductizationFlavor.IBM.suffixes());
        assertEquals(List.of("-redhat-", ".redhat-"), ProductizationFlavor.REDHAT.suffixes());
    }

    // ========================================================================
    // Parsing configured modes
    // ========================================================================

    @Test
    void testParseDefaultsToIbmWhenNotConfigured() {
        assertEquals(Set.of(ProductizationFlavor.IBM), ProductizationFlavor.parse(null));
        assertEquals(Set.of(ProductizationFlavor.IBM), ProductizationFlavor.parse(List.of()));
    }

    @Test
    void testParseAcceptsMultipleModes() {
        assertEquals(Set.of(ProductizationFlavor.IBM, ProductizationFlavor.REDHAT),
                ProductizationFlavor.parse(List.of("ibm", "redhat")));
    }

    @Test
    void testParseIsCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(Set.of(ProductizationFlavor.REDHAT),
                ProductizationFlavor.parse(List.of(" RedHat ")));
    }

    @Test
    void testParseRejectsUnknownModeAndListsValidValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProductizationFlavor.parse(List.of("acme")));
        assertTrue(e.getMessage().contains("acme"), e.getMessage());
        assertTrue(e.getMessage().contains("ibm") && e.getMessage().contains("redhat"),
                "Message should list the valid modes: " + e.getMessage());
    }

    // ========================================================================
    // Describing the configured modes
    // ========================================================================

    @Test
    void testIsProductizedMatchesAnyConfiguredMode() {
        Set<ProductizationFlavor> both = ProductizationFlavor.parse(List.of("ibm", "redhat"));
        assertTrue(ProductizationFlavor.isProductized("3.0.0-ibm-00002", both));
        assertTrue(ProductizationFlavor.isProductized("3.0.0.redhat-00001", both));
        assertFalse(ProductizationFlavor.isProductized("3.0.0", both));
    }

    @Test
    void testIsProductizedHandlesNullVersion() {
        assertFalse(ProductizationFlavor.isProductized(null,
                Set.of(ProductizationFlavor.IBM)));
    }

    @Test
    void testDescribeSuffixesListsEverySuffixInPlay() {
        String described = ProductizationFlavor.describeSuffixes(
                ProductizationFlavor.parse(List.of("ibm")));
        assertEquals("-ibm-, .ibm-", described);
    }

}
