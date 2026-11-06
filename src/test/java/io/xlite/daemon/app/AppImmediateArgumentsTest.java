package io.xlite.daemon.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.xlite.daemon.app.util.ConfigHelper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * {@code --version} and {@code --help} are answered before startup touches the data directory.
 *
 * <p>They used to construct the master listener first, whose static configuration creates {@code
 * <data-home>/xlite-daemon} and writes credentials. Printing a version must not have that side
 * effect, so each case runs in a forked JVM with an empty data home and asserts the directory is
 * still absent afterwards.
 */
class AppImmediateArgumentsTest {

    @TempDir Path dataHome;

    @Test
    void versionIsPrintedWithoutCreatingTheDataDirectory() throws Exception {
        Result result = run("--version");

        assertEquals(0, result.exitCode(), result.stderr);
        assertEquals(Version.CLIENT_VERSION, result.stdout().trim());
        assertNoDataDirectory();
    }

    @Test
    void helpIsPrintedWithoutCreatingTheDataDirectory() throws Exception {
        Result result = run("--help");

        assertEquals(0, result.exitCode(), result.stderr);
        assertTrue(
                result.stdout().contains("Usage: xlite-daemon"),
                "help must be on stdout, got: " + result.stdout());
        assertNoDataDirectory();
    }

    private void assertNoDataDirectory() {
        Path dataDirectory = dataHome.resolve(ConfigHelper.DATA_DIR_NAME);
        assertFalse(
                Files.exists(dataDirectory),
                "immediate arguments must not create " + dataDirectory);
    }

    private Result run(String argument) throws Exception {
        String executable =
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        File java = new File(new File(System.getProperty("java.home"), "bin"), executable);
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));

        ProcessBuilder builder =
                new ProcessBuilder(
                        java.getAbsolutePath(), "-cp", classpath, App.class.getName(), argument);
        builder.environment().put("XLITE_DATA_HOME", dataHome.toString());
        // Provider overrides are unrelated to argument printing; keep the child neutral.
        builder.environment().remove("EXR_ENDPOINT");
        builder.environment().remove("ELECTRUM_ENDPOINT");
        builder.environment().remove("BLOCKCHAIN_CONFIGURATION_FILES");

        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child process did not exit");
        return new Result(process.exitValue(), stdout, stderr);
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
