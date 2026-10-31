package io.xlite.daemon.app.net.protocols.blocknet;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.net.protocols.blocknet.listeners.BlocknetOnBlocksDownloadedEventListener;
import io.xlite.daemon.app.net.protocols.blocknet.listeners.BlocknetOnXRouterMessageReceivedListener;
import io.xlite.daemon.app.net.protocols.blocknet.listeners.BlocknetPeerConnectedEventListener;
import io.xlite.daemon.app.net.protocols.blocknet.listeners.BlocknetPeerDisconnectedEventListener;
import io.xlite.daemon.app.net.protocols.blocknet.listeners.BlocknetPreMessageReceivedEventListener;
import io.xlite.daemon.app.net.protocols.blocknet.messages.VersionMessageImpl;
import io.xlite.daemon.app.net.xrouter.XRouterCommandUtils;
import io.xlite.daemon.app.net.xrouter.XRouterInitialMessagesSentListener;
import io.xlite.daemon.app.net.xrouter.XRouterMessage;
import io.xlite.daemon.app.net.xrouter.XRouterMessageSerializer;
import io.xlite.daemon.app.util.XRouterConfiguration;

import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockLocator;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.GetBlocksMessage;
import org.bitcoinj.core.GetHeadersMessage;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerSocketHandler;
import org.bitcoinj.core.Ping;
import org.bitcoinj.core.Pong;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.PrunedException;
import org.bitcoinj.core.RejectMessage;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionAck;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

public class BlocknetPeer extends PeerSocketHandler {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final ReentrantLock lock = Threading.lock("BlocknetPeer");

    private volatile boolean activePeer;
    private volatile boolean hasRequiredPlugins;

    private volatile boolean pastConnectionSuccess;

    private BlocknetParameters params;
    private BlocknetSerializer serializer;
    private XRouterMessageSerializer xRouterMessageSerializer;
    private AbstractBlockChain blockChain;

    private BlocknetSeed blocknetSeed;
    private XRouterConfiguration xRouterConfiguration;
    private final ArrayList<XRouterConfiguration.XRouterPluginConfiguration> pluginConfigurations =
            new ArrayList<>();
    private final AtomicBoolean haveConfig = new AtomicBoolean(false);

    private CopyOnWriteArrayList<XRouterMessage> messagesPendingReply =
            new CopyOnWriteArrayList<>();

    private CopyOnWriteArrayList<ListenerRegistration<XRouterInitialMessagesSentListener>>
            initialMessagesSentListeners = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<BlocknetPreMessageReceivedEventListener>>
            preMessageReceivedEventListeners = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<BlocknetPeerConnectedEventListener>>
            peerConnectedEventListeners = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<BlocknetOnBlocksDownloadedEventListener>>
            blocksDownloadedEventListeners = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<BlocknetPeerDisconnectedEventListener>>
            disconnectedEventListeners = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>>
            xRouterMessageListeners = new CopyOnWriteArrayList<>();

    private volatile boolean downloadData;

    @GuardedBy("lock") private boolean downloadBlockBodies;

    private static class GetDataRequest {
        final Sha256Hash hash;
        final SettableFuture future;

        GetDataRequest(Sha256Hash hash, SettableFuture future) {
            this.hash = hash;
            this.future = future;
        }
    }

    private final CopyOnWriteArrayList<GetDataRequest> getDataFutures =
            new CopyOnWriteArrayList<>();

    @GuardedBy("lock") private Sha256Hash lastGetBlocksBegin;

    @GuardedBy("lock") private Sha256Hash lastGetBlocksEnd;

    private final CopyOnWriteArrayList<Ping> pendingPings = new CopyOnWriteArrayList<>();
    private VersionMessage peerVersionMessage = null;
    private final VersionMessage ourVersionMessage;
    private CopyOnWriteArrayList<Wallet> wallets = new CopyOnWriteArrayList<>();

    private final SettableFuture<BlocknetPeer> connectionOpenFuture = SettableFuture.create();
    private final SettableFuture<BlocknetPeer> incomingVersionHandshakeFuture =
            SettableFuture.create();
    private final SettableFuture<BlocknetPeer> outgoingVersionHandshakeFuture =
            SettableFuture.create();
    private final SettableFuture<BlocknetPeer> incomingPingHandshakeFuture =
            SettableFuture.create();
    private boolean firstPingReceived = false;

    @SuppressWarnings({"UnstableApiUsage", "unchecked"})
    private final ListenableFuture<BlocknetPeer> versionHandshakeFuture =
            Futures.transform(
                    Futures.allAsList(
                            outgoingVersionHandshakeFuture,
                            incomingVersionHandshakeFuture,
                            incomingPingHandshakeFuture),
                    BlocknetPeer::samePeerFromHandshake,
                    Threading.SAME_THREAD);

    /** All three handshake futures must resolve to the same peer instance. */
    private static BlocknetPeer samePeerFromHandshake(List<BlocknetPeer> peers) {
        if (peers.size() != 2 || peers.get(0) != peers.get(1)) {
            throw new IllegalStateException("Bad peer list state.");
        }
        return peers.get(0);
    }

    private FilteredBlock currentFilteredBlock;
    private final HashSet<Sha256Hash> pendingBlockDownloads = new HashSet<>();

    @GuardedBy("lock") @Nullable private List<Sha256Hash> awaitingFreshFilter;

    private static final int MIN_PROTOCOL_VERSION = 70712;

    private AtomicReference<byte[]> largeReadBuffer = new AtomicReference<>();
    private AtomicInteger largeReadBufferPos = new AtomicInteger();
    private final AtomicReference<BlocknetPacketHeader> header = new AtomicReference<>();

    protected BlocknetPeer(
            BlocknetParameters params,
            AbstractBlockChain chain,
            PeerAddress peerAddress,
            BlocknetSeed blocknetSeed) {
        super(params, peerAddress);

        this.params = params;
        this.serializer = this.params.getSerializer(false);
        this.xRouterMessageSerializer = this.params.getXRouterMessageSerializer(false);

        this.blockChain = chain;
        this.downloadData = chain != null;
        this.peerAddress = peerAddress;

        this.blocknetSeed = blocknetSeed;

        this.versionHandshakeFuture.addListener(
                this::versionHandshakeComplete, Threading.SAME_THREAD);

        Context.getOrCreate(params);

        this.ourVersionMessage =
                new VersionMessageImpl(this.params, chain != null ? chain.getBestChainHeight() : 0);
        this.ourVersionMessage.appendToSubVer(
                Version.CLIENT_TYPE, Version.CLIENT_VERSION, Version.CLIENT_COMMENTS);

        LOGGER.finer("[blocknet-peer] DEBUG: Our version message:");
        LOGGER.finer(this.ourVersionMessage.toString());

        this.activePeer = true;
        this.pastConnectionSuccess = false;
    }

    @Override
    public void connectionClosed() {
        if (!activePeer) {
            return;
        }

        activePeer = false;
        LOGGER.finer(
                "[blocknet-peer] Connection with "
                        + (getAddress() != null ? getAddress().toString() : "<null address>")
                        + " closed. Notifying receivers.");

        for (final ListenerRegistration<BlocknetPeerDisconnectedEventListener> registration :
                disconnectedEventListeners) {
            registration.executor.execute(
                    () -> registration.listener.onPeerDisconnected(BlocknetPeer.this, 0));
        }
    }

    @Override
    public void connectionOpened() {
        LOGGER.finer(
                "[blocknet-peer] Connection open to "
                        + (getAddress() != null ? getAddress().toString() : "<null address>")
                        + ", sending version message.");

        sendMessage(ourVersionMessage);
        connectionOpenFuture.set(this);
    }

    @Override
    protected void timeoutOccurred() {
        super.timeoutOccurred();
        LOGGER.finer("[blocknet-peer] Timeout occurred.");
        if (!connectionOpenFuture.isDone()) {
            connectionClosed();
        }
    }

    public void addPreMessageReceivedEventListener(
            BlocknetPreMessageReceivedEventListener listener) {
        addPreMessageReceivedEventListener(Threading.SAME_THREAD, listener);
    }

    private void addPreMessageReceivedEventListener(
            Executor executor, BlocknetPreMessageReceivedEventListener listener) {
        preMessageReceivedEventListeners.add(new ListenerRegistration<>(listener, executor));
    }

    public void addConnectedEventListener(BlocknetPeerConnectedEventListener listener) {
        addConnectedEventListener(Threading.SAME_THREAD, listener);
    }

    public void addConnectedEventListener(
            Executor executor, BlocknetPeerConnectedEventListener listener) {
        peerConnectedEventListeners.add(new ListenerRegistration<>(listener, executor));
    }

    public void addBlocksDownloadedEventListener(BlocknetOnBlocksDownloadedEventListener listener) {
        addBlocksDownloadedEventListener(Threading.SAME_THREAD, listener);
    }

    private void addBlocksDownloadedEventListener(
            Executor executor, BlocknetOnBlocksDownloadedEventListener listener) {
        blocksDownloadedEventListeners.add(new ListenerRegistration<>(listener, executor));
    }

    public void addPeerDisconnectedEventListener(BlocknetPeerDisconnectedEventListener listener) {
        addPeerDisconnectedEventListener(Threading.SAME_THREAD, listener);
    }

    public void addPeerDisconnectedEventListener(
            Executor executor, BlocknetPeerDisconnectedEventListener listener) {
        disconnectedEventListeners.add(new ListenerRegistration<>(listener, executor));
    }

    public void addXRouterMessageReceivedEventListener(
            BlocknetOnXRouterMessageReceivedListener listener) {
        addXRouterMessageReceivedEventListener(Threading.SAME_THREAD, listener);
    }

    private void addXRouterMessageReceivedEventListener(
            Executor executor, BlocknetOnXRouterMessageReceivedListener listener) {
        xRouterMessageListeners.add(new ListenerRegistration<>(listener, executor));
    }

    public void removeXRouterMessageReceivedEventListener(
            BlocknetOnXRouterMessageReceivedListener listener) {
        ListenerRegistration.removeFromList(listener, xRouterMessageListeners);
    }

    public BlocknetOnXRouterMessageReceivedListener getListener(
            final String uuid, final AtomicReference<String> response, final CountDownLatch latch) {
        return new BlocknetOnXRouterMessageReceivedListener() {
            @Override
            public void onXRouterMessageReceived(XRouterMessage message, XRouterMessage original) {
                if (message.getXRouterHeader().getUUID().equals(uuid)) {
                    response.set((String) message.getParsedData().get("reply"));

                    latch.countDown();

                    removeXRouterMessageReceivedEventListener(this);
                }
            }
        };
    }

    @Override
    public ListenableFuture sendMessage(Message message) throws NotYetConnectedException {
        lock.lock();
        try {
            if (writeTarget == null) {
                LOGGER.finer(
                        "[blocknet-peer] ERROR: Attempted to send message on non-connected"
                                + " socket.");
                throw new NotYetConnectedException();
            }
        } finally {
            lock.unlock();
        }

        if (message instanceof XRouterMessage) {
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                xRouterMessageSerializer.serialize(message, outputStream);
                LOGGER.finer(
                        "[blocknet-peer] DEBUG: Sending XRouter message. Actual length (excluding"
                                + " network header) is "
                                + (outputStream.size() - BlocknetPacketHeader.HEADER_LENGTH - 4)
                                + " bytes.");
                ListenableFuture future = writeTarget.writeBytes(outputStream.toByteArray());

                messagesPendingReply.add((XRouterMessage) message);
                LOGGER.finer(
                        "[blocknet-peer] DEBUG: Added UUID "
                                + ((XRouterMessage) message).getXRouterHeader().getUUID()
                                + " to pending reply list.");
                return future;
            } catch (IOException e) {
                LOGGER.warning("[blocknet] Error serializing XRouter message" + e.getMessage());
            }
        } else {
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                serializer.serialize(message, outputStream);
                return writeTarget.writeBytes(outputStream.toByteArray());
            } catch (IOException e) {
                LOGGER.warning(
                        "[blocknet] Error serializing/sending non-XRouter message"
                                + e.getMessage());
            }
        }

        return Futures.immediateFuture(null);
    }

    @Override
    protected void processMessage(Message message) {
        for (ListenerRegistration<BlocknetPreMessageReceivedEventListener> registration :
                preMessageReceivedEventListeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                message = registration.listener.onPreMessageReceived(this, message);
            }

            if (message == null) {
                break;
            }
        }

        if (message == null) {
            return;
        }

        if (currentFilteredBlock != null && !(message instanceof Transaction)) {
            endFilteredBlock(currentFilteredBlock);
            currentFilteredBlock = null;
        }

        if (!(message instanceof VersionMessage
                || message instanceof Ping
                || message instanceof VersionAck
                || (versionHandshakeFuture.isDone() && !versionHandshakeFuture.isCancelled()))) {
            throw new ProtocolException(
                    "Received "
                            + message.getClass().getSimpleName()
                            + " before version handshake was complete.");
        }

        if (message instanceof VersionMessage) {
            processVersionMessage((VersionMessage) message);
        } else if (message instanceof VersionAck) {
            processVersionAck((VersionAck) message);
        } else if (message instanceof Ping) {
            LOGGER.finer(
                    "[blocknet-peer] Received ping message from "
                            + getAddress().toString()
                            + ", sending pong.");
            processPing((Ping) message);
        } else if (message instanceof RejectMessage) {
            LOGGER.finer(
                    "[blocknet-peer] ERROR: Received rejection message from "
                            + getAddress().toString()
                            + ": "
                            + message.toString());
        } else if (message instanceof FilteredBlock) {
            currentFilteredBlock = (FilteredBlock) message;
        } else if (message instanceof XRouterMessage) {
            processXRouterMessage((XRouterMessage) message);
        } else {
            LOGGER.finer(
                    "[blocknet-peer] Warning: Received unhandled message from "
                            + getAddress().toString()
                            + ": "
                            + message.toString());
        }

        // TODO process other message types
    }

    private void processPing(Ping pingMessage) throws ProtocolException {
        sendMessage(new Pong(pingMessage.getNonce()));

        if (!firstPingReceived) {
            firstPingReceived = true;
            incomingPingHandshakeFuture.set(this);
        }
    }

    private void processVersionMessage(VersionMessage versionMessage) throws ProtocolException {
        if (peerVersionMessage != null) {
            throw new ProtocolException("Received more than one version message from this peer!");
        }

        peerVersionMessage = versionMessage;

        LOGGER.finer(
                "[blocknet-peer] Received version message: "
                        + peerVersionMessage.subVer
                        + ", version "
                        + peerVersionMessage.clientVersion
                        + ", blocks="
                        + peerVersionMessage.bestHeight
                        + ", us="
                        + peerVersionMessage.receivingAddr);

        if (!peerVersionMessage.hasBlockChain()
                || (!params.allowEmptyPeerChain() && peerVersionMessage.bestHeight == 0)) {
            LOGGER.finer(
                    "[blocknet-peer] ERROR: Peer has an empty blockchain while this network does"
                            + " not allow empty blockchains. Disconnecting.");
            close();
        }

        if (peerVersionMessage.bestHeight < 0) {
            LOGGER.finer(
                    "[blocknet-peer] ERROR: Peer reported bad blockchain height ("
                            + peerVersionMessage.bestHeight
                            + "). Disconnecting.");
            close();
        }

        sendMessage(new VersionAck());
        LOGGER.finer("[blocknet-peer] Incoming version handshake complete.");
        incomingVersionHandshakeFuture.set(this);
    }

    private void processVersionAck(VersionAck versionAck) throws ProtocolException {
        if (peerVersionMessage == null) {
            throw new ProtocolException("Received version acknowledgement before version message.");
        }

        if (!incomingVersionHandshakeFuture.isDone()) {
            throw new ProtocolException("Received more than one version acknowledgement.");
        }

        LOGGER.finer("[blocknet-peer] Outgoing version handshake complete.");
        outgoingVersionHandshakeFuture.set(this);
    }

    private void versionHandshakeComplete() {
        setTimeoutEnabled(false);
        for (final ListenerRegistration<BlocknetPeerConnectedEventListener> registration :
                peerConnectedEventListeners) {
            registration.executor.execute(
                    () -> registration.listener.onPeerConnected(BlocknetPeer.this, 1));
        }

        if (peerVersionMessage.clientVersion < MIN_PROTOCOL_VERSION) {
            LOGGER.finer(
                    "[blocknet-peer] Peer's protocol version ("
                            + peerVersionMessage.clientVersion
                            + ") is lower than the minimum ("
                            + MIN_PROTOCOL_VERSION
                            + ")! Disconnecting.");
            close();
        }
    }

    private XRouterMessage getOriginalXRouterMessage(String uuid) {
        for (XRouterMessage msg : messagesPendingReply) {
            if (msg.getXRouterHeader().getUUID().equalsIgnoreCase(uuid)) {
                return msg;
            }
        }

        return null;
    }

    private int removeUUIDFromPendingReplyList(String uuid) {
        int removed = 0;

        for (XRouterMessage msg : messagesPendingReply) {
            if (msg.getXRouterHeader().getUUID().equalsIgnoreCase(uuid)) {
                messagesPendingReply.remove(msg);
                removed++;
            }
        }

        return removed;
    }

    private void processReply(XRouterMessage message) {
        if (message.getXRouterHeader().getUUID().isEmpty()) {
            LOGGER.finer("[blocknet-peer] ERROR: XRouter server sent back packet with blank UUID!");
            return;
        }

        final XRouterMessage original =
                getOriginalXRouterMessage(message.getXRouterHeader().getUUID());
        if (original == null) {
            LOGGER.finer(
                    "[blocknet-peer] ERROR: Unexpected UUID in reply message! Perhaps the server"
                            + " thinks we sent a packet that we didn't send?");
            throw new ProtocolException("Unexpected UUID in reply message");
        }

        int removed = removeUUIDFromPendingReplyList(message.getXRouterHeader().getUUID());
        if (removed != 1) {
            LOGGER.finer(
                    "[blocknet-peer] Warning: Exception occurred while removing message from"
                            + " pending list! This may break things later on. Amount of messages"
                            + " removed = "
                            + removed);
            if (removed == 0) {
                LOGGER.finer("[blocknet-peer] ERROR: Invalid UUID in reply message!");
                throw new ProtocolException("Invalid UUID in reply message");
            }
        }

        LOGGER.finer("[blocknet-peer] XRouter pre-processing successful. Notifying listeners.");
        for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> registration :
                xRouterMessageListeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                registration.executor.execute(
                        () -> registration.listener.onXRouterMessageReceived(message, original));
            }
        }
    }

    private void processXRouterMessage(final XRouterMessage message) {
        LOGGER.finer("processXRouterMessage() called.");
        LOGGER.finer(
                "This XRouter message's UUID is '" + message.getXRouterHeader().getUUID() + "'");

        switch (XRouterCommandUtils.commandIdToString(message.getXRouterHeader().getCommand())) {
            case "xrReply": // xrReply
            case "xrConfigReply":
                { // xrConfigReply
                    processReply(message);
                    break;
                }
            default:
                { // xrInvalid or unexpected message
                    for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>
                            registration : xRouterMessageListeners) {
                        if (registration.executor == Threading.SAME_THREAD) {
                            registration.executor.execute(() ->
                                    registration.listener.onXRouterMessageReceived(message, null));
                        }
                    }
                    break;
                }
        }
    }

    @GuardedBy("lock") private void blockChainDownloadLocked(Sha256Hash toHash) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Lock is not held by current thread.");
        }

        List<Sha256Hash> blockLocatorHashes = new ArrayList<>(51);

        if (blockChain == null) {
            throw new NullPointerException("Blockchain object is null.");
        }

        BlockStore blockStore = blockChain.getBlockStore();
        StoredBlock chainHead = blockChain.getChainHead();
        Sha256Hash chainHeadHash = chainHead.getHeader().getHash();

        if (Objects.equals(chainHeadHash, lastGetBlocksBegin)
                || Objects.equals(toHash, lastGetBlocksEnd)) {
            LOGGER.finer(
                    "[blocknet-peer] Ignoring dupliated request: chainHeadHash = "
                            + chainHeadHash.toString()
                            + ", toHash = "
                            + toHash.toString());

            for (Sha256Hash hash : pendingBlockDownloads) {
                LOGGER.finer("[blocknet-peer] Pending block download: " + hash.toString());
            }

            LOGGER.finer(Throwables.getStackTraceAsString(new Throwable()));
            return;
        }

        LOGGER.finer(
                "[blocknet-peer] blockChainDownloadLocked("
                        + toHash.toString()
                        + "): Current head = "
                        + chainHeadHash.toString());

        StoredBlock cursor = chainHead;
        for (int i = 100; cursor != null && i > 0; i--) {
            blockLocatorHashes.add(cursor.getHeader().getHash());
            try {
                cursor = cursor.getPrev(blockStore);
            } catch (BlockStoreException e) {
                LOGGER.warning(
                        "[blocknet] Failed to walk blockchain while constructing locator"
                                + e.getMessage());
            }
        }

        if (cursor != null) {
            blockLocatorHashes.add(params.getGenesisBlockHash());
        }

        BlockLocator blockLocator = new BlockLocator(ImmutableList.copyOf(blockLocatorHashes));

        lastGetBlocksBegin = chainHeadHash;
        lastGetBlocksEnd = toHash;

        if (downloadBlockBodies) {
            GetBlocksMessage getBlocksMessage = new GetBlocksMessage(params, blockLocator, toHash);
            sendMessage(getBlocksMessage);
        } else {
            GetHeadersMessage getHeadersMessage =
                    new GetHeadersMessage(params, blockLocator, toHash);
            sendMessage(getHeadersMessage);
        }
    }

    private void endFilteredBlock(FilteredBlock filteredBlock) {
        if (!downloadData) {
            LOGGER.finer(
                    "[blocknet-peer] WARNING: ["
                            + getAddress().toString()
                            + "] Received block we did not ask for! Hash: "
                            + filteredBlock.getHash().toString());
            return;
        }

        if (blockChain == null) {
            LOGGER.finer(
                    "[blocknet-peer] WARNING: Received a block, but a blockchain object was not"
                            + " configured!");
            return;
        }

        pendingBlockDownloads.remove(filteredBlock.getBlockHeader().getHash());
        try {
            lock.lock();

            try {
                if (awaitingFreshFilter != null) {
                    LOGGER.finer(
                            "[blocknet-peer] Discarding this block because we are waiting for a"
                                    + " fresh filter. Hash: "
                                    + filteredBlock.getHash().toString());

                    awaitingFreshFilter.add(filteredBlock.getHash());
                    return;
                } else if (checkForFilterExhaustion(filteredBlock)) {
                    awaitingFreshFilter = new LinkedList<>();
                    awaitingFreshFilter.add(filteredBlock.getHash());
                    awaitingFreshFilter.addAll(blockChain.drainOrphanBlocks());
                    return;
                }
            } finally {
                lock.unlock();
            }

            if (blockChain.add(filteredBlock)) {
                invokeOnBlocksDownloaded(filteredBlock.getBlockHeader(), filteredBlock);
            } else {
                lock.lock();
                try {
                    final Block orphanRoot = blockChain.getOrphanRoot(filteredBlock.getHash());
                    if (orphanRoot == null) {
                        throw new NullPointerException("Orphan root is null.");
                    }

                    blockChainDownloadLocked(orphanRoot.getHash());
                } finally {
                    lock.unlock();
                }
            }
        } catch (VerificationException e) {
            LOGGER.warning("[blocknet] Block failed to properly verify" + e.getMessage());
        } catch (PrunedException e) {
            LOGGER.finer(
                    "[blocknet-peer] Some data needed to handle this block was pruned! Hash: "
                            + filteredBlock.getHash().toString());
            throw new RuntimeException(e);
        }
    }

    private boolean checkForFilterExhaustion(FilteredBlock filteredBlock) {
        boolean exhausted = false;
        for (Wallet wallet : wallets) {
            exhausted |= wallet.checkForFilterExhaustion(filteredBlock);
        }
        return exhausted;
    }

    private void invokeOnBlocksDownloaded(
            final Block block, @Nullable final FilteredBlock filteredBlock) {
        if (blockChain == null) {
            return;
        }

        final int blocksLeft =
                Math.max(0, (int) peerVersionMessage.bestHeight - blockChain.getBestChainHeight());
        for (final ListenerRegistration<BlocknetOnBlocksDownloadedEventListener> registration :
                blocksDownloadedEventListeners) {
            registration.executor.execute(() ->
                    registration.listener.onBlocksDownloaded(
                            BlocknetPeer.this, block, filteredBlock, blocksLeft));
        }
    }

    @Override
    public int receiveBytes(ByteBuffer buff) {
        if (buff.position() != 0 || buff.capacity() < BlocknetPacketHeader.HEADER_LENGTH + 4) {
            throw new IllegalArgumentException("Buffer position is nonzero or bad header.");
        }

        try {
            boolean firstMessage = true;

            while (true) {
                if (largeReadBuffer.get() != null) {
                    if (!firstMessage) {
                        throw new IllegalStateException("Bad firstMessage state.");
                    }

                    int bytesToGet =
                            Math.min(
                                    buff.remaining(),
                                    largeReadBuffer.get().length - largeReadBufferPos.get());
                    byte[] buf = largeReadBuffer.get();
                    buff.get(buf, largeReadBufferPos.get(), bytesToGet);

                    largeReadBuffer.set(buf);

                    largeReadBufferPos.set(largeReadBufferPos.get() + bytesToGet);

                    if (largeReadBufferPos.get() == largeReadBuffer.get().length) {
                        processMessage(
                                serializer.deserializePayload(
                                        header.get(), ByteBuffer.wrap(largeReadBuffer.get())));

                        largeReadBuffer.set(null);
                        header.set(null);
                        firstMessage = false;
                    } else {
                        return buff.position();
                    }
                }

                Message message;
                int preSerializePos = buff.position();
                try {
                    message = serializer.deserialize(buff);
                } catch (BufferUnderflowException e) {
                    if (firstMessage && buff.limit() == buff.capacity()) {
                        buff.position(0);

                        try {
                            serializer.seekPastMagicBytes(buff);
                            header.set(serializer.deserializeHeader(buff));

                            largeReadBufferPos.set(buff.remaining());
                            byte[] buf = new byte[header.get().getLength()];
                            buff.get(buf, 0, largeReadBufferPos.get());
                            largeReadBuffer.set(buf);
                        } catch (BufferUnderflowException e1) {
                            throw new ProtocolException(
                                    "No magic/header after reading " + buff.capacity() + " bytes.");
                        }
                    } else {
                        buff.position(preSerializePos);
                    }

                    return buff.position();
                }

                processMessage(message);
                firstMessage = false;
            }
        } catch (Exception e) {
            LOGGER.warning("[blocknet] Error closing peer connection" + e.getMessage());
            return -1;
        }
    }

    public void parsePlugins(JSONObject pluginsList) {
        for (String plugin : pluginsList.keySet()) {
            String rawPluginConfig = pluginsList.getString(plugin);

            XRouterConfiguration.XRouterPluginConfiguration pluginConfig =
                    new XRouterConfiguration.XRouterPluginConfiguration(plugin, rawPluginConfig);
            pluginConfig.parsePluginConfig();
            getPluginConfigurations().add(pluginConfig);
        }
    }

    public XRouterConfiguration.XRouterPluginConfiguration getPluginConfig(String pluginName) {
        for (XRouterConfiguration.XRouterPluginConfiguration pluginConfig : pluginConfigurations) {
            if (pluginConfig.getPluginName().equals(pluginName)) {
                return pluginConfig;
            }
        }

        return null;
    }

    /** A read-only view; registration goes through the add/remove listener methods. */
    public List<XRouterMessage> getMessagesPendingReply() {
        return Collections.unmodifiableList(messagesPendingReply);
    }

    /** A read-only view; registration goes through {@link #addInitialMessagesSentListener}. */
    public List<ListenerRegistration<XRouterInitialMessagesSentListener>>
            getInitialMessagesSentListeners() {
        return Collections.unmodifiableList(initialMessagesSentListeners);
    }

    /** A read-only view; registration goes through the add/remove listener methods. */
    public List<ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>>
            getXRouterMessageListeners() {
        return Collections.unmodifiableList(xRouterMessageListeners);
    }

    public void addInitialMessagesSentListener(XRouterInitialMessagesSentListener listener) {
        initialMessagesSentListeners.add(
                new ListenerRegistration<>(listener, Threading.SAME_THREAD));
    }

    public void removeInitialMessagesSentListener(XRouterInitialMessagesSentListener listener) {
        ListenerRegistration.removeFromList(listener, initialMessagesSentListeners);
    }

    public BlocknetSeed getBlocknetSeed() {
        return blocknetSeed;
    }

    public AtomicBoolean getHaveConfig() {
        return haveConfig;
    }

    public XRouterConfiguration getxRouterConfiguration() {
        return xRouterConfiguration;
    }

    /** A read-only view of the plugins this peer advertised. */
    public List<XRouterConfiguration.XRouterPluginConfiguration> getPluginConfigurations() {
        return Collections.unmodifiableList(pluginConfigurations);
    }

    public boolean isActivePeer() {
        return activePeer;
    }

    public boolean pastConnectionSuccess() {
        return pastConnectionSuccess;
    }

    public boolean hasRequiredPlugins() {
        return hasRequiredPlugins;
    }

    public void setPastConnectionSuccess(boolean pastConnectionSuccess) {
        this.pastConnectionSuccess = pastConnectionSuccess;
    }

    public void setHaveConfig(boolean hasConfig) {
        this.haveConfig.set(hasConfig);
    }

    public void setxRouterConfiguration(XRouterConfiguration xRouterConfiguration) {
        this.xRouterConfiguration = xRouterConfiguration;
    }

    public void setHasRequiredPlugins(boolean hasRequiredPlugins) {
        this.hasRequiredPlugins = hasRequiredPlugins;
    }
}
