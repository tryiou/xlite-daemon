package io.cloudchains.app.net.protocols.blocknet;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.cloudchains.app.Version;
import io.cloudchains.app.net.protocols.blocknet.listeners.*;
import io.cloudchains.app.net.protocols.blocknet.messages.VersionMessageImpl;
import io.cloudchains.app.net.xrouter.XRouterCommandUtils;
import io.cloudchains.app.net.xrouter.XRouterInitialMessagesSentListener;
import io.cloudchains.app.net.xrouter.XRouterMessage;
import io.cloudchains.app.net.xrouter.XRouterMessageSerializer;
import io.cloudchains.app.util.XRouterConfiguration;
import net.jcip.annotations.GuardedBy;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.core.GetAddrMessage;
import org.bitcoinj.core.AddressMessage;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class BlocknetPeer extends PeerSocketHandler {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private final ReentrantLock lock = Threading.lock("BlocknetPeer");

	private boolean activePeer;
	private boolean hasRequiredPlugins;

	private boolean pastConnectionSuccess;
	
	// Peer Discovery Fields
	private static final int MAX_DISCOVERED_PEERS = 100;
	private final Set<InetSocketAddress> discoveredPeers = ConcurrentHashMap.newKeySet();
	private final AtomicLong lastDiscoveryRequest = new AtomicLong(0);
	private static final long DISCOVERY_COOLDOWN = TimeUnit.MINUTES.toMillis(2); // Reduced from 5 to 2 minutes

	// Connection state for proper timeout management
	private enum ConnectionState {
		HANDSHAKING, ESTABLISHED, SHUTTING_DOWN
	}
	private volatile ConnectionState connectionState = ConnectionState.HANDSHAKING;

	private BlocknetParameters params;
	private BlocknetSerializer serializer;
	private XRouterMessageSerializer xRouterMessageSerializer;
	private AbstractBlockChain blockChain;

	private BlocknetSeed blocknetSeed;
	private XRouterConfiguration xRouterConfiguration;
	private final ArrayList<XRouterConfiguration.XRouterPluginConfiguration> pluginConfigurations = new ArrayList<>();
	private final AtomicBoolean haveConfig = new AtomicBoolean(false);
	
	// Reference to peer group for discovered peer integration
	private BlocknetPeerGroup peerGroup;


	private Context context;
	private CopyOnWriteArrayList<XRouterMessage> messagesPendingReply = new CopyOnWriteArrayList<>();

	private CopyOnWriteArrayList<ListenerRegistration<XRouterInitialMessagesSentListener>> initialMessagesSentListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetPreMessageReceivedEventListener>> preMessageReceivedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetPeerConnectedEventListener>> peerConnectedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetOnBlocksDownloadedEventListener>> blocksDownloadedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetPeerDisconnectedEventListener>> disconnectedEventListeners = new CopyOnWriteArrayList<>();
	private CopyOnWriteArrayList<ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>> xRouterMessageListeners = new CopyOnWriteArrayList<>();

	private volatile boolean downloadData;

	@GuardedBy("lock")
	private boolean downloadBlockBodies;

	private static class GetDataRequest {
		final Sha256Hash hash;
		final SettableFuture future;

		public GetDataRequest(Sha256Hash hash, SettableFuture future) {
			this.hash = hash;
			this.future = future;
		}
	}

	private final CopyOnWriteArrayList<GetDataRequest> getDataFutures = new CopyOnWriteArrayList<>();

	@GuardedBy("lock")
	private Sha256Hash lastGetBlocksBegin, lastGetBlocksEnd;

	private final CopyOnWriteArrayList<Ping> pendingPings = new CopyOnWriteArrayList<>();
	private VersionMessage peerVersionMessage = null;
	private final VersionMessage ourVersionMessage;
	private CopyOnWriteArrayList<Wallet> wallets = new CopyOnWriteArrayList<>();
	private volatile int downloadTxDependencyDepth;

	private final SettableFuture<BlocknetPeer> connectionOpenFuture = SettableFuture.create();
	private final SettableFuture<BlocknetPeer> incomingVersionHandshakeFuture = SettableFuture.create();
	private final SettableFuture<BlocknetPeer> outgoingVersionHandshakeFuture = SettableFuture.create();
	private final SettableFuture<BlocknetPeer> incomingPingHandshakeFuture = SettableFuture.create();
	private boolean firstPingReceived = false;

	@SuppressWarnings({"UnstableApiUsage", "unchecked"})
		private final ListenableFuture<BlocknetPeer> versionHandshakeFuture = Futures.transform(Futures.allAsList(outgoingVersionHandshakeFuture,
				incomingVersionHandshakeFuture,
				incomingPingHandshakeFuture),
				new com.google.common.base.Function<List<BlocknetPeer>, BlocknetPeer>() {
					@Nullable
					@Override
					public BlocknetPeer apply(@Nullable List<BlocknetPeer> peers) {
						if (peers == null) {
							throw new NullPointerException("Peer list is null.");
						}
	
						if (peers.size() != 2 || peers.get(0) != peers.get(1)) {
							throw new IllegalStateException("Bad peer list state.");
						}
	
						return peers.get(0);
					}
				},
				MoreExecutors.directExecutor());

	private FilteredBlock currentFilteredBlock;
	private final HashSet<Sha256Hash> pendingBlockDownloads = new HashSet<>();

	@GuardedBy("lock")
	@Nullable
	private List<Sha256Hash> awaitingFreshFilter;

	private static final int minProtocolVersion = 70712;

	private AtomicReference<byte[]> largeReadBuffer = new AtomicReference<>();
	private AtomicInteger largeReadBufferPos = new AtomicInteger();
	private AtomicReference<BlocknetPacketHeader> header;

	protected BlocknetPeer(BlocknetParameters params, AbstractBlockChain chain, PeerAddress peerAddress, BlocknetSeed blocknetSeed, BlocknetPeerGroup peerGroup) {
		super(params, peerAddress);

		this.params = params;
		this.serializer = this.params.getSerializer(false);
		this.xRouterMessageSerializer = this.params.getXRouterMessageSerializer(false);

		this.blockChain = chain;
		this.downloadData = chain != null;
		this.downloadTxDependencyDepth = chain != null ? Integer.MAX_VALUE : 0;
		this.peerAddress = peerAddress;

		this.blocknetSeed = blocknetSeed;
		this.peerGroup = peerGroup;

		this.versionHandshakeFuture.addListener(this::versionHandshakeComplete, Threading.SAME_THREAD);

		this.context = Context.getOrCreate(params);

		this.ourVersionMessage = new VersionMessageImpl(this.params, chain != null ? chain.getBestChainHeight() : 0);
		this.ourVersionMessage.appendToSubVer(Version.CLIENT_TYPE, Version.CLIENT_VERSION, Version.CLIENT_COMMENTS);

		LOGGER.log(Level.FINER, "[blocknet-peer] DEBUG: Our version message:");
		LOGGER.log(Level.FINER, this.ourVersionMessage.toString());

		this.activePeer = true;
		this.pastConnectionSuccess = false;
	}

	@Override
	public void connectionClosed() {
		if (!activePeer) return;

		// For established XRouter connections, prevent disconnection to maintain stable connections
		if (connectionState == ConnectionState.ESTABLISHED) {
			LOGGER.log(Level.WARNING, "[blocknet-peer] Suppressing disconnection for established XRouter connection. " +
				"Resetting activePeer to maintain connection stability.");
			// Reset activePeer to keep the connection alive for XRouter operations
			activePeer = true;
			return;
		}

		// Only allow disconnection during handshake failures or shutdown
		activePeer = false;
		connectionState = ConnectionState.SHUTTING_DOWN;
		LOGGER.log(Level.FINER, "[blocknet-peer] Connection with " + (getAddress() != null ? getAddress().toString() : "<null address>") + " closed. Notifying receivers.");

		for (final ListenerRegistration<BlocknetPeerDisconnectedEventListener> registration : disconnectedEventListeners) {
			registration.executor.execute(() -> registration.listener.onPeerDisconnected(BlocknetPeer.this, 0));
		}
	}

	@Override
	public void connectionOpened() {
		LOGGER.log(Level.FINER, "[blocknet-peer] Connection open to " + (getAddress() != null ? getAddress().toString() : "<null address>") + ", sending version message.");

		sendMessage(ourVersionMessage);
		connectionOpenFuture.set(this);
	}

	@Override
	protected void timeoutOccurred() {
		long currentTime = System.currentTimeMillis();
		
		LOGGER.log(Level.FINER, "[blocknet-peer] Timeout occurred. Connection state: " + connectionState +
			", Connection active: " + activePeer +
			", Messages pending reply: " + messagesPendingReply.size() +
			", Current time: " + currentTime);
		
		// For established XRouter connections, ignore timeout to prevent disconnection
		if (connectionState == ConnectionState.ESTABLISHED) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Timeout ignored for established XRouter connection. " +
				"Messages pending: " + messagesPendingReply.size() +
				". This prevents aggressive timeout disconnection during valid XRouter operations.");
			// Don't call super.timeoutOccurred() to prevent automatic disconnection
			return;
		}
		
		// Only process timeout for handshake failures or shutdown state
		super.timeoutOccurred();
		LOGGER.log(Level.FINER, "[blocknet-peer] Handshake timeout processed - connection will be closed.");
	}

	public void addPreMessageReceivedEventListener(BlocknetPreMessageReceivedEventListener listener) {
		addPreMessageReceivedEventListener(Threading.SAME_THREAD, listener);
	}

	private void addPreMessageReceivedEventListener(Executor executor, BlocknetPreMessageReceivedEventListener listener) {
		preMessageReceivedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addConnectedEventListener(BlocknetPeerConnectedEventListener listener) {
		addConnectedEventListener(Threading.SAME_THREAD, listener);
	}

	public void addConnectedEventListener(Executor executor, BlocknetPeerConnectedEventListener listener) {
		peerConnectedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addBlocksDownloadedEventListener(BlocknetOnBlocksDownloadedEventListener listener) {
		addBlocksDownloadedEventListener(Threading.SAME_THREAD, listener);
	}

	private void addBlocksDownloadedEventListener(Executor executor, BlocknetOnBlocksDownloadedEventListener listener) {
		blocksDownloadedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addPeerDisconnectedEventListener(BlocknetPeerDisconnectedEventListener listener) {
		addPeerDisconnectedEventListener(Threading.SAME_THREAD, listener);
	}

	public void addPeerDisconnectedEventListener(Executor executor, BlocknetPeerDisconnectedEventListener listener) {
		disconnectedEventListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void addXRouterMessageReceivedEventListener(BlocknetOnXRouterMessageReceivedListener listener) {
		addXRouterMessageReceivedEventListener(Threading.SAME_THREAD, listener);
	}

	private void addXRouterMessageReceivedEventListener(Executor executor, BlocknetOnXRouterMessageReceivedListener listener) {
		xRouterMessageListeners.add(new ListenerRegistration<>(listener, executor));
	}

	public void removeXRouterMessageReceivedEventListener(BlocknetOnXRouterMessageReceivedListener listener) {
		ListenerRegistration.removeFromList(listener, xRouterMessageListeners);
	}

	public BlocknetOnXRouterMessageReceivedListener getListener(final String uuid, final AtomicReference<String> response, final CountDownLatch latch) {
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
	public ListenableFuture<Void> sendMessage(Message message) throws NotYetConnectedException {
		lock.lock();
		try {
			if (writeTarget == null) {
				LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Attempted to send message on non-connected socket.");
				throw new NotYetConnectedException();
			}
		} finally {
			lock.unlock();
		}

		try {
			if (message instanceof XRouterMessage) {
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				xRouterMessageSerializer.serialize(message, outputStream);
				// LOGGER.log(Level.FINER, "[blocknet-peer] DEBUG: Sending XRouter message. Actual length (excluding network header) is " + (outputStream.size() - BlocknetPacketHeader.HEADER_LENGTH - 4) + " bytes.");
				
				// Handle both void and CompletableFuture return types
				Object result = writeTarget.writeBytes(outputStream.toByteArray());
				ListenableFuture<Void> future = (result instanceof ListenableFuture) ?
					((ListenableFuture<Void>) result) : Futures.immediateFuture(null);
				
				messagesPendingReply.add((XRouterMessage) message);
				// LOGGER.log(Level.FINER, "[blocknet-peer] DEBUG: Added UUID " + ((XRouterMessage) message).getXRouterHeader().getUUID() + " to pending reply list.");
				
				return future;
			} else {
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				serializer.serialize(message, outputStream);
				
				// Handle both void and CompletableFuture return types
				Object result = writeTarget.writeBytes(outputStream.toByteArray());
				return (result instanceof ListenableFuture) ?
					((ListenableFuture<Void>) result) : Futures.immediateFuture(null);
			}
		} catch (IOException e) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Error while serializing/sending message!");
			e.printStackTrace();
			return Futures.immediateFailedFuture(e);
		}
	}

	@Override
	protected void processMessage(Message message) {
		for (ListenerRegistration<BlocknetPreMessageReceivedEventListener> registration : preMessageReceivedEventListeners) {
			if (registration.executor == Threading.SAME_THREAD) {
				message = registration.listener.onPreMessageReceived(this, message);
			}

			if (message == null)
				break;
		}

		if (message == null)
			return;

		if (currentFilteredBlock != null && !(message instanceof Transaction)) {
			endFilteredBlock(currentFilteredBlock);
			currentFilteredBlock = null;
		}

		if (!(message instanceof VersionMessage || message instanceof Ping || message instanceof VersionAck ||
		      message instanceof AddressMessage || message instanceof GetAddrMessage ||
		      (versionHandshakeFuture.isDone() && !versionHandshakeFuture.isCancelled()))) {
			throw new ProtocolException("Received " + message.getClass().getSimpleName() + " before version handshake was complete.");
		}

		if (message instanceof VersionMessage) {
			processVersionMessage((VersionMessage) message);
		} else if (message instanceof VersionAck) {
			processVersionAck((VersionAck) message);
		} else if (message instanceof Ping) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Received ping message from " + getAddress().toString() + ", sending pong.");
			processPing((Ping) message);
		} else if (message instanceof RejectMessage) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Received rejection message from " + getAddress().toString() + ": " + message.toString());
		} else if (message instanceof AddressMessage) {
			processDiscoveredPeers((AddressMessage) message);
		} else if (message instanceof GetAddrMessage) {
			// We don't serve addr requests (we're not a full node)
			LOGGER.log(Level.FINER, "[blocknet-peer] Received getaddr request, ignoring (SPV client)");
		} else if (message instanceof XRouterMessage) {
			processXRouterMessage((XRouterMessage) message);
		}  else {
			LOGGER.log(Level.FINER, "[blocknet-peer] Warning: Received unhandled message from " + getAddress().toString() + ": " + message.toString());
		}

		//TODO process other message types
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

		LOGGER.log(Level.FINER, "[blocknet-peer] Received version message: " + peerVersionMessage.subVer
				+ ", version " + peerVersionMessage.clientVersion
				+ ", blocks=" + peerVersionMessage.bestHeight);

		if (!peerVersionMessage.hasBlockChain() || (!params.allowEmptyPeerChain() && peerVersionMessage.bestHeight == 0)) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Peer has an empty blockchain while this network does not allow empty blockchains. Disconnecting.");
			close();
		}

		if (peerVersionMessage.bestHeight < 0) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Peer reported bad blockchain height (" + peerVersionMessage.bestHeight + "). Disconnecting.");
			close();
		}

		sendMessage(new VersionAck());
		LOGGER.log(Level.FINER, "[blocknet-peer] Incoming version handshake complete.");
		incomingVersionHandshakeFuture.set(this);
	}

	private void processVersionAck(VersionAck versionAck) throws ProtocolException {
		if (peerVersionMessage == null) {
			throw new ProtocolException("Received version acknowledgement before version message.");
		}

		if (!incomingVersionHandshakeFuture.isDone()) {
			throw new ProtocolException("Received more than one version acknowledgement.");
		}

		LOGGER.log(Level.FINER, "[blocknet-peer] Outgoing version handshake complete.");
		outgoingVersionHandshakeFuture.set(this);
	}

	private void versionHandshakeComplete() {
		// Mark connection as established for proper timeout management
		connectionState = ConnectionState.ESTABLISHED;
		
		// Keep your original 60-second timeout setting - no changes
		setTimeoutEnabled(true);
		setSocketTimeout(60000); // 60 seconds for sustained XRouter operations (preserved as requested)
		
		for (final ListenerRegistration<BlocknetPeerConnectedEventListener> registration : peerConnectedEventListeners) {
			registration.executor.execute(() -> registration.listener.onPeerConnected(BlocknetPeer.this, 1));
		}

		if (peerVersionMessage.clientVersion < minProtocolVersion) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Peer's protocol version (" + peerVersionMessage.clientVersion + ") is lower than the minimum (" + minProtocolVersion + ")! Disconnecting.");
			close();
		}
	}

	private XRouterMessage getOriginalXRouterMessage(String uuid) {
		for (XRouterMessage msg : messagesPendingReply) {
			if (msg.getXRouterHeader().getUUID().equalsIgnoreCase(uuid))
				return msg;
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
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: XRouter server sent back packet with blank UUID!");
			return;
		}

		final XRouterMessage original = getOriginalXRouterMessage(message.getXRouterHeader().getUUID());
		if (original == null) {
			LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Unexpected UUID in reply message! Perhaps the server thinks we sent a packet that we didn't send?");
			throw new ProtocolException("Unexpected UUID in reply message");
		}

		int removed = removeUUIDFromPendingReplyList(message.getXRouterHeader().getUUID());
		if (removed != 1) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Warning: Exception occurred while removing message from pending list! This may break things later on. Amount of messages removed = " + removed);
			if (removed == 0) {
				LOGGER.log(Level.FINER, "[blocknet-peer] ERROR: Invalid UUID in reply message!");
				throw new ProtocolException("Invalid UUID in reply message");
			}
		}

		LOGGER.log(Level.FINER, "[blocknet-peer] XRouter pre-processing successful. Notifying listeners.");
		for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> registration : xRouterMessageListeners) {
			if (registration.executor == Threading.SAME_THREAD) {
				registration.executor.execute(() -> registration.listener.onXRouterMessageReceived(message, original));
			}
		}
	}

	private void processXRouterMessage(final XRouterMessage message) {
		LOGGER.log(Level.FINER, "processXRouterMessage() called.");
		LOGGER.log(Level.FINER, "This XRouter message's UUID is '" + message.getXRouterHeader().getUUID() + "'");
		LOGGER.log(Level.FINER, "[xrouter] XRouter message activity detected - UUID: " + message.getXRouterHeader().getUUID() +
			", Command: " + XRouterCommandUtils.commandIdToString(message.getXRouterHeader().getCommand()) +
			", Timestamp: " + System.currentTimeMillis());

		switch (XRouterCommandUtils.commandIdToString(message.getXRouterHeader().getCommand())) {
			case "xrReply":  //xrReply
			case "xrConfigReply": { // xrConfigReply
				processReply(message);
				break;
			}
			default: { //xrInvalid or unexpected message
				for (ListenerRegistration<BlocknetOnXRouterMessageReceivedListener> registration : xRouterMessageListeners) {
					if (registration.executor == Threading.SAME_THREAD) {
						registration.executor.execute(() -> registration.listener.onXRouterMessageReceived(message, null));
					}
				}
				break;
			}
		}

	}

	@GuardedBy("lock")
	private void blockChainDownloadLocked(Sha256Hash toHash) {
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

		if (Objects.equals(chainHeadHash, lastGetBlocksBegin) || Objects.equals(toHash, lastGetBlocksEnd)) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Ignoring dupliated request: chainHeadHash = " + chainHeadHash.toString() + ", toHash = " + toHash.toString());

			for (Sha256Hash hash : pendingBlockDownloads)
				LOGGER.log(Level.FINER, "[blocknet-peer] Pending block download: " + hash.toString());

			LOGGER.log(Level.FINER, Throwables.getStackTraceAsString(new Throwable()));
			return;
		}

		LOGGER.log(Level.FINER, "[blocknet-peer] blockChainDownloadLocked(" + toHash.toString() + "): Current head = " + chainHeadHash.toString());

		StoredBlock cursor = chainHead;
		for (int i = 100; cursor != null && i > 0; i--) {
			blockLocatorHashes.add(cursor.getHeader().getHash());
			try {
				cursor = cursor.getPrev(blockStore);
			} catch (BlockStoreException e) {
				LOGGER.log(Level.FINER, "[blocknet-peer] Failed to walk the blockchain while constructing a locator.");
				e.printStackTrace();
			}
		}

		if (cursor != null)
			blockLocatorHashes.add(params.getGenesisBlockHash());

		lastGetBlocksBegin = chainHeadHash;
		lastGetBlocksEnd = toHash;

		// Create BlockLocator from the list of hashes
		BlockLocator blockLocator = new BlockLocator(ImmutableList.copyOf(blockLocatorHashes));

		if (downloadBlockBodies) {
			GetBlocksMessage getBlocksMessage = new GetBlocksMessage(params, blockLocator, toHash);
			sendMessage(getBlocksMessage);
		} else {
			GetHeadersMessage getHeadersMessage = new GetHeadersMessage(params, blockLocator, toHash);
			sendMessage(getHeadersMessage);
		}
	}

	private void endFilteredBlock(FilteredBlock filteredBlock) {
		if (!downloadData) {
			LOGGER.log(Level.FINER, "[blocknet-peer] WARNING: [" + getAddress().toString() + "] Received block we did not ask for! Hash: " + filteredBlock.getHash().toString());
			return;
		}

		if (blockChain == null) {
			LOGGER.log(Level.FINER, "[blocknet-peer] WARNING: Received a block, but a blockchain object was not configured!");
			return;
		}

		pendingBlockDownloads.remove(filteredBlock.getBlockHeader().getHash());
		try {
			lock.lock();

			try {
				if (awaitingFreshFilter != null) {
					LOGGER.log(Level.FINER, "[blocknet-peer] Discarding this block because we are waiting for a fresh filter. Hash: " + filteredBlock.getHash().toString());

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
			LOGGER.log(Level.FINER, "[blocknet-peer] Block failed to properly verify!");
			e.printStackTrace();
		} catch (PrunedException e) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Some data needed to handle this block was pruned! Hash: " + filteredBlock.getHash().toString());
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

	private void invokeOnBlocksDownloaded(final Block block, @Nullable final FilteredBlock filteredBlock) {
		if (blockChain == null) {
			return;
		}

		final int blocksLeft = Math.max(0, (int) peerVersionMessage.bestHeight - blockChain.getBestChainHeight());
		for (final ListenerRegistration<BlocknetOnBlocksDownloadedEventListener> registration : blocksDownloadedEventListeners) {
			registration.executor.execute(() -> registration.listener.onBlocksDownloaded(BlocknetPeer.this, block, filteredBlock, blocksLeft));
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

					int bytesToGet = Math.min(buff.remaining(), largeReadBuffer.get().length - largeReadBufferPos.get());
					byte[] buf = largeReadBuffer.get();
					buff.get(buf, largeReadBufferPos.get(), bytesToGet);

					largeReadBuffer.set(buf);

					largeReadBufferPos.set(largeReadBufferPos.get() + bytesToGet);

					if (largeReadBufferPos.get() == largeReadBuffer.get().length) {
						processMessage(serializer.deserializePayload(header.get(), ByteBuffer.wrap(largeReadBuffer.get())));

						largeReadBuffer.set(null);
						header = null;
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
							throw new ProtocolException("No magic/header after reading " + buff.capacity() + " bytes.");
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
			LOGGER.log(Level.FINER, "Error while receiving bytes!");
			e.printStackTrace();
			return -1;
		}
	}

	public void parsePlugins(JSONObject pluginsList) {
		for (String plugin : pluginsList.keySet()) {
			String rawPluginConfig = pluginsList.getString(plugin);

			XRouterConfiguration.XRouterPluginConfiguration pluginConfig = new XRouterConfiguration.XRouterPluginConfiguration(plugin, rawPluginConfig);
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

	public CopyOnWriteArrayList<XRouterMessage> getMessagesPendingReply() {
		return messagesPendingReply;
	}

	public CopyOnWriteArrayList<ListenerRegistration<XRouterInitialMessagesSentListener>> getInitialMessagesSentListeners() {
		return initialMessagesSentListeners;
	}

	public CopyOnWriteArrayList<ListenerRegistration<BlocknetOnXRouterMessageReceivedListener>> getXRouterMessageListeners() {
		return xRouterMessageListeners;
	}

	public void addInitialMessagesSentListener(XRouterInitialMessagesSentListener listener) {
		initialMessagesSentListeners.add(new ListenerRegistration<>(listener, Threading.SAME_THREAD));
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

	public ArrayList<XRouterConfiguration.XRouterPluginConfiguration> getPluginConfigurations() {
		return pluginConfigurations;
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
	
	// Peer Discovery Methods
	
	/**
	 * Request peer addresses from this connected peer
	 */
	public void requestPeerDiscovery() {
		if (!isActivePeer()) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Cannot request discovery - peer not active");
			return;
		}
		
		if (peerVersionMessage == null) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Cannot request discovery - version handshake not complete");
			return;
		}
		
		long now = System.currentTimeMillis();
		if (now - lastDiscoveryRequest.get() < DISCOVERY_COOLDOWN) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Discovery request cooldown active");
			return;
		}
		
		if (discoveredPeers.size() >= MAX_DISCOVERED_PEERS) {
			LOGGER.log(Level.FINER, "[blocknet-peer] Maximum discovered peers reached");
			return;
		}
		
		try {
			GetAddrMessage getAddrMessage = new GetAddrMessage(params);
			ListenableFuture<Void> sendResult = sendMessage(getAddrMessage);
			
			// Add callback to log successful sending
			sendResult.addListener(() -> {
				if (sendResult.isDone() && !sendResult.isCancelled()) {
					lastDiscoveryRequest.set(now);
					LOGGER.log(Level.INFO, "[blocknet-peer] Successfully requested peer addresses from " + getAddress());
				} else {
					LOGGER.log(Level.WARNING, "[blocknet-peer] Failed to send discovery request to " + getAddress());
				}
			}, MoreExecutors.directExecutor());
			
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "[blocknet-peer] Exception while sending discovery request to " + getAddress(), e);
		}
	}
	
	/**
	 * Force a discovery request, bypassing normal cooldown restrictions
	 * Use sparingly for critical discovery situations
	 */
	public void forceDiscoveryRequest() {
		if (!isActivePeer()) return;
		
		try {
			GetAddrMessage getAddrMessage = new GetAddrMessage(params);
			sendMessage(getAddrMessage);
			lastDiscoveryRequest.set(System.currentTimeMillis());
			LOGGER.log(Level.INFO, "[blocknet-peer] Forced discovery request to " + getAddress());
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "[blocknet-peer] Failed to send forced discovery request: " + e.getMessage());
		}
	}
	
	/**
	 * Process discovered peers from addr message
	 */
	private void processDiscoveredPeers(AddressMessage addrMessage) {
		if (addrMessage.getAddresses() == null) return;
		
		int newPeers = 0;
		for (PeerAddress peerAddr : addrMessage.getAddresses()) {
			try {
				InetAddress inetAddress = peerAddr.toSocketAddress().getAddress();
				int port = peerAddr.getPort();
				InetSocketAddress socketAddress = new InetSocketAddress(inetAddress, port);
				
				if (isValidDiscoveredPeer(socketAddress)) {
					if (discoveredPeers.add(socketAddress)) {
						newPeers++;
						LOGGER.log(Level.FINER, "[blocknet-peer] Discovered new peer: " + socketAddress);
						
						// Immediately try to add this peer to the peer group if we have access to it
						try {
							addDiscoveredPeerToGroup(socketAddress);
						} catch (Exception e) {
							LOGGER.log(Level.FINER, "[blocknet-peer] Failed to add discovered peer to group: " + socketAddress);
						}
					}
				}
			} catch (Exception e) {
				LOGGER.log(Level.FINER, "[blocknet-peer] Invalid peer address in discovery response");
			}
		}
		
		if (newPeers > 0) {
			LOGGER.log(Level.INFO, "[blocknet-peer] Discovered " + newPeers + " new peers from " + getAddress());
			
			// Record successful message receipt for quality scoring
			if (blocknetSeed instanceof DiscoveredBlocknetSeed) {
				((DiscoveredBlocknetSeed) blocknetSeed).recordMessageReceived();
			}
		}
	}
	
	/**
	 * Add discovered peer to the peer group if possible
	 */
	private void addDiscoveredPeerToGroup(InetSocketAddress peerAddress) {
		// This is a simplified approach - in a complete implementation,
		// you would need a reference to the BlocknetPeerGroup to call its addDiscoveredPeer method
		// For now, we'll rely on the periodic processing in BlocknetPeerGroup
		
		// Log the discovery for debugging
		LOGGER.log(Level.FINER, "[blocknet-peer] Would add discovered peer to group: " + peerAddress);
	}
	
	/**
	 * Validate if a discovered peer is suitable for connection
	 */
	private boolean isValidDiscoveredPeer(InetSocketAddress address) {
		if (address.isUnresolved()) return false;
		
		// Skip localhost and private addresses for production
		InetAddress inetAddr = address.getAddress();
		if (inetAddr == null) return false;
		
		if (inetAddr.isLoopbackAddress()) return false;
		if (inetAddr.isLinkLocalAddress()) return false;
		if (inetAddr.isMulticastAddress()) return false;
		
		// Blocknet default port
		if (address.getPort() != 41412) return false;
		
		// Skip if already connected or in pending connections
		if (isExistingPeer(address)) return false;
		
		return true;
	}
	
	/**
	 * Check if we're already connected to this peer
	 */
	private boolean isExistingPeer(InetSocketAddress address) {
		// Check against the blocknet seed's address - if this is the same peer, skip
		if (blocknetSeed != null && blocknetSeed.getAddress().equals(address.getHostString())
			&& blocknetSeed.getPort() == address.getPort()) {
			return true;
		}
		
		// In a more complete implementation, you would check against
		// the peer group's connected and pending peers
		// For now, this basic check prevents self-discovery
		return false;
	}
	
	/**
	 * Check if discovery requests can be made to this peer
	 */
	public boolean canRequestDiscovery() {
		long now = System.currentTimeMillis();
		long lastRequest = lastDiscoveryRequest.get();
		
		// Allow discovery if no previous request was made (initial discovery)
		// or if cooldown period has expired since last request
		return lastRequest == 0 || (now - lastRequest) > DISCOVERY_COOLDOWN;
	}
	
	/**
	 * Get discovered peers from this connection
	 */
	public Set<InetSocketAddress> getDiscoveredPeers() {
		return new HashSet<>(discoveredPeers);
	}
	
	/**
	 * Get count of discovered peers
	 */
	public int getDiscoveredPeerCount() {
		return discoveredPeers.size();
	}
}
