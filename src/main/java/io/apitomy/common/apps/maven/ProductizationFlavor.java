package io.apitomy.common.apps.maven;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A productization flavour that artifact versions can be checked against.
 *
 * <p>Note on the name: this type is deliberately <em>not</em> called {@code ProductizationMode},
 * even though the parameter it backs is {@code productizationModes}. Plexus resolves a
 * configuration child element to a same-named class in the mojo's package, so a
 * {@code <productizationMode>} element would be converted with a case-sensitive
 * {@code Enum.valueOf}, and the documented lowercase tokens would be rejected.
 *
 * <p>Each flavour owns a token (for example {@code ibm}) from which its accepted version suffixes
 * are derived: a dash form ({@code -ibm-}) and a dot form ({@code .ibm-}). A version is
 * productized for a mode if it contains either form, so both {@code 3.0.0-ibm-00002} and
 * {@code 2.15.0.ibm-00004} are recognised.
 */
public enum ProductizationFlavor {

    /** IBM productization: {@code -ibm-} and {@code .ibm-}. */
    IBM("ibm"),

    /** Red Hat productization: {@code -redhat-} and {@code .redhat-}. */
    REDHAT("redhat");

    /** The mode used when none is configured. */
    public static final ProductizationFlavor DEFAULT = IBM;

    private final String token;
    private final List<String> suffixes;

    ProductizationFlavor(String token) {
        this.token = token;
        this.suffixes = List.of("-" + token + "-", "." + token + "-");
    }

    /**
     * @return the configuration token for this mode, e.g. {@code ibm}
     */
    public String token() {
        return token;
    }

    /**
     * @return the version suffixes accepted by this mode, derived from its token
     */
    public List<String> suffixes() {
        return suffixes;
    }

    /**
     * Checks whether the given text contains one of this mode's suffixes.
     *
     * @param text the version string, or a file name containing one
     * @return {@code true} if the text is productized for this mode
     */
    public boolean matches(String text) {
        if (text == null) {
            return false;
        }
        return suffixes.stream().anyMatch(text::contains);
    }

    /**
     * Checks whether the given text is productized for any of the supplied modes.
     *
     * @param text the version string, or a file name containing one
     * @param modes the modes to accept
     * @return {@code true} if the text matches at least one mode
     */
    public static boolean isProductized(String text, Collection<ProductizationFlavor> modes) {
        return modes.stream().anyMatch(mode -> mode.matches(text));
    }

    /**
     * Resolves configured mode tokens into modes. Tokens are matched case-insensitively and
     * may be padded with whitespace. When nothing is configured, {@link #DEFAULT} is used.
     *
     * @param tokens the configured tokens, may be {@code null} or empty
     * @return the resolved modes, never empty
     * @throws IllegalArgumentException if a token does not name a known mode
     */
    public static Set<ProductizationFlavor> parse(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return EnumSet.of(DEFAULT);
        }

        Set<ProductizationFlavor> modes = EnumSet.noneOf(ProductizationFlavor.class);
        for (String token : tokens) {
            modes.add(fromToken(token));
        }
        return modes;
    }

    /**
     * Resolves a single configured token into a mode.
     *
     * @param token the configured token
     * @return the matching mode
     * @throws IllegalArgumentException if the token does not name a known mode
     */
    public static ProductizationFlavor fromToken(String token) {
        String normalized = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        for (ProductizationFlavor mode : values()) {
            if (mode.token.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown productization mode: '" + token
                + "'. Valid modes are: " + validTokens() + ".");
    }

    /**
     * @return the valid configuration tokens, comma separated
     */
    public static String validTokens() {
        return Arrays.stream(values())
                .map(ProductizationFlavor::token)
                .collect(Collectors.joining(", "));
    }

    /**
     * Describes every suffix accepted by the supplied modes, for use in log and failure
     * messages.
     *
     * @param modes the modes in play
     * @return the accepted suffixes, comma separated
     */
    public static String describeSuffixes(Collection<ProductizationFlavor> modes) {
        return modes.stream()
                .flatMap(mode -> mode.suffixes().stream())
                .collect(Collectors.joining(", "));
    }

}
