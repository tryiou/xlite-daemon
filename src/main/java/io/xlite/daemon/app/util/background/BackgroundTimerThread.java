package io.xlite.daemon.app.util.background;

import io.xlite.daemon.app.App;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeerGroup;
import io.xlite.daemon.app.util.LogRotationUtil;
import io.xlite.daemon.app.util.XRouterConfiguration;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class BackgroundTimerThread implements Runnable {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    public static final boolean HTTP_BLOCK_COUNT_UPDATES = true;
    public static final boolean HTTP_BALANCE_UPDATES = true;

    private static final int KEEPALIVE_INTERVAL = 10000;

    private BlocknetPeerGroup blocknetPeerGroup;
    private HTTPClient heightUpdateHttpClient;

    private long lastKeepAliveTime;

    private long lastOut;
    private volatile boolean shutdownRequested = false;
    private volatile Thread workerThread;

    private Set<String> lastAvailable = new HashSet<>();
    private Set<String> lastUnavailable = new HashSet<>();

    // Daemon-factory scheduler: the wrapper thread started by ConsoleMenu
    // is the deliberate sole non-daemon anchor of this application. Worker
    // threads must never outlive it as liveness anchors — stop() still
    // releases the scheduler explicitly, but daemon status makes the
    // single-anchor invariant structural rather than remembered.
    private ScheduledExecutorService logRotationScheduler;
    private static final int DAILY_ROTATION_HOUR = 2; // 2:00 AM
    private static final int DAILY_ROTATION_MINUTE = 0;

    public BackgroundTimerThread() {
        blocknetPeerGroup =
                CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork())
                        .getBlocknetPeerGroup();
        heightUpdateHttpClient = App.getHeightUpdateHttpClient();

        lastKeepAliveTime = 0;

        lastOut = 0;

        // Initialize log rotation scheduler
        initializeLogRotationScheduler();
    }

    /** Initializes the log rotation scheduler to run daily at 2:00 AM. */
    private void initializeLogRotationScheduler() {
        logRotationScheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "background-timer-log-rotation");
                    t.setDaemon(true);
                    return t;
                });
        long initialDelay = calculateInitialDelay();
        logRotationScheduler.scheduleAtFixedRate(
                this::performDailyLogRotation, initialDelay, 24, TimeUnit.HOURS);
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        LOGGER.info(
                "[BackgroundTimer] Scheduled daily log rotation at "
                        + String.format("%02d:%02d", DAILY_ROTATION_HOUR, DAILY_ROTATION_MINUTE)
                        + " (current time: "
                        + String.format("%02d:%02d", now.getHour(), now.getMinute())
                        + ")");
    }

    /**
     * Calculates the initial delay until the next scheduled log rotation at 2:00 AM.
     *
     * @return Delay in milliseconds until next 2:00 AM
     */
    private long calculateInitialDelay() {
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        LocalTime targetTime = LocalTime.of(DAILY_ROTATION_HOUR, DAILY_ROTATION_MINUTE);
        long delay;
        if (now.isBefore(targetTime)) {
            delay = Duration.between(now, targetTime).toMillis();
        } else {
            delay = Duration.between(now, targetTime.plusHours(24)).toMillis();
        }
        return Math.max(delay, 0);
    }

    /** Performs the daily log rotation task. Called by the scheduler every 24 hours at 2:00 AM. */
    private void performDailyLogRotation() {
        try {
            LOGGER.info("[BackgroundTimer] Starting scheduled daily log rotation");
            LogRotationUtil.performLogRotation();
            LOGGER.info("[BackgroundTimer] Daily log rotation completed successfully");
        } catch (Exception e) {
            LOGGER.severe(
                    "[BackgroundTimer] Failed to perform daily log rotation: " + e.getMessage());
        }
    }

    public void stop() {
        shutdownRequested = true;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (logRotationScheduler != null && !logRotationScheduler.isShutdown()) {
            logRotationScheduler.shutdown();
            try {
                if (!logRotationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logRotationScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                logRotationScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void outputAvailableCurrencies() {
        long elapsed = (System.currentTimeMillis() - lastOut);

        if (elapsed < 60 * 1000 && lastOut != 0) {
            return;
        }

        List<String> available = new ArrayList<>();
        Set<String> unavailable = new HashSet<>();

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker())) {
                continue;
            }

            String name = CoinTickerUtils.tickerToString(coinInstance.getTicker());
            if (CoinInstance.getBlockCountByTicker(coinInstance.getTicker()) > 0) {
                available.add(name);
            } else {
                unavailable.add(name);
            }
        }

        Set<String> currentAvailable = new HashSet<>(available);
        if (!currentAvailable.equals(lastAvailable)) {
            LOGGER.info("[coin] Available: " + String.join(", ", available));
        }
        lastAvailable = currentAvailable;

        if (!unavailable.isEmpty() && !unavailable.equals(lastUnavailable)) {
            LOGGER.info("[coin] Unavailable: " + String.join(", ", unavailable));
        }
        lastUnavailable = unavailable;
        lastOut = System.currentTimeMillis();
    }

    private void sendKeepAlive() {
        long elapsed = (System.currentTimeMillis() - lastKeepAliveTime);

        if (elapsed < KEEPALIVE_INTERVAL && lastKeepAliveTime != 0) {
            return;
        }

        if (HTTP_BLOCK_COUNT_UPDATES) {
            heightUpdateHttpClient.getAllBlockCounts();
        } else if (!blocknetPeerGroup.getConnectedPeers().isEmpty()) {
            for (BlocknetPeer blocknetPeer : blocknetPeerGroup.getConnectedPeers()) {
                XRouterConfiguration xRouterConfiguration = blocknetPeer.getxRouterConfiguration();
                if (xRouterConfiguration == null) {
                    continue;
                }

                for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
                    if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker())) {
                        continue;
                    } else if (!blocknetPeer
                            .getxRouterConfiguration()
                            .getSupportedWallets()
                            .contains(coinInstance.getNetworkParameters().getId())) {
                        continue;
                    }

                    coinInstance.sendXrGetBlockCount(blocknetPeer);
                    LOGGER.finer(
                            "[BackgroundTimer] Sent keepalive message: "
                                    + coinInstance.getNetworkParameters().getId());
                }
            }
        } else {
            return;
        }

        lastKeepAliveTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        workerThread = Thread.currentThread();
        LOGGER.finer("[BackgroundTimer] Waiting until initial messages are sent off.");

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker())) {
                continue;
            }

            Thread historyThread =
                    new Thread(
                            () -> {
                                App.getFeeUpdateHttpClient()
                                        .getHistory(
                                                coinInstance.getTicker(),
                                                0,
                                                (int) System.currentTimeMillis(),
                                                30000);
                            },
                            "history-fetch-"
                                    + CoinTickerUtils.tickerToString(coinInstance.getTicker()));
            // Daemon: a blocking initial-history fetch must never keep the
            // JVM alive past shutdown.
            historyThread.setDaemon(true);
            historyThread.start();
        }

        while (!Thread.currentThread().isInterrupted()) {
            if (shutdownRequested) {
                break;
            }
            try {
                sendKeepAlive();
                outputAvailableCurrencies();

                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            } catch (NullPointerException e) {
                LOGGER.warning("[BackgroundTimer] Null pointer: " + e.getMessage());
            } catch (Exception e) {
                LOGGER.warning("[BackgroundTimer] Unexpected error: " + e.getMessage());
            }
        }
    }
}
