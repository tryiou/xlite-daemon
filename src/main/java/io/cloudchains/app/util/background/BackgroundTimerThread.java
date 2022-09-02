package io.cloudchains.app.util.background;

import io.cloudchains.app.App;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeerGroup;
import io.cloudchains.app.util.XRouterConfiguration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

	public BackgroundTimerThread() {
		blocknetPeerGroup = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork()).getBlocknetPeerGroup();
		feeUpdateHttpClient = App.feeUpdateHttpClient;
		heightUpdateHttpClient = App.heightUpdateHttpClient;

		lastKeepAliveTime = 0;
		lastBalanceUpdateTime = 0;

		lastOut = 0;
	}

	public void stop() {
        shutdownRequested = true;
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
						continue;
					else if (!blocknetPeer.getxRouterConfiguration().getSupportedWallets().contains(coinInstance.getNetworkParameters().getId()))
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
				App.feeUpdateHttpClient.getTransactionHistory(coinInstance.getTicker(), 0, (int) System.currentTimeMillis(), 30000);
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
