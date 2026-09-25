package io.xlite.daemon.app;

import io.xlite.daemon.app.util.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Precedence matrix for {@link App#resolveUserConfigDir(String, String, String, String)}.
 * <p>{@code XLITE_DATA_HOME} (non-blank, trimmed, absolute-normalized) must win over
 * every per-OS default; blank/null env must fall through to the platform root.</p>
 */
class AppTest {

    private static final String USER_HOME = "/home/tester";
    private static final String APPDATA = "C:\\Users\\tester\\AppData\\Roaming";

    @TempDir
    static Path sandbox;
    private static String savedConfigDir;

    /**
     * Isolate from the real home dir: touching {@code App} triggers class
     * init, which builds live objects against {@code CONFIG_DIR}. Without
     * this, the suite reads (and previously even migrated) the developer's
     * real {@code ~/.config}.
     */
    @BeforeAll
    static void isolateConfigDir() {
        savedConfigDir = ConfigHelper.CONFIG_DIR;
        ConfigHelper.CONFIG_DIR = sandbox.toString();
    }

    @AfterAll
    static void restoreConfigDir() {
        ConfigHelper.CONFIG_DIR = savedConfigDir;
    }

    @Test
    @DisplayName("XLITE_DATA_HOME overrides the linux default")
    void testResolve_EnvOverrideWinsOnLinux() {
        assertEquals("/tmp/xlite-sandbox/data",
                App.resolveUserConfigDir("/tmp/xlite-sandbox/data", "Linux", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("XLITE_DATA_HOME overrides the windows default")
    void testResolve_EnvOverrideWinsOnWindows() {
        // expectation built through File so the assertion holds on any host OS
        File expected = new File("/tmp/xlite-sandbox/data").getAbsoluteFile();
        assertEquals(expected.getPath(),
                App.resolveUserConfigDir("/tmp/xlite-sandbox/data", "Windows 11", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("XLITE_DATA_HOME overrides the mac default")
    void testResolve_EnvOverrideWinsOnMac() {
        File expected = new File("/tmp/xlite-sandbox/data").getAbsoluteFile();
        assertEquals(expected.getPath(),
                App.resolveUserConfigDir("/tmp/xlite-sandbox/data", "Mac OS X", USER_HOME, null));
    }

    @Test
    @DisplayName("blank or null env falls back to per-OS defaults (real-world os.name casing)")
    void testResolve_BlankEnvFallsBackToPlatformDefaults() {
        for (String blank : new String[]{null, "", "   "}) {
            assertEquals(USER_HOME + File.separator + ".config",
                    App.resolveUserConfigDir(blank, "Linux", USER_HOME, APPDATA));
            assertEquals(APPDATA,
                    App.resolveUserConfigDir(blank, "Windows 11", USER_HOME, APPDATA));
            assertEquals(USER_HOME + File.separator + "Library" + File.separator + "Application Support",
                    App.resolveUserConfigDir(blank, "Mac OS X", USER_HOME, null));
        }
    }

    @Test
    @DisplayName("unknown os.name falls back to the linux-style default")
    void testResolve_UnknownOsFallsBackToDotConfig() {
        assertEquals(USER_HOME + File.separator + ".config",
                App.resolveUserConfigDir(null, "sunos", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("padded override is trimmed before resolution")
    void testResolve_PaddedEnvIsTrimmed() {
        File expected = new File("/tmp/xlite-sandbox/data").getAbsoluteFile();
        assertEquals(expected.getPath(),
                App.resolveUserConfigDir("  /tmp/xlite-sandbox/data  ", "Linux", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("relative override is normalized against the working directory")
    void testResolve_RelativeEnvNormalizedToAbsolute() {
        String resolved = App.resolveUserConfigDir("relative/sandbox", "Linux", USER_HOME, APPDATA);
        assertTrue(resolved.startsWith(new File("").getAbsolutePath()),
                "must resolve under the working directory, got: " + resolved);
        assertTrue(resolved.endsWith("relative" + File.separator + "sandbox"),
                "must keep the relative segments, got: " + resolved);
    }

    @Test
    @DisplayName("windows default without AppData fails fast instead of yielding a null dir")
    void testResolve_WindowsDefaultWithoutAppDataThrows() {
        for (String blank : new String[]{null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> App.resolveUserConfigDir(null, "Windows 11", USER_HOME, blank));
            assertTrue(e.getMessage().contains("XLITE_DATA_HOME"),
                    "message must point at the override, got: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("mac/linux default without user.home fails fast instead of yielding a null dir")
    void testResolve_NonWindowsDefaultWithoutUserHomeThrows() {
        for (String blank : new String[]{null, "", "   "}) {
            assertThrows(IllegalStateException.class,
                    () -> App.resolveUserConfigDir(null, "Linux", blank, APPDATA));
            assertThrows(IllegalStateException.class,
                    () -> App.resolveUserConfigDir(null, "Mac OS X", blank, null));
        }
    }
}
