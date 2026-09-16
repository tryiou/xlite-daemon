package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSourceResolverTest {

    private static final String UPSTREAM =
            "https://raw.githubusercontent.com/blocknetdx/blockchain-configuration-files/master";

    @Test
    void testFlagWinsOverEnvOverDefault() {
        assertEquals("/opt/bcf",
                new ConfigSourceResolver("/opt/bcf", "/env/bcf").resolve());
        assertEquals("/env/bcf",
                new ConfigSourceResolver(null, "/env/bcf").resolve());
        assertEquals(UPSTREAM,
                new ConfigSourceResolver(null, null).resolve());
        assertEquals(UPSTREAM,
                new ConfigSourceResolver("   ", "").resolve());
    }

    @Test
    void testBlankValuesFallThrough() {
        assertEquals("/env/bcf", new ConfigSourceResolver("", "  /env/bcf ").resolve());
    }

    @Test
    void testTrailingSlashesTrimmed() {
        assertEquals("/opt/bcf", new ConfigSourceResolver("/opt/bcf///", null).resolve());
        assertEquals(UPSTREAM, new ConfigSourceResolver(UPSTREAM + "/", null).resolve());
    }

    @Test
    void testGithubRepoUrlNormalizedToRawMaster() {
        assertEquals(UPSTREAM,
                new ConfigSourceResolver("https://github.com/blocknetdx/blockchain-configuration-files", null).resolve());
    }

    @Test
    void testGithubTreeUrlNormalizedToRawBranch() {
        assertEquals("https://raw.githubusercontent.com/blocknetdx/blockchain-configuration-files/develop",
                new ConfigSourceResolver("https://github.com/blocknetdx/blockchain-configuration-files/tree/develop", null).resolve());
    }

    @Test
    void testGithubDotGitSuffixStripped() {
        assertEquals(UPSTREAM,
                new ConfigSourceResolver("https://github.com/blocknetdx/blockchain-configuration-files.git", null).resolve());
    }

    @Test
    void testGithubBlobUrlNormalized() {
        assertEquals("https://raw.githubusercontent.com/blocknetdx/blockchain-configuration-files/master",
                new ConfigSourceResolver("https://github.com/blocknetdx/blockchain-configuration-files/blob/master", null).resolve());
    }

    @Test
    void testUppercaseSchemeNormalized() {
        assertEquals(UPSTREAM,
                new ConfigSourceResolver("HTTPS://GitHub.com/blocknetdx/blockchain-configuration-files", null).resolve());
    }

    @Test
    void testDeepGithubPathFailsHard() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ConfigSourceResolver(
                        "https://github.com/blocknetdx/blockchain-configuration-files/tree/develop/src", null)
                        .resolve());
        assertTrue(e.getMessage().contains("unsupported github URL form"), e.getMessage());
    }

    @Test
    void testUppercaseSchemeIsRemoteNotLocalDir() {
        assertFalse(ConfigSourceResolver.isLocalDirectory("HTTP://127.0.0.1:8080/bcf"));
    }

    @Test
    void testPlainHttpKept() {
        assertEquals("http://127.0.0.1:8080/bcf",
                new ConfigSourceResolver("http://127.0.0.1:8080/bcf", null).resolve());
    }

    @Test
    void testLocalDirectoryDetectedAsNonUrl() {
        assertTrue(ConfigSourceResolver.isLocalDirectory("/opt/bcf"));
        assertFalse(ConfigSourceResolver.isLocalDirectory(UPSTREAM));
    }
}
