package io.cloudchains.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Precedence matrix for {@link App#resolveUserConfigDir(String, String, String, String)}.
 * <p>{@code XLITE_DATA_HOME} (non-blank) must win over every per-OS default;
 * blank/null env must fall through to the platform root unchanged.</p>
 */
class AppTest {

    private static final String USER_HOME = "/home/tester";
    private static final String APPDATA = "C:\\Users\\tester\\AppData\\Roaming";

    @Test
    @DisplayName("XLITE_DATA_HOME overrides the linux default")
    void testResolve_EnvOverrideWinsOnLinux() {
        assertEquals("/tmp/xlite-sandbox/data",
                App.resolveUserConfigDir("/tmp/xlite-sandbox/data", "linux", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("XLITE_DATA_HOME overrides the windows default")
    void testResolve_EnvOverrideWinsOnWindows() {
        assertEquals("/tmp/xlite-sandbox/data",
                App.resolveUserConfigDir("/tmp/xlite-sandbox/data", "windows 10", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("XLITE_DATA_HOME overrides the mac default")
    void testResolve_EnvOverrideWinsOnMac() {
        assertEquals("/tmp/xlite-sandbox/data",
                App.resolveUserConfigDir("/tmp/xlite-sandbox/data", "mac os x", USER_HOME, null));
    }

    @Test
    @DisplayName("blank or null env falls back to per-OS defaults")
    void testResolve_BlankEnvFallsBackToPlatformDefaults() {
        for (String blank : new String[]{null, "", "   "}) {
            assertEquals(USER_HOME + File.separator + ".config",
                    App.resolveUserConfigDir(blank, "linux", USER_HOME, APPDATA));
            assertEquals(APPDATA,
                    App.resolveUserConfigDir(blank, "windows 10", USER_HOME, APPDATA));
            assertEquals(USER_HOME + File.separator + "Library" + File.separator + "Application Support",
                    App.resolveUserConfigDir(blank, "mac os x", USER_HOME, null));
        }
    }

    @Test
    @DisplayName("unknown os.name falls back to the linux-style default")
    void testResolve_UnknownOsFallsBackToDotConfig() {
        assertEquals(USER_HOME + File.separator + ".config",
                App.resolveUserConfigDir(null, "sunos", USER_HOME, APPDATA));
    }

    @Test
    @DisplayName("override is normalized to an absolute path")
    void testResolve_RelativeEnvNormalizedToAbsolute() {
        String resolved = App.resolveUserConfigDir("relative/sandbox", "linux", USER_HOME, APPDATA);
        assertEquals(new File("relative/sandbox").getAbsoluteFile().getPath(), resolved);
    }

    @Test
    @DisplayName("windows default keeps legacy verbatim pass-through of AppData (may be null)")
    void testResolve_WindowsDefaultPassesAppDataVerbatim() {
        assertNull(App.resolveUserConfigDir(null, "windows 10", USER_HOME, null));
    }
}
