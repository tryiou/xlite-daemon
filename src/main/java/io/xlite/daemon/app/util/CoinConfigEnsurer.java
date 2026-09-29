package io.xlite.daemon.app.util;

import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Self-healing for coin RPC configs on login. For every supported coin whose
 * {@code settings/config-<T>.json} is missing (newly listed coins on stale
 * datadirs), empty, or corrupt, a first-run-style config is created so the
 * coin joins the normal init path. A present, parseable config — enabled or
 * explicitly disabled — is NEVER modified: existence (not content) is the
 * only discriminator, because "disabled on purpose" and "never set" are
 * undecidable from content.
 *
 * <p>Outcome per coin, returned to the caller for logging:</p>
 * <ul>
 *   <li>{@code CREATED} — file was absent/empty; fresh enabled config written</li>
 *   <li>{@code PRESENT_UNTOUCHED} — parseable file; byte-identical afterwards</li>
 *   <li>{@code CORRUPT_QUARANTINED} — unparseable bytes moved to
 *       {@code config-<T>.json.corrupt-<epoch>} (original preserved), then
 *       a fresh enabled config written</li>
 *   <li>{@code SKIPPED} — datadir unusable; logged, login continues</li>
 * </ul>
 *
 * <p>Single-threaded call sites only (login flow, before the coin-init pool
 * starts). Cross-process double-login races resolve as last-writer-wins with
 * equivalent fresh content.</p>
 */
public final class CoinConfigEnsurer {

    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Per-coin outcome of one ensure pass. */
    public enum Outcome {
        CREATED,
        PRESENT_UNTOUCHED,
        CORRUPT_QUARANTINED,
        SKIPPED
    }

    private CoinConfigEnsurer() {
    }

    /**
     * Ensures a usable config for every given ticker. Never throws: per-coin
     * failures are reported as {@link Outcome#SKIPPED}.
     */
    public static Map<CoinTicker, Outcome> ensureMissing(Collection<CoinTicker> tickers) {
        Map<CoinTicker, Outcome> report = new EnumMap<>(CoinTicker.class);
        for (CoinTicker ticker : tickers) {
            String tickerStr = CoinTickerUtils.tickerToString(ticker);
            if (tickerStr == null) {
                LOGGER.warning("[config-ensurer] No string mapping for " + ticker + ", skipping");
                report.put(ticker, Outcome.SKIPPED);
                continue;
            }
            try {
                report.put(ticker, ensureOne(tickerStr));
            } catch (Exception e) {
                LOGGER.severe("[config-ensurer] Cannot ensure config for " + tickerStr
                        + " (datadir unusable?), coin will stay disabled: " + e.getMessage());
                report.put(ticker, Outcome.SKIPPED);
            }
        }
        return report;
    }

    private static Outcome ensureOne(String tickerStr) throws IOException {
        if (!ConfigHelper.configExists(tickerStr)) {
            writeFresh(tickerStr);
            LOGGER.info("[config-ensurer] Created missing config for " + tickerStr + " (first-run defaults)");
            return Outcome.CREATED;
        }
        byte[] raw = Files.readAllBytes(ConfigHelper.settingsFile(tickerStr).toPath());
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) {
            // Empty file carries no user intent; treat as missing.
            writeFresh(tickerStr);
            LOGGER.info("[config-ensurer] Recreated empty config for " + tickerStr + " (first-run defaults)");
            return Outcome.CREATED;
        }
        try {
            new JSONObject(text);
        } catch (Exception e) {
            File corrupt = ConfigHelper.settingsFile(tickerStr);
            File quarantine = new File(corrupt.getParentFile(),
                    corrupt.getName() + ".corrupt-" + System.currentTimeMillis());
            Files.write(quarantine.toPath(), raw);
            LOGGER.severe("[config-ensurer] Quarantined corrupt config for " + tickerStr
                    + " to " + quarantine.getName() + ", recreating with first-run defaults");
            writeFresh(tickerStr);
            return Outcome.CORRUPT_QUARANTINED;
        }
        return Outcome.PRESENT_UNTOUCHED;
    }

    /**
     * Writes a first-run config and confirms it landed.
     *
     * <p>{@link ConfigHelper#writeConfig()} returns void: it skips the write
     * when the on-disk text already matches, and it downgrades an
     * {@link java.io.IOException} to a log warning. Treating the call as
     * success therefore reports a repair that never happened -- reachable
     * when the config path exists but is not a regular file, because
     * {@code configExists} uses {@code isFile()} and so reports "missing"
     * for a path that cannot be written as a file. Re-reading the file and
     * parsing it is the only check that cannot be fooled this way.
     *
     * @throws IllegalStateException if the written config is absent or
     *         unparseable, so the caller reports the real outcome
     */
    private static void writeFresh(String tickerStr) {
        ConfigHelper cfg = new ConfigHelper(tickerStr);
        cfg.setRpcUsername(randomToken(24));
        cfg.setRpcPassword(randomToken(32));
        cfg.setRpcEnabled(true);
        cfg.writeConfig();
        // Re-read the bytes rather than trusting the writer: configExists uses
        // isFile(), so a config path that exists but is not a regular file is
        // reported as missing here and then cannot be written as one.
        String onDisk;
        try {
            onDisk = new String(java.nio.file.Files.readAllBytes(
                    ConfigHelper.settingsFile(tickerStr).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("config for " + tickerStr
                    + " was not written: " + e.getMessage(), e);
        }
        if (onDisk.isBlank())
            throw new IllegalStateException("config for " + tickerStr
                    + " was not written to " + ConfigHelper.settingsFile(tickerStr).getAbsolutePath());
        try {
            new JSONObject(onDisk);
        } catch (Exception e) {
            throw new IllegalStateException("config for " + tickerStr
                    + " was written but does not parse: " + e.getMessage(), e);
        }
    }

    /**
     * URL-safe random token for RPC credentials. Single producer shared with
     * the console full-repair path.
     */
    public static String randomToken(int length) {
        SecureRandom secureRandom = new SecureRandom();
        byte[] token = new byte[length];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
