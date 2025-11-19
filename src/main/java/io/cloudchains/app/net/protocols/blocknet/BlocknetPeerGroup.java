package io.cloudchains.app.net.protocols.blocknet;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.*;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.protocols.blocknet.listeners.BlocknetOnXRouterMessageReceivedListener;
import io.cloudchains.app.net.protocols.blocknet.listeners.BlocknetPeerConnectedEventListener;
import io.cloudchains.app.net.protocols.blocknet.listeners.BlocknetPeerDisconnectedEventListener;
import io.cloudchains.app.net.protocols.blocknet.messagequeue.MessageSource;
import io.cloudchains.app.net.protocols.blocknet.messagequeue.QueueItem;
import io.cloudchains.app.net.xrouter.XRouterCommandUtils;
import io.cloudchains.app.net.xrouter.XRouterInitialMessagesSentListener;
import io.cloudchains.app.net.xrouter.XRouterMessage;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.XRouterConfiguration;
import io.cloudchains.app.util.background.BackgroundTimerThread;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkState;

public class BlocknetPeerGroup {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final ListeningScheduledExecutorService executor;

    private final CopyOnWriteArrayList<BlocknetPeer> peers;
    private final CopyOnWriteArrayList<BlocknetPeer> pendingPeers;

    private final ReentrantLock lock = Threading.lock("blocknetpeergroup");
    private final BlocknetBlockingClientManager clientManager;

    private final int maxReconnectsPerHour = 30; // maximum attempts per hour
    private final long reconnectTime = 2; // interval between reconnects

    private CoinInstance blocknetInstance;
    private BlocknetNetworkParameters blocknetNetworkParameters;
    private BlockChain blockChain;
    private ArrayList<BlocknetSeed> blocknetSeeds;

    private Queue<QueueItem> messageQueue = new LinkedBlockingQueue<>();

    private ExecutorService threadPool;

    private AtomicInteger activeConnectionCount;
    
    // Peer discovery system
    private final ConcurrentHashMap<String, DiscoveredBlocknetSeed> discoveredPeers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerQualityScore> peerQualityScores = new ConcurrentHashMap<>();
    private static final long PEER_DISCOVERY_INTERVAL_MS = 2 * 60 * 1000; // 2 minutes
    private static final int MAX_DISCOVERED_PEERS = 50;
    private static final int MIN_QUALITY_SCORE = 30;

    public BlocknetPeerGroup(CoinInstance blocknetInstance, BlocknetNetworkParameters blocknetNetworkParameters, BlockChain chain) {
        this.blocknetInstance = blocknetInstance;
        this.blocknetNetworkParameters = blocknetNetworkParameters;
        this.blockChain = chain;

        peers = new CopyOnWriteArrayList<>();
        pendingPeers = new CopyOnWriteArrayList<>();

        blocknetSeeds = new ArrayList<>();

        for (String ipAddr : blocknetNetworkParameters.getDnsSeeds()) {
            blocknetSeeds.add(new BlocknetSeed(ipAddr, blocknetNetworkParameters.getPort()));
        }

        Collections.shuffle(blocknetSeeds);

        clientManager = new BlocknetBlockingClientManager();
        executor = createPrivateExecutor();

        clientManager.setConnectTimeoutMillis(60000); 

        activeConnectionCount = new AtomicInteger(0);
    }

    private CountDownLatch executorStartupLatch = new CountDownLatch(1);

    protected ListeningScheduledExecutorService createPrivateExecutor() {
        ListeningScheduledExecutorService result = MoreExecutors.listeningDecorator(
                new ScheduledThreadPoolExecutor(1, new ContextPropagatingThreadFactory("BlocknetPeerGroup Thread"))
        );

        result.execute(() -> Uninterruptibles.awaitUninterruptibly(executorStartupLatch));
        return result;
    }

    @GuardedBy("lock")
    private BlocknetPeer createPeer(BlocknetParameters blocknetNetworkParameters, BlockChain chain, BlocknetSeed blocknetSeed) {
    	PeerAddress peerAddress;
    	try {
    		peerAddress = new PeerAddress(blocknetNetworkParameters, InetAddress.getByName(blocknetSeed.getAddress()), blocknetSeed.getPort());
    	} catch (UnknownHostException e) {
    		return null;
    	}
   
    	return new BlocknetPeer(blocknetNetworkParameters,
    			chain,
    			peerAddress,
    			blocknetSeed, this) {};
    }

    @GuardedBy("lock")
    private void connectTo(InetSocketAddress inetSocketAddress, BlocknetPeer blocknetPeer) {
        checkState(lock.isHeldByCurrentThread());

        blocknetPeer.getBlocknetSeed().setActivePeer(true);

        blocknetPeer.setTimeoutEnabled(true);
        blocknetPeer.setSocketTimeout(60000); // 60 seconds for XRouter operations (increased from 30s to prevent premature timeouts)
        LOGGER.log(Level.FINER, "[blocknet-peer-group] DEBUG: Set socket timeout to 60 seconds for peer " + inetSocketAddress.getHostName());

        blocknetPeer.addConnectedEventListener(Threading.SAME_THREAD, startupListener);
        blocknetPeer.addPeerDisconnectedEventListener(startupListener);
        pendingPeers.add(blocknetPeer);

        LOGGER.log(Level.FINER, "[blocknet-peer-group] Attempting to connect to: " + inetSocketAddress.getHostName());

        try {
            ListenableFuture<SocketAddress> future = clientManager.openConnection(inetSocketAddress, blocknetPeer);
            if (future.isDone())
                Uninterruptibles.getUninterruptibly(future);
        } catch (ExecutionException e) {
            e.printStackTrace();
            Throwable cause = Throwables.getRootCause(e);
            handlePeerDeath(blocknetPeer, cause);
        }
    }

    private void startConnections() {
        lock.lock();
        try {
            for (BlocknetSeed blocknetSeed : blocknetSeeds) {
                BlocknetPeer blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, blocknetSeed);

                if (blocknetPeer == null)
                    continue;

                LOGGER.log(Level.FINER, "[blocknet-peer-group] Connecting to " + blocknetPeer.getBlocknetSeed().getAddress() + ":" + blocknetPeer.getBlocknetSeed().getPort());
                connectTo(new InetSocketAddress(blocknetPeer.getBlocknetSeed().getAddress(), blocknetPeer.getBlocknetSeed().getPort()), blocknetPeer);
            }
        } finally {
            lock.unlock();
        }
    }

    private void startBackgroundThreads() {
        threadPool = Executors.newSingleThreadExecutor();
        threadPool.submit(new BackgroundTimerThread());
    }

    private ListenableFuture startAsync() {
        executorStartupLatch.countDown();

        return executor.submit(() -> {
            try {
                clientManager.startAsync();
                clientManager.awaitRunning();
                startConnections();
                startBackgroundThreads();
                scheduleNetworkDiscovery();

                // Schedule immediate discovery attempt after initial connections
                scheduleImmediateDiscovery();

                if (!(BackgroundTimerThread.HTTP_BLOCK_COUNT_UPDATES && BackgroundTimerThread.HTTP_BALANCE_UPDATES))
                    scheduleReconnects();

 //                scheduleMessageQueueRuns();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    public void start() {
        Futures.getUnchecked(startAsync());
    }

    public void stop() {
        try {
            clientManager.stopAsync();
            clientManager.awaitTerminated();
            threadPool.shutdownNow();
        } catch (Exception e) {
            LOGGER.log(Level.FINER, "[coin] ERROR: Error while deinitializing keep alive or balance update thread!");
            e.printStackTrace();
        }
    }

    public void sendMessage(BlocknetPeer blocknetPeer, Message message) {
        if (blocknetPeer == null) {
            LOGGER.log(Level.FINER, "[blocknet-peer-group] BlocknetPeer is null!");
            return;
        }

        blocknetPeer.sendMessage(message);
    }

    private BlocknetPeer getBestConnectedPeer(String currency) {
    	return getConnectedPeers().stream()
    			.filter(
    					e -> (e.getHaveConfig().get() || e.pastConnectionSuccess())
    							&& e.getxRouterConfiguration() != null
    							&& e.getxRouterConfiguration().getSupportedWallets().contains(currency.toUpperCase())
    			)
    			.min(Comparator.comparing(e -> e.getMessagesPendingReply().size()))
    			.orElse(null);
    }

    private BlocknetPeer getBestPendingPeer(String currency) {
    	return getPendingPeers().stream()
    			.filter(
    					e -> (e.getHaveConfig().get() || e.pastConnectionSuccess())
    							&& e.getxRouterConfiguration() != null
    							&& e.getxRouterConfiguration().getSupportedWallets().contains(currency.toUpperCase())
    			)
    			.min(Comparator.comparing(e -> e.getMessagesPendingReply().size()))
    			.orElse(null);
    }

    public BlocknetPeer getBestBlocknetPeer(String currency) {
        BlocknetPeer bestConnectedPeer = getBestConnectedPeer(currency);

        if (bestConnectedPeer == null)
            return getBestPendingPeer(currency);

        return bestConnectedPeer;
    }

    public ArrayList<BlocknetPeer> getConnectedPeers() {
        lock.lock();
        try {
            return new ArrayList<>(peers);
        } finally {
            lock.unlock();
        }
    }

    private ArrayList<BlocknetPeer> getPendingPeers() {
        lock.lock();
        try {
            return new ArrayList<>(pendingPeers);
        } finally {
            lock.unlock();
        }
    }

    private void sendInitialXRouterMessages(BlocknetPeer peer) {
        if (peer.getHaveConfig().get())
            return;

        LOGGER.log(Level.FINER, "[blocknet-peer-group] Sending initial XRouter messages!");

        CoinInstance activeBlocknetNetwork = blocknetInstance;

        XRouterMessage getXRouterConfigMessage = activeBlocknetNetwork.getXRouterPacketManager().getXrGetConfig(
                peer,
                UUID.randomUUID().toString(),
                "self",
                activeBlocknetNetwork.getKeyHandler().getBaseECKey(),
                activeBlocknetNetwork.getKeyHandler().getPublicKey());

        peer.addXRouterMessageReceivedEventListener((message, original) -> {
            Preconditions.checkNotNull(message);
            if (original == null)
                return;

            int originalCmd = original.getXRouterHeader().getCommand();
            String reply = (String) message.getParsedData().get("reply");

            switch (XRouterCommandUtils.commandIdToString(originalCmd)) {
                case "xrGetConfig": {
                    try {
                        JSONObject replyJson = new JSONObject(reply);
                        if (!replyJson.has("config"))
                            break;

                        XRouterConfiguration xRouterConfiguration = new XRouterConfiguration(replyJson.getString("config"));
                        xRouterConfiguration.parseConfig();

                        peer.setxRouterConfiguration(xRouterConfiguration);

                        if (replyJson.has("plugins")) {
                            peer.parsePlugins(replyJson.getJSONObject("plugins"));
                        }

                        // if (peer.getPluginConfig("xrmgetutxos") == null) {
                        //     LOGGER.log(Level.FINER, "[xrouter] ERROR: Node missing required configuration... Falling back to HTTP if no available nodes. ");
                        //     peer.setHasRequiredPlugins(false);
                        // //    peer.close();
                        // }

                    } catch (Exception e) {
                        LOGGER.log(Level.FINER, "[xrouter] ERROR: Error while parsing XRouter config/plugin list!");
                        e.printStackTrace();
                    }

                    if (!peer.getHaveConfig().get()) {
                        peer.setHaveConfig(true);
                        peer.setPastConnectionSuccess(true);
                        
                        String peerAddress = peer.getAddress().toString();
                        LOGGER.log(Level.INFO, "[NETWORK] XRouter configuration received from " + peerAddress);
        
                        for (ListenerRegistration<XRouterInitialMessagesSentListener> registration : peer.getInitialMessagesSentListeners()) {
                            if (registration.executor == Threading.SAME_THREAD) {
                                registration.listener.initialMessagesSent(activeBlocknetNetwork);
                            }
                        }
                    }
                    break;
                }
                case "xrGetBlockCount": {
                    try {
                        String originalTicker = (String) original.getParsedData().get("currency");
                        int blockCount = Integer.parseInt(reply);
                        
                        String peerAddress = peer.getAddress().toString();
                        activeBlocknetNetwork.addBlockCount(CoinTickerUtils.stringToTicker(originalTicker), blockCount);
                        LOGGER.log(Level.INFO, "[XR] SUCCESS: Received " + originalTicker + " block count via XRouter from " +
                                   peerAddress + ": " + blockCount);
                    } catch (Exception e) {
                        String originalTicker = (String) original.getParsedData().get("currency");
                        LOGGER.log(Level.WARNING, "[XR] FAILED: Error parsing " + originalTicker + " block count response from XRouter peer");
                        LOGGER.log(Level.FINER, "[XR] ERROR: Error while parsing XRouter reply to xrGetBlockCount! Dumping reply and stack trace.");
                        LOGGER.log(Level.FINER, reply);
                    }
                    break;
                }
                case "xrService": {
                    String originalCustomCmd = (String) original.getParsedData().get("command");
                    try {
                        JSONObject replyJson = new JSONObject(reply);
                        switch (originalCustomCmd) {
                            case "xrmgetutxos": {
                                if (replyJson.has("error")) {
                                    LOGGER.log(Level.FINER, "[utxo-parser] ERROR: Error while retrieving UTXOs!");
                                    LOGGER.log(Level.FINER, replyJson.getString("error"));
                                    break;
                                }

                                if (!replyJson.has("utxos"))
                                    break;

                                ArrayList originalList = (ArrayList) original.getParsedData().get("params");
                                String originalTicker = (String) originalList.get(0);
                                LOGGER.log(Level.FINER, originalTicker);
                                CoinTicker coinTicker = CoinTickerUtils.stringToTicker(originalTicker.toUpperCase());
                                CoinInstance inst = CoinInstance.getInstance(coinTicker);

                                JSONArray utxosJson = replyJson.getJSONArray("utxos");
                                List<UTXO> utxoList = new ArrayList<>();

                                for (int i = 0; i < utxosJson.length(); i++) {
                                    JSONObject utxoJson = utxosJson.getJSONObject(i);
                                    LOGGER.log(Level.FINER, "[utxo-parser] UTXO " + i + ": " + utxoJson.toString());

                                    String addressB58 = utxoJson.getString("address");
                                    String txid = utxoJson.getString("txhash");
                                    int vout = utxoJson.getInt("vout");
                                    int height = utxoJson.getInt("block_number");
                                    long value = (long) Math.floor(utxoJson.getDouble("value") * 100000000.0);

                                    UTXO utxo = new UTXO(coinTicker, addressB58, txid, vout, height, value);
                                    utxoList.add(utxo);
                                }

                                String peerAddress = peer.getAddress().toString();
                                inst.processUtxos(utxoList);
                                LOGGER.log(Level.INFO, "[XR] SUCCESS: Received " + originalTicker + " UTXOs via XRouter from " +
                                           peerAddress + ": " + utxoList.size() + " UTXOs");
                                break;
                            }
                            case "xrmgetbalance": {
                                LOGGER.log(Level.FINER, "[xrouter] ERROR: xrmgetbalance is not implemented yet!");
                                break;
                            }
                            case "xrmgetrawtransaction":
                            case "xrmgetrawmempool": {
                                break;
                            }
                            default: {
                                LOGGER.log(Level.FINER, "[xrouter] ERROR: Received reply for command we don't recognize! Original custom command: " + originalCustomCmd + ". Dumping reply.");
                                LOGGER.log(Level.FINER, reply);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINER, "[xrouter] ERROR: Error while parsing XRouter reply to xrService! Original custom command: " + originalCustomCmd + ". Dumping reply and stack trace.");
                        LOGGER.log(Level.FINER, reply);
                    }
                    break;
                }
                default: {
                    LOGGER.log(Level.FINER, "[xrouter] WARNING: Core received reply to unexpected packet type. This is probably not a bug. Original command: " + XRouterCommandUtils.commandIdToString(originalCmd));
                    break;
                }
            }
        });

        sendMessage(peer, getXRouterConfigMessage);
    }

    private class PeerStartupListener implements BlocknetPeerConnectedEventListener, BlocknetPeerDisconnectedEventListener {
        @Override
        public void onPeerConnected(BlocknetPeer peer, int peerCount) {
            handleNewPeer(peer, peerCount);
        }

        @Override
        public void onPeerDisconnected(BlocknetPeer peer, int peerCount) {
            handlePeerDeath(peer, null);
        }
    }

    private final PeerStartupListener startupListener = new PeerStartupListener();

    private void handleNewPeer(final BlocknetPeer peer, int peerCount) {
        lock.lock();
        try {
            pendingPeers.remove(peer);
            peers.add(peer);

            peer.addPreMessageReceivedEventListener((thisPeer, message) -> {
                LOGGER.log(Level.FINER, "[blocknet-peer] Message received from peer: " + peer.getAddress());
                if (message instanceof XRouterMessage) {
                    LOGGER.log(Level.FINER, "[blocknet-peer] XRouter message received.");
                }
                return message;
            });

            String peerAddress = peer.getAddress().toString();
            LOGGER.log(Level.INFO, "[NETWORK] XRouter peer connected: " + peerAddress + " (" + (peerCount + 1) + " total peers)");
            LOGGER.log(Level.FINER, "[blocknet-peer-group] DEBUG: New peer connection established.");
            
            // Record successful connection for quality scoring if this is a discovered peer
            if (peer.getBlocknetSeed() instanceof DiscoveredBlocknetSeed) {
                DiscoveredBlocknetSeed discoveredSeed = (DiscoveredBlocknetSeed) peer.getBlocknetSeed();
                // Use a reasonable default latency for now - would need actual timing implementation
                discoveredSeed.recordSuccessfulConnection(100); // Default latency
                LOGGER.log(Level.FINER, "[blocknet-peer-group] Updated quality score for discovered peer: " + peerAddress);
            }
            
            // Check peer configuration
            // if (peer.getPluginConfig("xrmgetutxos") == null) {
            //     LOGGER.log(Level.WARNING, "[XR] FAILED: XRouter peer " + peerAddress + " missing required plugins");
            // } else {
            //     LOGGER.log(Level.FINER, "[XR] XRouter peer " + peerAddress + " ready for requests");
            // }

            // Allow XRouter for Blocknet, Bitcoin, and Litecoin
            if (!blocknetInstance.hasXRouter() &&
            	blocknetInstance.getTicker() != CoinTicker.BITCOIN &&
            	blocknetInstance.getTicker() != CoinTicker.LITECOIN) {
            	LOGGER.log(Level.FINER, "[xrouter] WARNING: This network (" + blocknetInstance.getTicker().toString() + ") does not support XRouter. Will attempt to send XRouter messages over active Blocknet network.");
            	return;
            }

            peer.addInitialMessagesSentListener(new XRouterInitialMessagesSentListener() {
                @Override
                public void initialMessagesSent(CoinInstance instance) {
                    if (instance != blocknetInstance) {
                        return;
                    }

                    peer.removeInitialMessagesSentListener(this);
                }
            });

            if (!peer.getHaveConfig().get()) {
                sendInitialXRouterMessages(peer);
                LOGGER.log(Level.FINER, "[blocknet-peer-group] Sent initial messages to peer: " + peer.getAddress());
            }
            
            // Request discovery immediately after connection for seed nodes and discovered peers
            if (peer.getBlocknetSeed() instanceof DiscoveredBlocknetSeed ||
                blocknetSeeds.indexOf(peer.getBlocknetSeed()) < 3) {
                // Schedule immediate discovery from this peer
                executor.schedule(() -> {
                    if (peer.isActivePeer() && peer.canRequestDiscovery()) {
                        LOGGER.log(Level.INFO, "[blocknet-peer-group] Auto-requesting discovery from peer: " + peer.getAddress());
                        peer.requestPeerDiscovery();
                    }
                }, 3, TimeUnit.SECONDS);
                
                // Also trigger immediate discovery from all connected peers
                executor.schedule(() -> {
                    performImmediateDiscovery();
                }, 5, TimeUnit.SECONDS);
            }
            
            // Process any already discovered peers from this connection
            processDiscoveredPeersFromPeer(peer);
            
            // Set up a periodic task to process discovered peers from all connected peers
            scheduleDiscoveredPeersProcessing();

            peer.getBlocknetSeed().resetCounters();
            setActiveConnectionCount(peers.size());
        } finally {
            lock.unlock();
        }
    }

    private void handlePeerDeath(final BlocknetPeer peer, @Nullable Throwable exception) {
        lock.lock();
        try {
            peers.remove(peer);

            if (peer.getHaveConfig().get() || peer.pastConnectionSuccess()) {
                pendingPeers.add(peer);
                LOGGER.log(Level.FINER, "[NETWORK] XRouter peer saved to pending: " + peer.getAddress());
            }

            peer.setHaveConfig(false);

            BlocknetSeed blocknetSeed = peer.getBlocknetSeed();
            blocknetSeed.incrementFailCounter();
            blocknetSeed.setActivePeer(false);

            // Record failed connection for quality scoring if this is a discovered peer
            if (blocknetSeed instanceof DiscoveredBlocknetSeed) {
                DiscoveredBlocknetSeed discoveredSeed = (DiscoveredBlocknetSeed) blocknetSeed;
                discoveredSeed.recordFailedConnection();
                LOGGER.log(Level.FINER, "[blocknet-peer-group] Recorded failed connection for discovered peer: " + peer.getAddress());
            }

            String peerAddress = peer.getAddress().toString();
            String reason = (exception != null) ? exception.getMessage() : "connection lost";
            
            LOGGER.log(Level.INFO, "[NETWORK] XRouter peer disconnected: " + peerAddress +
                        " (" + peers.size() + " remaining peers)");
            LOGGER.log(Level.FINER, "[NETWORK] Peer connection failed: " + peerAddress + " - " + reason);

            setActiveConnectionCount(peers.size());
        } finally {
            lock.unlock();
        }

        peer.close();
    }

    private Runnable attemptReconnects(boolean forceReconnect) {
        return () -> {
            LOGGER.log(Level.FINER, "[blocknet-peer-group] Checking if we can reconnect to any disconnected peers...");

            try {
                lock.lock();
                try {
                    for (BlocknetSeed blocknetSeed : blocknetSeeds) {
                        if (blocknetSeed.isActivePeer())
                            continue;

                        if (blocknetSeed.getLastFailTimeDiff() >= 60) {
                            blocknetSeed.resetCounters();
                        }

                        boolean attemptReconnect = blocknetSeed.getFailCount() <= maxReconnectsPerHour
                                && blocknetSeed.getLastFailTimeDiff() >= reconnectTime;

                        if (attemptReconnect || forceReconnect) {
                            LOGGER.log(Level.FINER, "[blocknet-peer-group] Reconnecting to peer: " + blocknetSeed.getAddress());
                            BlocknetPeer blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, blocknetSeed);

                            if (blocknetPeer == null)
                                continue;

                            connectTo(new InetSocketAddress(blocknetSeed.getAddress(), blocknetSeed.getPort()), blocknetPeer);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }

    private void attemptReconnect(BlocknetPeer blocknetPeer) {
        try {
            BlocknetSeed blocknetSeed = blocknetPeer.getBlocknetSeed();
            connectTo(new InetSocketAddress(blocknetSeed.getAddress(), blocknetSeed.getPort()), blocknetPeer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addToMessageQueue(QueueItem queueItem) {
        messageQueue.add(queueItem);
    }

    private Runnable processQueue() {
        return () -> {
            CoinInstance coinInstance = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());

            LOGGER.log(Level.FINER, "[blocknet-peer-group] processing message queue");

            if (messageQueue.size() == 0) return;

            for (QueueItem queueItem : messageQueue) {
                if (!queueItem.isValidItem()) messageQueue.remove(queueItem);

                BlocknetPeer blocknetPeer = queueItem.getOriginalPeer();

                if (!blocknetPeer.isActivePeer()) {
                    blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, blocknetPeer.getBlocknetSeed());

                    if (blocknetPeer == null) continue;

                    lock.lock();
                    try {
                        attemptReconnect(blocknetPeer);
                        pendingPeers.remove(queueItem.getOriginalPeer());
                    } finally {
                        lock.unlock();
                    }

                    queueItem.setNewPeer(blocknetPeer);

                    if (!waitForConnection(blocknetPeer, 15)) {
                        blocknetPeer.close();
                        continue;
                    }
                }

                String uuid;
                if (queueItem.getCustomUUID() != null) {
                    coinInstance.sendXrMessage(blocknetPeer, queueItem.getCustomUUID(), queueItem.getCommmand(), queueItem.getParams());
                    uuid = queueItem.getCustomUUID();
                } else {
                    uuid = coinInstance.sendXrMessage(blocknetPeer, queueItem.getCommmand(), queueItem.getParams());
                }

                if (queueItem.getCommmand().equals("xrSendTransaction") && queueItem.getMessageSource() == MessageSource.SOURCE_GUI) {
                    BlocknetPeer finalBlocknetPeer = blocknetPeer;
                    LOGGER.log(Level.FINER, "Transaction successful!");
                } else if (queueItem.getNewPeer() != null) {
                    for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> listener : queueItem.getOriginalPeer().getXRouterMessageListeners()) {
                        queueItem.getNewPeer().addXRouterMessageReceivedEventListener(listener.listener);
                    }
                }

                messageQueue.remove(queueItem);
            }
        };
    }

    private boolean waitForConnection(BlocknetPeer blocknetPeer, int maxWaitSeconds) {
        long startTime = System.currentTimeMillis();

        while((System.currentTimeMillis() - startTime) <  (maxWaitSeconds * 1000)) {
            BlocknetPeer filteredPeer = getConnectedPeers().stream().filter(
                    e -> (e.getHaveConfig().get() && e.getAddress().getAddr() == blocknetPeer.getAddress().getAddr())
            ).findFirst().orElse(null);

            if (filteredPeer != null)
                return true;
            else {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return false;
    }

    private void scheduleMessageQueueRuns() {
        executor.scheduleWithFixedDelay(processQueue(), 0, 1, TimeUnit.SECONDS);
    }

    private void scheduleReconnects() {
        executor.scheduleWithFixedDelay(attemptReconnects(false), 0, 60, TimeUnit.SECONDS);
    }

    /**
     * Schedule periodic network discovery to find new peers
     */
    private void scheduleNetworkDiscovery() {
        LOGGER.log(Level.INFO, "[blocknet-peer-group] Starting network discovery scheduler");
        // Start with more frequent discovery (every 1 minute initially) then back to 3 minutes
        executor.scheduleWithFixedDelay(() -> {
            LOGGER.log(Level.FINER, "[blocknet-peer-group] Network discovery timer triggered");
            performNetworkDiscovery();
            cleanupStalePeers(); // Clean up stale peers regularly
        }, 1, 3 * 60 * 1000, TimeUnit.MILLISECONDS); // Every 3 minutes after initial
    }

    /**
     * Perform network discovery by requesting peer addresses from connected peers
     */
    private void performNetworkDiscovery() {
    	LOGGER.log(Level.INFO, "[blocknet-peer-group] Starting network discovery process");
    	if (!shouldPerformDiscovery()) {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Discovery conditions not met - skipping");
    		return;
    	}
   
    	List<BlocknetPeer> connectedPeers = getConnectedPeers();
    	LOGGER.log(Level.INFO, "[blocknet-peer-group] Found " + connectedPeers.size() + " connected peers for discovery");
    	
    	if (connectedPeers.isEmpty()) {
    		LOGGER.log(Level.WARNING, "[blocknet-peer-group] No connected peers available for discovery");
    		return;
    	}
    	
    	int discoveryRequests = 0;
    	int maxDiscoveryRequests = Math.min(5, connectedPeers.size()); // Allow up to 5 discovery requests or number of peers
   
    	// First try connected peers - prioritize high-quality connections
    	for (BlocknetPeer peer : connectedPeers) {
    		if (discoveryRequests >= maxDiscoveryRequests) {
    			break;
    		}
   
    		try {
    			// Request discovery from peers that support it
    			if (peer.isActivePeer()) {
    				if (peer.canRequestDiscovery()) {
    					peer.requestPeerDiscovery();
    					discoveryRequests++;
    					LOGGER.log(Level.INFO, "[blocknet-peer-group] Requested discovery from peer: " + peer.getAddress());
    					
    					// Add a small delay between requests to avoid overwhelming peers
    					try {
    						Thread.sleep(1000); // 1 second delay between discovery requests
    					} catch (InterruptedException e) {
    						Thread.currentThread().interrupt();
    						break;
    					}
    				} else {
    					// Check if we can reset cooldown for high-quality peers
    					BlocknetSeed seed = peer.getBlocknetSeed();
    					if (seed instanceof DiscoveredBlocknetSeed) {
    						DiscoveredBlocknetSeed discoveredSeed = (DiscoveredBlocknetSeed) seed;
    						if (discoveredSeed.getQualityScore() >= 80) {
    							// Very high-quality peer - allow more frequent discovery
    							LOGGER.log(Level.FINER, "[blocknet-peer-group] Bypassing cooldown for excellent peer: " + peer.getAddress() + " (quality=" + discoveredSeed.getQualityScore() + ")");
    							peer.forceDiscoveryRequest();
    							discoveryRequests++;
    							LOGGER.log(Level.INFO, "[blocknet-peer-group] Forced discovery from excellent peer: " + peer.getAddress());
    						} else if (discoveredSeed.getQualityScore() >= 70) {
    							// High-quality peer - allow more frequent discovery
    							LOGGER.log(Level.FINER, "[blocknet-peer-group] Bypassing cooldown for high-quality peer: " + peer.getAddress() + " (quality=" + discoveredSeed.getQualityScore() + ")");
    							peer.requestPeerDiscovery();
    							discoveryRequests++;
    							LOGGER.log(Level.INFO, "[blocknet-peer-group] Requested discovery from high-quality peer: " + peer.getAddress());
    						} else {
    							LOGGER.log(Level.FINER, "[blocknet-peer-group] Skipping peer " + peer.getAddress() + " (cooldown active, quality=" + discoveredSeed.getQualityScore() + ")");
    						}
    					} else {
    						LOGGER.log(Level.FINER, "[blocknet-peer-group] Skipping peer " + peer.getAddress() + " for discovery (cooldown active)");
    					}
    				}
    			} else {
    				LOGGER.log(Level.FINER, "[blocknet-peer-group] Skipping peer " + peer.getAddress() + " for discovery (not active)");
    			}
    		} catch (Exception e) {
    			LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to request discovery from peer: " + peer.getAddress(), e);
    		}
    	}
   
    	// Always try seed nodes for discovery regardless of connected peer count
    	LOGGER.log(Level.FINER, "[blocknet-peer-group] Attempting discovery via seed nodes");
    	int seedDiscoveryRequests = attemptDiscoveryViaSeedNodes(maxDiscoveryRequests - discoveryRequests);
    	
    	discoveryRequests += seedDiscoveryRequests;
   
    	if (discoveryRequests > 0) {
    		LOGGER.log(Level.INFO, "[blocknet-peer-group] Performed " + discoveryRequests + " discovery requests to peers/seed nodes");
    	} else {
    		LOGGER.log(Level.WARNING, "[blocknet-peer-group] No discovery requests sent this cycle - no suitable peers available");
    	}
    	
    	LOGGER.log(Level.FINER, "[blocknet-peer-group] Network discovery process completed");
    	
    	// Log discovery statistics
    	logDiscoveryStatistics();
    }
    
    /**
     * Log discovery statistics for monitoring
     */
    private void logDiscoveryStatistics() {
    	int totalDiscovered = discoveredPeers.size();
    	int reliablePeers = 0;
    	int activePeers = 0;
    	int connectedPeers = 0;
    	
    	for (DiscoveredBlocknetSeed seed : discoveredPeers.values()) {
    		if (seed.meetsQualityThreshold(70)) {
    			reliablePeers++;
    		}
    		if (seed.hasRecentActivity(30)) {
    			activePeers++;
    		}
    		// Check if currently connected
    		for (BlocknetPeer peer : peers) {
    			if (peer.getAddress().toString().equals(seed.getAddress() + ":" + seed.getPort())) {
    				connectedPeers++;
    				break;
    			}
    		}
    	}
    	
    	LOGGER.log(Level.INFO, "[blocknet-peer-group] Discovery Stats: Total=" + totalDiscovered +
    		", Reliable=" + reliablePeers + ", Active=" + activePeers + ", Connected=" + connectedPeers);
    }

    /**
     * Attempt discovery via seed nodes when no peers are connected
     */
    private int attemptDiscoveryViaSeedNodes(int maxRequests) {
        int discoveryRequests = 0;
        
        for (BlocknetSeed seed : blocknetSeeds) {
            if (discoveryRequests >= maxRequests) {
                break;
            }
            
            try {
                if (seed.isActivePeer()) {
                    // Try discovery through existing active seed connection
                    BlocknetPeer activePeer = findActivePeerForSeed(seed);
                    if (activePeer != null && activePeer.canRequestDiscovery()) {
                        LOGGER.log(Level.FINER, "[blocknet-peer-group] Requesting discovery through active seed node: " + seed.getAddress());
                        activePeer.requestPeerDiscovery();
                        discoveryRequests++;
                        LOGGER.log(Level.INFO, "[blocknet-peer-group] Discovery request sent to active seed node: " + seed.getAddress());
                    }
                } else if (seed.getFailCount() <= 3) {
                    // Connect to inactive seed for discovery
                    LOGGER.log(Level.FINER, "[blocknet-peer-group] Attempting to connect to seed node for discovery: " + seed.getAddress());
                    BlocknetPeer peer = createPeer(blocknetNetworkParameters, blockChain, seed);
                    if (peer != null) {
                        connectTo(new InetSocketAddress(seed.getAddress(), seed.getPort()), peer);
                        discoveryRequests++;
                        LOGGER.log(Level.INFO, "[blocknet-peer-group] Connected to seed node for discovery: " + seed.getAddress());
                        
                        // Schedule discovery request after connection establishes
                        scheduleSeedDiscoveryRequest(peer);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to use seed node for discovery: " + seed.getAddress(), e);
            }
        }
        
        return discoveryRequests;
    }
    
    /**
     * Find an active peer for the given seed
     */
    private BlocknetPeer findActivePeerForSeed(BlocknetSeed seed) {
        return getConnectedPeers().stream()
            .filter(peer -> peer.getBlocknetSeed() == seed)
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Schedule a discovery request for a seed node after connection establishes
     */
    private void scheduleSeedDiscoveryRequest(BlocknetPeer peer) {
        try {
            // Use a scheduled task to request discovery after connection stabilizes
            executor.schedule(() -> {
                try {
                    if (peer.isActivePeer() && peer.canRequestDiscovery()) {
                        LOGGER.log(Level.INFO, "[blocknet-peer-group] Requesting discovery from seed node: " + peer.getAddress());
                        peer.requestPeerDiscovery();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to request discovery from seed node: " + peer.getAddress(), e);
                }
            }, 2, TimeUnit.SECONDS); // Wait 2 seconds for connection to stabilize
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to schedule discovery for seed node: " + e.getMessage());
        }
    }

    /**
     * Check if network discovery should be performed
     */
    private boolean shouldPerformDiscovery() {
        // Allow discovery regardless of connected peer count
        int connectedCount = getConnectedPeers().size();
        LOGGER.log(Level.FINER, "[blocknet-peer-group] Checking discovery conditions: connected=" + connectedCount + ", discovered=" + discoveredPeers.size());
        
        // Always allow discovery to help find new peers
        LOGGER.log(Level.FINER, "[blocknet-peer-group] " + connectedCount + " connected peers available for discovery");
        
        // Only skip if we have maximum discovered peers and no seed nodes to try
        if (discoveredPeers.size() >= MAX_DISCOVERED_PEERS) {
            boolean hasActiveSeeds = blocknetSeeds.stream()
                .anyMatch(seed -> !seed.isActivePeer() && seed.getFailCount() < 3);
            if (!hasActiveSeeds) {
                LOGGER.log(Level.FINER, "[blocknet-peer-group] Maximum discovered peers reached and no active seeds");
                return false;
            }
        }

        // Always proceed with discovery - we want to find new peers constantly
        LOGGER.log(Level.FINER, "[blocknet-peer-group] Discovery conditions met - proceeding with discovery");
        return true;
    }

    /**
     * Perform immediate discovery from newly connected peers
     */
    private void performImmediateDiscovery() {
        LOGGER.log(Level.FINER, "[blocknet-peer-group] Performing immediate discovery from newly connected peers");
        
        List<BlocknetPeer> connectedPeers = getConnectedPeers();
        int discoveryRequests = 0;
        int maxImmediateRequests = Math.min(5, connectedPeers.size()); // Allow up to 5 immediate requests
        
        for (BlocknetPeer peer : connectedPeers) {
            if (discoveryRequests >= maxImmediateRequests) {
                break;
            }
            
            try {
                // Request discovery from any active peer, bypassing cooldown for immediate discovery
                if (peer.isActivePeer()) {
                    if (peer.canRequestDiscovery()) {
                        LOGGER.log(Level.INFO, "[blocknet-peer-group] Requesting immediate discovery from peer: " + peer.getAddress());
                        peer.requestPeerDiscovery();
                        discoveryRequests++;
                    } else {
                        // Use force method when in cooldown but we need immediate discovery
                        LOGGER.log(Level.FINER, "[blocknet-peer-group] Forcing immediate discovery from peer (cooldown active): " + peer.getAddress());
                        peer.forceDiscoveryRequest();
                        discoveryRequests++;
                    }
                    
                    // Add delay between requests
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to request immediate discovery from peer: " + peer.getAddress(), e);
            }
        }
        
        if (discoveryRequests > 0) {
            LOGGER.log(Level.INFO, "[blocknet-peer-group] Performed " + discoveryRequests + " immediate discovery requests");
        } else {
            LOGGER.log(Level.WARNING, "[blocknet-peer-group] No immediate discovery requests were successful");
        }
    }

    /**
     * Add a discovered peer to the registry
     */
    public void addDiscoveredPeer(InetSocketAddress address) {
    	if (address == null) {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Cannot add null discovered peer address");
    		return;
    	}
    	
    	if (discoveredPeers.size() >= MAX_DISCOVERED_PEERS) {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Maximum discovered peers reached, skipping new peer");
    		return;
    	}
   
    	String peerKey = address.getHostString() + ":" + address.getPort();
    	LOGGER.log(Level.INFO, "[blocknet-peer-group] Processing discovered peer: " + peerKey);
    	
    	// Skip if already exists
    	if (discoveredPeers.containsKey(peerKey)) {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Discovered peer already exists: " + peerKey);
    		return;
    	}
   
    	// Validate the peer address
    	if (!isValidDiscoveryCandidate(address)) {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Discovered peer failed validation: " + peerKey);
    		return;
    	}
   
    	// Create discovered seed and add to registry
    	DiscoveredBlocknetSeed discoveredSeed = new DiscoveredBlocknetSeed(address.getHostString(), address.getPort());
    	discoveredPeers.put(peerKey, discoveredSeed);
   
    	// Add to seed list for connection attempts - convert if needed
    	BlocknetSeed existingSeed = findExistingSeed(address);
    	if (existingSeed != null) {
    		// Convert existing seed to discovered seed
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Converting existing seed to discovered seed: " + peerKey);
    		blocknetSeeds.remove(existingSeed);
    		blocknetSeeds.add(0, discoveredSeed); // Priority placement
    	} else {
    		// New seed - add at beginning for priority
    		blocknetSeeds.add(0, discoveredSeed);
    	}
   
    	LOGGER.log(Level.INFO, "[blocknet-peer-group] Successfully added discovered peer: " + peerKey);
   
    	// Immediately try to connect if we're running
    	if (clientManager.isRunning()) {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Client manager running, attempting to connect to discovered peer");
    		connectToDiscoveredPeer(discoveredSeed);
    	} else {
    		LOGGER.log(Level.FINER, "[blocknet-peer-group] Client manager not running, will connect later");
    	}
    }
    
    /**
     * Find existing seed for the given address
     */
    private BlocknetSeed findExistingSeed(InetSocketAddress address) {
    	for (BlocknetSeed seed : blocknetSeeds) {
    		if (seed.getAddress().equals(address.getHostString()) && seed.getPort() == address.getPort()) {
    			return seed;
    		}
    	}
    	return null;
    }

    /**
     * Connect to a discovered peer
     */
    private void connectToDiscoveredPeer(DiscoveredBlocknetSeed discoveredSeed) {
        try {
            BlocknetPeer blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, discoveredSeed);
            if (blocknetPeer != null) {
                connectTo(new InetSocketAddress(discoveredSeed.getAddress(), discoveredSeed.getPort()), blocknetPeer);
                discoveredSeed.markDiscoveryRequest();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to connect to discovered peer: " + discoveredSeed.getAddress(), e);
        }
    }

    /**
     * Validate if a peer address is suitable for discovery
     */
    private boolean isValidDiscoveryCandidate(InetSocketAddress address) {
        if (address.isUnresolved()) {
            return false;
        }

        InetAddress inetAddr = address.getAddress();
        if (inetAddr == null) {
            return false;
        }

        // Skip localhost and private addresses for production
        if (inetAddr.isLoopbackAddress()) {
            return false;
        }
        if (inetAddr.isLinkLocalAddress()) {
            return false;
        }
        if (inetAddr.isMulticastAddress()) {
            return false;
        }

        // Must use Blocknet default port
        if (address.getPort() != 41412) {
            return false;
        }

        // Skip if already connected
        for (BlocknetPeer existingPeer : peers) {
            if (existingPeer.getAddress().equals(address)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get discovered peers sorted by quality score
     */
    public List<DiscoveredBlocknetSeed> getDiscoveredPeersByQuality() {
        return discoveredPeers.values().stream()
                .sorted((a, b) -> Integer.compare(b.getQualityScore(), a.getQualityScore()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Remove stale discovered peers
     */
    private void cleanupStalePeers() {
        long now = System.currentTimeMillis();
        long staleThreshold = 30 * 60 * 1000; // 30 minutes

        discoveredPeers.entrySet().removeIf(entry -> {
            DiscoveredBlocknetSeed seed = entry.getValue();
            boolean isStale = !seed.hasRecentActivity(30) && seed.getPeerAge() > staleThreshold;
            
            if (isStale) {
                LOGGER.log(Level.FINER, "[blocknet-peer-group] Removing stale discovered peer: " + entry.getKey());
            }
            
            return isStale;
        });
    }
    
    /**
     * Schedule immediate discovery attempt after initial connections
     */
    private void scheduleImmediateDiscovery() {
        executor.schedule(() -> {
            try {
                LOGGER.log(Level.INFO, "[blocknet-peer-group] Performing immediate discovery after startup");
                performNetworkDiscovery();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to perform immediate discovery", e);
            }
        }, 10, TimeUnit.SECONDS); // Wait 10 seconds for initial connections to establish
    }

    /**
     * Update peer quality score
     */
    public void updatePeerQuality(String peerAddress, boolean success, long latencyMs) {
        DiscoveredBlocknetSeed seed = discoveredPeers.get(peerAddress);
        if (seed != null) {
            if (success) {
                seed.recordSuccessfulConnection(latencyMs);
            } else {
                seed.recordFailedConnection();
            }
        }
    }

    /**
     * Get quality statistics for discovered peers
     */
    public String getDiscoveryStats() {
        int totalDiscovered = discoveredPeers.size();
        int reliablePeers = 0;
        int activePeers = 0;
        int connectedPeers = 0;

        for (DiscoveredBlocknetSeed seed : discoveredPeers.values()) {
            if (seed.meetsQualityThreshold(MIN_QUALITY_SCORE)) {
                reliablePeers++;
            }
            if (seed.hasRecentActivity(30)) {
                activePeers++;
            }
            // Check if currently connected
            for (BlocknetPeer peer : peers) {
                if (peer.getAddress().toString().equals(seed.getAddress() + ":" + seed.getPort())) {
                    connectedPeers++;
                    break;
                }
            }
        }

        return String.format("Discovery Stats: Total=%d, Reliable=%d, Active=%d, Connected=%d",
                totalDiscovered, reliablePeers, activePeers, connectedPeers);
    }

    private void setActiveConnectionCount(int connectionCount) {
        activeConnectionCount.set(connectionCount);
    }

    public AtomicInteger getActiveConnectionProperty() {
        return activeConnectionCount;
    }

    public int getActiveConnectionCount() {
    	return activeConnectionCount.get();
    }
   
   /**
    * Add a manual peer for connection
    */
   public void addManualPeer(String host, int port) {
    LOGGER.log(Level.INFO, "[blocknet-peer-group] Adding manual peer: " + host + ":" + port);
    
    BlocknetSeed manualSeed = new BlocknetSeed(host, port);
    blocknetSeeds.add(0, manualSeed); // Add at beginning for priority
    
    // If we're already running, try to connect immediately
    if (clientManager.isRunning()) {
    	BlocknetPeer blocknetPeer = createPeer(blocknetNetworkParameters, blockChain, manualSeed);
    	if (blocknetPeer != null) {
    		connectTo(new InetSocketAddress(host, port), blocknetPeer);
    	}
    }
   }
   
   /**
    * Process discovered peers from a specific peer connection
    */
   private void processDiscoveredPeersFromPeer(BlocknetPeer peer) {
       try {
           Set<InetSocketAddress> discoveredPeers = peer.getDiscoveredPeers();
           LOGGER.log(Level.FINER, "[blocknet-peer-group] Processing " + discoveredPeers.size() + " discovered peers from " + peer.getAddress());
           
           for (InetSocketAddress peerAddress : discoveredPeers) {
               addDiscoveredPeer(peerAddress);
           }
       } catch (Exception e) {
           LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to process discovered peers from " + peer.getAddress(), e);
       }
   }
   
   /**
    * Schedule periodic processing of discovered peers from all connected peers
    */
   private void scheduleDiscoveredPeersProcessing() {
       // Process discovered peers every 30 seconds
       executor.scheduleWithFixedDelay(() -> {
           try {
               processAllDiscoveredPeers();
           } catch (Exception e) {
               LOGGER.log(Level.WARNING, "[blocknet-peer-group] Failed to process discovered peers", e);
           }
       }, 30, 30, TimeUnit.SECONDS);
   }
   
   /**
    * Process discovered peers from all connected peers
    */
   private void processAllDiscoveredPeers() {
       List<BlocknetPeer> connectedPeers = getConnectedPeers();
       int totalNewPeers = 0;
       
       for (BlocknetPeer peer : connectedPeers) {
           try {
               Set<InetSocketAddress> discoveredPeers = peer.getDiscoveredPeers();
               
               for (InetSocketAddress peerAddress : discoveredPeers) {
                   if (addDiscoveredPeerSafely(peerAddress)) {
                       totalNewPeers++;
                   }
               }
           } catch (Exception e) {
               LOGGER.log(Level.FINER, "[blocknet-peer-group] Failed to process discovered peers from " + peer.getAddress(), e);
           }
       }
       
       if (totalNewPeers > 0) {
           LOGGER.log(Level.INFO, "[blocknet-peer-group] Added " + totalNewPeers + " new discovered peers from all connections");
       }
   }
   
   /**
    * Safely add a discovered peer with proper validation
    */
   private boolean addDiscoveredPeerSafely(InetSocketAddress address) {
       try {
           String peerKey = address.getHostString() + ":" + address.getPort();
           
           // Skip if already exists in discovered peers
           if (discoveredPeers.containsKey(peerKey)) {
               return false;
           }
           
           // Validate the peer address
           if (!isValidDiscoveryCandidate(address)) {
               return false;
           }
           
           // Create discovered seed and add to registry
           DiscoveredBlocknetSeed discoveredSeed = new DiscoveredBlocknetSeed(address.getHostString(), address.getPort());
           discoveredPeers.put(peerKey, discoveredSeed);
           
           // Add to seed list for connection attempts
           blocknetSeeds.add(0, discoveredSeed); // Priority placement
           
           LOGGER.log(Level.FINER, "[blocknet-peer-group] Added discovered peer: " + peerKey);
           
           // Try to connect immediately if client manager is running
           if (clientManager.isRunning()) {
               connectToDiscoveredPeer(discoveredSeed);
           }
           
           return true;
       } catch (Exception e) {
           LOGGER.log(Level.FINER, "[blocknet-peer-group] Failed to add discovered peer: " + address, e);
           return false;
       }
   }
   
   /**
    * Force immediate discovery from all connected peers
    */
   public void forceDiscoveryFromAllPeers() {
       LOGGER.log(Level.INFO, "[blocknet-peer-group] Force requesting discovery from all connected peers");
       
       List<BlocknetPeer> connectedPeers = getConnectedPeers();
       int discoveryRequests = 0;
       
       for (BlocknetPeer peer : connectedPeers) {
           try {
               if (peer.isActivePeer()) {
                   peer.forceDiscoveryRequest();
                   discoveryRequests++;
                   LOGGER.log(Level.FINER, "[blocknet-peer-group] Forced discovery request to " + peer.getAddress());
                   
                   // Small delay between requests
                   Thread.sleep(200);
               }
           } catch (Exception e) {
               LOGGER.log(Level.FINER, "[blocknet-peer-group] Failed to force discovery from " + peer.getAddress(), e);
           }
       }
       
       LOGGER.log(Level.INFO, "[blocknet-peer-group] Forced discovery requests sent to " + discoveryRequests + " peers");
   }
  }
