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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
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
        LOGGER.log(Level.INFO, "[BackgroundTimer] Scheduled daily log rotation at {0} (current time: {1})",
                new Object[]{String.format("%02d:%02d", DAILY_ROTATION_HOUR, DAILY_ROTATION_MINUTE),
                        String.format("%02d:%02d", now.getHour(), now.getMinute())});
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
            LOGGER.log(Level.INFO, "[BackgroundTimer] Starting scheduled daily log rotation");
            LogRotationUtil.performLogRotation();
            LOGGER.log(Level.INFO, "[BackgroundTimer] Daily log rotation completed successfully");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[BackgroundTimer] Failed to perform daily log rotation", e);
        }
    }

    public void stop() {
        shutdownRequested = true;
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

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            if (!CoinTickerUtils.isActiveTicker(coinInstance.getTicker()))
                continue;

            if (CoinInstance.getBlockCountByTicker(coinInstance.getTicker()) > 0) {
                LOGGER.log(Level.INFO, "[coin] Available Currency: " + CoinTickerUtils.tickerToString(coinInstance.getTicker()));
            }
        }

        lastOut = System.currentTimeMillis();
    }

    private void sendKeepAlive() {
        long elapsed = (System.currentTimeMillis() - lastKeepAliveTime);

        if (elapsed < KEEPALIVE_INTERVAL && lastKeepAliveTime != 0)
            return;

        if (HTTP_BLOCK_COUNT_UPDATES) {
            heightUpdateHttpClient.getAllBlockCounts();
            feeUpdateHttpClient.getAllFees();
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
                    LOGGER.log(Level.FINER, "[BackgroundTimer] Sent keepalive message: " + coinInstance.getNetworkParameters().getId());
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
                LOGGER.log(Level.FINER, "[BackgroundTimer] Peer was not found for currency " + coinInstance.getNetworkParameters().getId());
                continue;
            }

            coinInstance.sendXrGetUtxos(blocknetPeer);
            LOGGER.log(Level.FINER, "[BackgroundTimer] Sent GetUtxos message: " + coinInstance.getNetworkParameters().getId());
        }

        lastBalanceUpdateTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        LOGGER.log(Level.FINER, "[BackgroundTimer] Waiting until initial messages are sent off.");

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
            } catch (NullPointerException e) {
                e.printStackTrace();
            } catch (Exception e) {
                LOGGER.log(Level.FINER, "[BackgroundTimer] Interrupted thread");
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }
}
