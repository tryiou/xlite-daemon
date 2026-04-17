package io.cloudchains.app.util.background;

import io.cloudchains.app.App;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeerGroup;
import io.cloudchains.app.util.LogRotationUtil;
import io.cloudchains.app.util.XRouterConfiguration;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class BackgroundTimerThread implements Runnable {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    public static final boolean HTTP_BLOCK_COUNT_UPDATES = true;
    public static final boolean HTTP_BALANCE_UPDATES = true;

    private static final int KEEPALIVE_INTERVAL = 10000;
    private static final int BALANCE_INTERVAL = 10000;

    private ExecutorService threadPool = Executors.newSingleThreadExecutor();

    private BlocknetPeerGroup blocknetPeerGroup;
    private HTTPClient feeUpdateHttpClient;
    private HTTPClient heightUpdateHttpClient;

    private long lastKeepAliveTime;
    private long lastBalanceUpdateTime;

    private long lastOut;
    private boolean shutdownRequested = false;
    private volatile Thread workerThread;

    private Set<String> lastAvailable = new HashSet<>();
    private Set<String> lastUnavailable = new HashSet<>();

    // Log rotation scheduler fields
    private ScheduledExecutorService logRotationScheduler;
    private static final int DAILY_ROTATION_HOUR = 2; // 2:00 AM
    private static final int DAILY_ROTATION_MINUTE = 0;

    public BackgroundTimerThread() {
        blocknetPeerGroup = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork()).getBlocknetPeerGroup();
        feeUpdateHttpClient = App.feeUpdateHttpClient;
        heightUpdateHttpClient = App.heightUpdateHttpClient;

        lastKeepAliveTime = 0;
        lastBalanceUpdateTime = 0;

        lastOut = 0;

        // Initialize log rotation scheduler
        initializeLogRotationScheduler();
    }

    /**
     * Initializes the log rotation scheduler to run daily at 2:00 AM.
     */
    private void initializeLogRotationScheduler() {
        logRotationScheduler = Executors.newSingleThreadScheduledExecutor();
        long initialDelay = calculateInitialDelay();
        logRotationScheduler.scheduleAtFixedRate(
                this::performDailyLogRotation,
                initialDelay,
                24, TimeUnit.HOURS
        );
        LocalTime now = LocalTime.now();
        LOGGER.info("[BackgroundTimer] Scheduled daily log rotation at " + String.format("%02d:%02d", DAILY_ROTATION_HOUR, DAILY_ROTATION_MINUTE) + " (current time: " + String.format("%02d:%02d", now.getHour(), now.getMinute()) + ")");
    }

    /**
     * Calculates the initial delay until the next scheduled log rotation at 2:00 AM.
     *
     * @return Delay in milliseconds until next 2:00 AM
     */
    private long calculateInitialDelay() {
        LocalTime now = LocalTime.now();
        LocalTime targetTime = LocalTime.of(DAILY_ROTATION_HOUR, DAILY_ROTATION_MINUTE);
        long delay;
        if (now.isBefore(targetTime)) {
            delay = Duration.between(now, targetTime).toMillis();
        } else {
            delay = Duration.between(now, targetTime.plusHours(24)).toMillis();
        }
        return Math.max(delay, 0);
    }

    /**
     * Performs the daily log rotation task.
     * Called by the scheduler every 24 hours at 2:00 AM.
     */
    private void performDailyLogRotation() {
        try {
            LOGGER.info("[BackgroundTimer] Starting scheduled daily log rotation");
            LogRotationUtil.performLogRotation();
            LOGGER.info("[BackgroundTimer] Daily log rotation completed successfully");
        } catch (Exception e) {
            LOGGER.severe("[BackgroundTimer] Failed to perform daily log rotation: " + e.getMessage());
        }
    }

    public void stop() {
        shutdownRequested = true;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
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

        if (elapsed < 60 * 1000 && lastOut != 0)
            return;

        List<String> available = new ArrayList<>();
        Set<String> unavailable = new HashSet<>();

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker()))
                continue;

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

        if (elapsed < KEEPALIVE_INTERVAL && lastKeepAliveTime != 0)
            return;

        if (HTTP_BLOCK_COUNT_UPDATES) {
            heightUpdateHttpClient.getAllBlockCounts();
        } else if (!blocknetPeerGroup.getConnectedPeers().isEmpty()) {
            for (BlocknetPeer blocknetPeer : blocknetPeerGroup.getConnectedPeers()) {
                XRouterConfiguration xRouterConfiguration = blocknetPeer.getxRouterConfiguration();
                if (xRouterConfiguration == null)
                    continue;

                for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
                    if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker()))
                        continue;else if (!blocknetPeer.getxRouterConfiguration().getSupportedWallets().contains(coinInstance.getNetworkParameters().getId()))
                        continue;

                    coinInstance.sendXrGetBlockCount(blocknetPeer);
                    LOGGER.finer("[BackgroundTimer] Sent keepalive message: " + coinInstance.getNetworkParameters().getId());
                }
            }
        } else {
            return;
        }

        lastKeepAliveTime = System.currentTimeMillis();
    }

    private void sendBalanceUpdate() {
        long elapsed = (System.currentTimeMillis() - lastBalanceUpdateTime);

        if (elapsed < BALANCE_INTERVAL && lastBalanceUpdateTime != 0)
            return;

        // No longer polling balances and transaction history here. Instead it is requested
        // on demand when client requests the data. See HTTPServerHandler.java:302-330

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker()))
                continue;

            if (CoinInstance.getBlockCountByTicker(coinInstance.getTicker()) <= 0) {
                continue;
            }

            if (blocknetPeerGroup.getConnectedPeers().isEmpty()) {
                return;
            }

            BlocknetPeer blocknetPeer = blocknetPeerGroup.getBestBlocknetPeer(coinInstance.getNetworkParameters().getId());
            if (blocknetPeer == null) {
                LOGGER.finer("[BackgroundTimer] Peer was not found for currency " + coinInstance.getNetworkParameters().getId());
                continue;
            }

            coinInstance.sendXrGetUtxos(blocknetPeer);
            LOGGER.finer("[BackgroundTimer] Sent GetUtxos message: " + coinInstance.getNetworkParameters().getId());
        }

        lastBalanceUpdateTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        workerThread = Thread.currentThread();
        LOGGER.finer("[BackgroundTimer] Waiting until initial messages are sent off.");

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker()))
                continue;

            new Thread(() -> {
                App.feeUpdateHttpClient.getHistory(coinInstance.getTicker(), 0, (int) System.currentTimeMillis(), 30000);
            }).start();
        }

        while (!Thread.currentThread().isInterrupted()) {
            if (shutdownRequested)
                break;
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
