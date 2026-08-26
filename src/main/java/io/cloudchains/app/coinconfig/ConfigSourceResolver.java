package io.cloudchains.app.coinconfig;

import java.util.Locale;

/**
 * Resolves the blockchain-configuration-files source from, in order of
 * precedence: CLI flag {@code --blockchain-configuration-files}, environment
 * variable {@code BLOCKCHAIN_CONFIGURATION_FILES}, then the upstream
 * blocknetdx default (master branch).
 *
 * <p>A source value is either a local filesystem directory that contains
 * {@code manifest-latest.json} and {@code xbridge-confs/}, or a base URL under
 * which those same relative paths resolve (a raw GitHub URL, or a
 * github.com repo URL which is normalized to its raw form).</p>
 */
public final class ConfigSourceResolver {

    public static final String FLAG_NAME = "--blockchain-configuration-files";
    public static final String ENV_NAME = "BLOCKCHAIN_CONFIGURATION_FILES";
    public static final String DEFAULT_UPSTREAM =
            "https://raw.githubusercontent.com/blocknetdx/blockchain-configuration-files/master";

    private final String flagValue;
    private final String envValue;

    public ConfigSourceResolver(String flagValue, String envValue) {
        this.flagValue = normalize(flagValue);
        this.envValue = normalize(envValue);
    }

    /** @return true when the base is a filesystem directory rather than a URL. */
    public static boolean isLocalDirectory(String base) {
        String lower = base.toLowerCase(Locale.ROOT);
        return !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    /** @return the resolved source value (never blank); local dir or base URL. */
    public String resolve() {
        if (flagValue != null)
            return toBase(flagValue);
        if (envValue != null)
            return toBase(envValue);
        return DEFAULT_UPSTREAM;
    }

    /**
     * Normalizes a github.com web URL ({@code github.com/o/r} or
     * {@code .../tree/branch}) to its raw base; other values pass through.
     */
    static String toBase(String value) {
        String v = normalize(value);
        if (v == null)
            throw new IllegalArgumentException(FLAG_NAME + ": empty config source");
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://github.com/") || lower.startsWith("http://github.com/")) {
            int hostIdx = lower.indexOf("github.com/") + "github.com/".length();
            String rest = v.substring(hostIdx);
            String lowerRest = lower.substring(hostIdx);
            while (lowerRest.endsWith("/") || rest.endsWith("/")) {
                lowerRest = lowerRest.replaceAll("/+$", "");
                rest = rest.replaceAll("/+$", "");
            }
            if (lowerRest.endsWith(".git")) {
                lowerRest = lowerRest.substring(0, lowerRest.length() - ".git".length());
                rest = rest.substring(0, rest.length() - ".git".length());
            }
            String branch = "master";
            for (String marker : new String[]{"/tree/", "/blob/"}) {
                int idx = lowerRest.indexOf(marker);
                if (idx >= 0) {
                    branch = rest.substring(idx + marker.length());
                    rest = rest.substring(0, idx);
                    if (branch.isEmpty() || branch.contains("/"))
                        throw new IllegalArgumentException(
                                "unsupported github URL form '" + value
                                        + "' (expected github.com/<owner>/<repo>[.git][/tree/<branch>])");
                    break;
                }
            }
            String scheme = lower.startsWith("http://") ? "http://" : "https://";
            return scheme + "raw.githubusercontent.com/" + rest + "/" + branch;
        }
        return v.replaceAll("/+$", "");
    }

    static String normalize(String v) {
        if (v == null)
            return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
