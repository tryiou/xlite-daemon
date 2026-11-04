package io.xlite.daemon.app.net;

import com.google.common.base.Joiner;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;
// import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.App;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.CompiledCoinSupplement;
import io.xlite.daemon.app.coinconfig.SighashStyle;
import io.xlite.daemon.app.crypto.KeyHandler;
import io.xlite.daemon.app.net.api.JSONRPCController;
import io.xlite.daemon.app.net.api.JSONRPCServer;
import io.xlite.daemon.app.net.electrumx.ElectrumApi;
import io.xlite.daemon.app.net.electrumx.ElectrumClient;
import io.xlite.daemon.app.net.electrumx.ElectrumEndpoints;
import io.xlite.daemon.app.net.electrumx.HistoryEngine;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetNetworkParameters;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetParameters;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeerGroup;
import io.xlite.daemon.app.net.xrouter.XRouterMessage;
import io.xlite.daemon.app.net.xrouter.XRouterPacketManager;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.AddressDiscoveryService;
import io.xlite.daemon.app.util.CloudTransaction;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.RpcPortClaims;
import io.xlite.daemon.app.util.RpcPorts;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.util.history.Transaction;
import io.xlite.daemon.app.wallet.WalletHelper;

import org.bitcoinj.core.Context;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.Monetary;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.json.JSONArray;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class CoinInstance {
    public static class CoinError {
        public enum CoinErrorCode {
            BADPASSWORD,
            UNSUPPORTEDCOIN,
            BADMNEMONIC,
            CHANGEPASSWORDFAILED
        }

        private final CoinErrorCode code;
        private final String msg;

        public CoinError(String msg, CoinErrorCode code) {
            this.msg = msg;
            this.code = code;
        }

        public CoinErrorCode getCode() {
            return this.code;
        }

        public String getMessage() {
            return this.msg;
        }
    }

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final int MINIMUM_UTXO_UPDATE_INTERVAL = 500;

    /** The display name each supported mainnet uses in its startup line. */
    private static final Map<CoinTicker, String> MAINNET_NAMES =
            Map.ofEntries(
                    Map.entry(CoinTicker.BLOCKNET, "Blocknet"),
                    Map.entry(CoinTicker.BITCOIN, "Bitcoin"),
                    Map.entry(CoinTicker.BITCOIN_CASH, "Bitcoin Cash"),
                    Map.entry(CoinTicker.BITCORE, "BitCore"),
                    Map.entry(CoinTicker.LITECOIN, "Litecoin"),
                    Map.entry(CoinTicker.DASHCOIN, "Dashcoin"),
                    Map.entry(CoinTicker.DIGIBYTE, "Digibyte"),
                    Map.entry(CoinTicker.DOGECOIN, "Dogecoin"),
                    Map.entry(CoinTicker.SYSCOIN, "Syscoin"),
                    Map.entry(CoinTicker.PIVX, "Pivx"),
                    Map.entry(CoinTicker.UNOBTANIUM, "Unobtanium"),
                    Map.entry(CoinTicker.PKOIN, "Pocketcoin"),
                    Map.entry(CoinTicker.RAVENCOIN, "Ravencoin"),
                    Map.entry(CoinTicker.VERGE, "Verge"));

    private static final int FORWARD_ADDRESS_COUNT = 0;

    private static final List<CoinInstance> COIN_INSTANCES = new CopyOnWriteArrayList<>();
    private static volatile CoinInstance activeCurrency;
    private static CoinTicker activeBlocknetNetwork = null;
    private static CopyOnWriteArrayList<ListenerRegistration<ActiveCoinChangedEventListener>>
            activeCoinChangedListeners = new CopyOnWriteArrayList<>();
    private static ConcurrentHashMap<CoinTicker, AtomicInteger> blockCounts =
            new ConcurrentHashMap<>();

    private ConfigHelper configHelper;
    private WalletHelper walletHelper = null;
    private CoinTicker ticker;
    private volatile ConcurrentHashMap<String, Transaction> transactionList =
            new ConcurrentHashMap<>();
    private volatile HistoryEngine historyEngine;

    /** The coin's single native provider connection; null until the engine starts. */
    private volatile ElectrumApi electrumApi;

    private final CopyOnWriteArrayList<AddressBalance> addressKeyPairs =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CloudTransaction> transactionObservableList =
            new CopyOnWriteArrayList<>();

    /**
     * Serializes the check-then-add in {@link #addCloudTransaction}: the list itself is
     * copy-on-write, so its own monitor protects nothing and a concurrent duplicate could slip
     * between the scan and the add.
     */
    private final Object cloudTransactionLock = new Object();

    private BlocknetPeerGroup blocknetPeerGroup;

    /**
     * The p2p parameters for a Blocknet-network coin, or null for every other coin. Also the
     * XRouter capability flag: a coin can only speak XRouter when these exist, so one field answers
     * both questions and they cannot drift apart.
     */
    private BlocknetParameters blocknetNetworkParameters;

    private NetworkParameters networkParameters;
    private Wallet wallet;
    private KeyHandler keyHandler;
    private XRouterPacketManager xRouterPacketManager = null;
    private volatile int rpcPort = -1;
    private boolean testnet = false;
    // Volatile: written by reloadconfig on a Netty event-loop thread and
    // read by lifecycle code (deinit) on other threads.
    private volatile JSONRPCServer coinRPCServer = null;
    private volatile long lastUtxoUpdate = 0;
    private volatile int generatedAddressCount;

    /**
     * Address-count discovery runs off the startup path, once a session actually serves the coin.
     */
    private final AtomicBoolean discoveryRunning = new AtomicBoolean();

    private volatile boolean discoveryComplete;
    private static volatile boolean addressDiscoveryEnabled = true;

    private CoinInstance(CoinTicker ticker, ConfigHelper configHelper) {
        this.ticker = ticker;
        this.configHelper = configHelper;

        addBlockCount(ticker, 0);

        if (isBlocknetNetwork()) {
            setActiveCurrency(this);
        }
    }

    /**
     * Return mnemonic seed from wallet stored on disk. Correct passphrase required. Returns empty
     * string on error or failure to retrieve mnemonic (or if mnemonic doesn't exist).
     *
     * @param pw caller-owned char array; must be zeroed by the caller after use
     * @return String
     */
    public static String getMnemonicForPw(char[] pw) {
        if (!KeyHandler.existsBaseECKeyFromLocal()) {
            return "";
        }

        List<String> seed = KeyHandler.getBaseSeed(pw);
        if (seed == null) {
            return "";
        }
        return Joiner.on(" ").join(seed);
    }

    public static int getBlockCountByTicker(CoinTicker ticker) {
        if (!blockCounts.containsKey(ticker)) {
            return -1;
        }

        return blockCounts.get(ticker).get();
    }

    public static List<CoinInstance> getCoinInstances() {
        return COIN_INSTANCES;
    }

    public boolean isBlocknetNetwork() {
        return getTicker() == CoinTicker.BLOCKNET;
    }

    public static void setActiveCurrency(CoinInstance newActiveCurrency) {
        activeCurrency = newActiveCurrency;

        for (ListenerRegistration<ActiveCoinChangedEventListener> registration :
                activeCoinChangedListeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                registration.listener.onActiveCoinChanged(activeCurrency);
            }
        }
    }

    public XRouterPacketManager getXRouterPacketManager() {
        return xRouterPacketManager;
    }

    public static void addActiveCoinChangedListener(ActiveCoinChangedEventListener listener) {
        activeCoinChangedListeners.add(new ListenerRegistration<>(listener, Threading.SAME_THREAD));
    }

    public static CoinInstance getActiveCurrency() {
        return activeCurrency;
    }

    public AddressBalance getAddress(String addressB58) {
        for (AddressBalance address : addressKeyPairs) {
            if (address.getAddress().toBase58().equals(addressB58)) {
                return address;
            }
        }

        return null;
    }

    public AddressBalance generateAddress(boolean updateConfig) {
        AddressBalance addressKeyPair = getWalletHelper().generateAddress();
        LegacyAddress address = (LegacyAddress) addressKeyPair.getAddress();
        addressKeyPairs.add(addressKeyPair);
        LOGGER.finer(
                "[wallet] Generated new address, have "
                        + addressKeyPairs.size()
                        + ": "
                        + address.toBase58());

        if (updateConfig) {
            configHelper.setAddressCount(configHelper.getAddressCount() + 1);
            configHelper.writeConfig();
            generatedAddressCount = configHelper.getAddressCount();
        }
        syncHistoryAddresses();

        return addressKeyPair;
    }

    public void importPrivateKey(String privKey) {
        AddressBalance addressKeyPair = getWalletHelper().generateFromPrivateKey(privKey);
        AddressBalance addrExists =
                addressKeyPairs.stream()
                        .filter(e -> e.getAddress().equals(addressKeyPair.getAddress()))
                        .findAny()
                        .orElse(null);

        if (addrExists == null) {
            addressKeyPairs.add(addressKeyPair);
            syncHistoryAddresses();
        }
    }

    private void syncHistoryAddresses() {
        HistoryEngine engine = historyEngine;
        if (engine != null) {
            engine.syncAddresses();
        }
    }

    public CoinTicker getTicker() {
        return ticker;
    }

    private static CoinInstance getInstanceByTicker(CoinTicker ticker) {
        for (CoinInstance instance : COIN_INSTANCES) {
            if (instance.getTicker() == ticker) {
                return instance;
            }
        }

        return null;
    }

    public static CoinTicker getActiveBlocknetNetwork() {
        return activeBlocknetNetwork;
    }

    public static CoinInstance getInstance(CoinTicker ticker) {
        CoinInstance existing = getInstanceByTicker(ticker);
        if (existing != null) {
            return existing;
        }

        ConfigHelper cfg = null;
        if (ticker != CoinTicker.BLOCKNET) {
            cfg = new ConfigHelper(CoinTickerUtils.tickerToString(ticker));
            if (!cfg.isRpcEnabled()) {
                return null;
            }
        }

        synchronized (CoinInstance.class) {
            CoinInstance instance = getInstanceByTicker(ticker);

            if (instance == null) {
                if (cfg == null) {
                    cfg = new ConfigHelper(CoinTickerUtils.tickerToString(ticker));
                }
                instance = new CoinInstance(ticker, cfg);
                if (ticker == CoinTicker.BLOCKNET) {
                    COIN_INSTANCES.add(0, instance);
                } else {
                    COIN_INSTANCES.add(instance);
                }
            }

            if (ticker == CoinTicker.BLOCKNET) {
                activeBlocknetNetwork = ticker;
            }

            if (getActiveBlocknetNetwork() != null && ticker == CoinTicker.BLOCKNET) {
                return getInstanceByTicker(activeBlocknetNetwork);
            }

            return instance;
        }
    }

    /**
     * Change the password. Recreates the wallet file and encrypts with new password.
     *
     * @param oldPassword caller-owned char array; must be zeroed by the caller after use
     * @param newPassword caller-owned char array; must be zeroed by the caller after use
     * @return Error or null
     */
    public static CoinError changePassword(char[] oldPassword, char[] newPassword) {
        if (!KeyHandler.existsBaseECKeyFromLocal()) {
            LOGGER.warning("[wallet] Unable to change the password: Wallet not found on disk");
            return new CoinError(
                    "Unable to change the password: Wallet not found on disk",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        List<String> baseSeed = KeyHandler.getBaseSeed(oldPassword);
        if (baseSeed == null) {
            LOGGER.warning("[wallet] Unable to change the password: Incorrect password");
            return new CoinError(
                    "Unable to change the password: Incorrect password",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        DeterministicSeed seed =
                new DeterministicSeed(baseSeed, null, "", System.currentTimeMillis() / 1000);
        List<String> mnemonic = seed.getMnemonicCode();

        if (!KeyHandler.importFromMnemonic(mnemonic, newPassword)) {
            LOGGER.warning(
                    "[wallet] Unable to change the password: Failed to create new wallet file");
            return new CoinError(
                    "Unable to change the password: Failed to create new wallet file",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        return null;
    }

    public NetworkParameters getNetworkParameters() {
        return networkParameters;
    }

    public boolean hasXRouter() {
        return blocknetNetworkParameters != null;
    }

    public void deinit() {
        if (getTicker() == getActiveBlocknetNetwork() && blocknetPeerGroup != null) {
            blocknetPeerGroup.stop();
        }

        if (coinRPCServer != null) {
            boolean interruptedDuringJoin = false;
            try {
                coinRPCServer.deinit();
                // Bounded: an untimeouted join here would stall the JVM
                // shutdown hook indefinitely on a wedged server thread.
                coinRPCServer.join(10_000);
            } catch (InterruptedException e) {
                // Preserve the flag and say so — a silent swallow here would
                // hide shutdown-latency facts even when the server dies.
                Thread.currentThread().interrupt();
                interruptedDuringJoin = true;
                LOGGER.warning(
                        "[coin] deinit join interrupted for "
                                + CoinTickerUtils.tickerToString(ticker));
            } catch (Exception e) {
                LOGGER.warning(
                        "[coin] Error deinitializing RPC server for "
                                + CoinTickerUtils.tickerToString(ticker)
                                + e.getMessage());
            }
            if (coinRPCServer.isAlive()) {
                LOGGER.warning(
                        "[coin] RPC server thread for "
                                + CoinTickerUtils.tickerToString(ticker)
                                + " still alive after deinit"
                                + (interruptedDuringJoin ? " (join was interrupted)" : ""));
            }
        }
        HistoryEngine engine = historyEngine;
        if (engine != null) {
            // The engine owns the session it was constructed with and closes it.
            engine.close();
            historyEngine = null;
            electrumApi = null;
        } else {
            ElectrumApi api = electrumApi;
            if (api != null) {
                api.close();
                electrumApi = null;
            }
        }
    }

    public CoinError init(char[] pw, String userMnemonic, boolean isMnemonic) {
        CoinError parameterError = loadCoinParameters();
        if (parameterError != null) {
            return parameterError;
        }

        configHelper.writeConfig();
        Context.propagate(new Context(networkParameters));
        startProviderSession();

        CoinError walletError = initializeWallet(pw, userMnemonic, isMnemonic);
        if (walletError != null) {
            return walletError;
        }
        startHistoryEngine();
        startRpcIfConfigured();

        // Blocknet Network / XRouter not used (Dec 10)
        //		if (isBlocknetNetwork() && hasXRouter()) {
        //			XRouterMessageSerializer xRouterMessageSerializer =
        // (getBlocknetNetworkParameters()).getXRouterMessageSerializer(false);
        //			xRouterPacketManager = new XRouterPacketManager(xRouterMessageSerializer,
        // blocknetNetworkParameters);
        //			LOGGER.fine("[coin] This network is a Blocknet network and supports XRouter. Our packet
        // version is " + Integer.toString(XRouterPacketManager.getXRouterPacketVersion(), 16));
        //		} else {
        //			LOGGER.fine("[coin] WARNING: This network (" +
        // CoinTickerUtils.tickerToString(getTicker()) + ") does not support XRouter.");
        //		}
        //
        //		if (isBlocknetNetwork()) {
        //			String userHome = ConfigHelper.getLocalDataDirectory();
        //			Preconditions.checkNotNull(userHome);
        //
        //			File spvDat = new File(userHome,"spv-" + CoinTickerUtils.tickerToString(getTicker()) +
        // ".dat");
        //			try {
        //				chain = new BlockChain(networkParameters, getWallet(), new
        // SPVBlockStore(networkParameters, spvDat));
        //			} catch (BlockStoreException e) {
        //				try {
        //					chain = new BlockChain(networkParameters, getWallet(), new
        // SPVBlockStore(networkParameters, spvDat));
        //				} catch (BlockStoreException ex) {
        //					LOGGER.warning("Error while initializing blockchain object!");
        //					ex.printStackTrace();
        //					return false;
        //				}
        //			}
        //
        //			LOGGER.info("[coin] Connecting to the (" + getTicker().toString() + ") network.");
        //
        //			if (getAddressKeyPairs().size() == 0) {
        //				LOGGER.fine("[peer] Have no addresses. Generating forward addresses.");
        //
        //				generateForwardAddresses(true);
        //			}
        //
        //			AddressBalance blockProofAddress = getAddressKeyPairs().get(0);
        //
        //			keyHandler = new KeyHandler(blockProofAddress.getPrivateKey().getKey());
        //
        //			blocknetPeerGroup = new BlocknetPeerGroup(this, (BlocknetNetworkParameters)
        // blocknetNetworkParameters, chain);
        //			connectToBlocknetNetwork();
        //		}

        return null;
    }

    /**
     * Loads the coin's address and money parameters, and for Blocknet its p2p parameters.
     *
     * <p>Blocknet is configured like every other coin: its address prefixes and fee rates come from
     * its configuration file. It used to take a hardcoded parameter class instead, whose fee
     * constants had drifted from what that file specifies. The XRouter-over-p2p parameters are
     * separate and still hardcoded: they carry the p2p serializer the config does not model, and
     * nothing in the money or address paths reads them.
     */
    private CoinError loadCoinParameters() {
        if (!CoinTicker.coins().contains(ticker)) {
            LOGGER.fine("[coin] ERROR: Invalid/unsupported network: " + ticker);
            return new CoinError("Unsupported coin", CoinError.CoinErrorCode.UNSUPPORTEDCOIN);
        }
        LOGGER.fine("[coin] Initializing for " + MAINNET_NAMES.get(ticker) + " main network.");
        CoinError error = loadMigratedParams();
        if (error != null) {
            return error;
        }
        error = checkSighashPolicy();
        if (error != null) {
            return error;
        }
        if (ticker == CoinTicker.BLOCKNET) {
            // Constructing this object consults the loaded configuration, so it has to come
            // after the load succeeded: moving it up would throw out of init() instead of
            // returning the coin error callers handle.
            blocknetNetworkParameters = new BlocknetNetworkParameters();
        }
        return null;
    }

    private CoinError initializeWallet(char[] pw, String userMnemonic, boolean isMnemonic) {
        List<String> baseSeed;
        if (isMnemonic) {
            baseSeed = splitMnemonicChars(pw);
        } else {
            CoinError importError = importOrReuseWallet(pw, userMnemonic);
            if (importError != null) {
                return importError;
            }
            baseSeed = KeyHandler.getBaseSeed(pw);
        }
        if (baseSeed == null) {
            LOGGER.warning("[wallet] Possible Bad password: Unable to import or create base seed!");
            return new CoinError("Bad password", CoinError.CoinErrorCode.BADPASSWORD);
        }
        createWallet(baseSeed);
        return null;
    }

    private static CoinError importOrReuseWallet(char[] pw, String userMnemonic) {
        if (KeyHandler.existsBaseECKeyFromLocal()) {
            if (userMnemonic != null) {
                LOGGER.warning(
                        "[wallet] Wallet already exists on disk, ignoring provided mnemonic");
            }
            return null;
        }
        if (userMnemonic != null
                && !KeyHandler.importFromMnemonic(Arrays.asList(userMnemonic.split(" ")), pw)) {
            LOGGER.warning("[wallet] Unable to create wallet from mnemonic");
            return new CoinError(
                    "Unable to create wallet from mnemonic", CoinError.CoinErrorCode.BADMNEMONIC);
        }
        return null;
    }

    private void createWallet(List<String> baseSeed) {
        // In-memory wallet only
        DeterministicSeed seed =
                new DeterministicSeed(baseSeed, null, "", System.currentTimeMillis() / 1000);
        wallet = Wallet.fromSeed(networkParameters, seed, ScriptType.P2PKH);
        if (isBlocknetNetwork()) {
            LOGGER.fine(
                    "[wallet] Mnemonic generated for " + CoinTickerUtils.tickerToString(ticker));
        }

        // Address discovery asks the provider which derived addresses hold funds. It is
        // deliberately not part of wallet creation: startup must stay local, so it runs from
        // onReady once a session actually answers for this coin.
        if (!addressDiscoveryEnabled) {
            LOGGER.fine("[coin] Address discovery disabled");
        }

        // Make sure wallet addresses are available
        generateForwardAddresses(true);
    }

    /**
     * Opens the coin's provider session before the wallet exists.
     *
     * <p>Address discovery asks the provider which derived addresses have funds, so the session has
     * to be usable before {@code createWallet} runs. The history engine attaches to this same
     * session afterwards; one connection per coin either way.
     */
    private void startProviderSession() {
        String tickerName = CoinTickerUtils.tickerToString(ticker);
        String endpoint = App.resolveProviderEndpoint(tickerName);
        if (endpoint == null || endpoint.isBlank() || networkParameters == null) {
            LOGGER.warning("[provider] no endpoint configured for " + tickerName);
            return;
        }
        try {
            URI uri = ElectrumEndpoints.websocket(endpoint, tickerName);
            ElectrumClient client = new ElectrumClient(uri);
            electrumApi = client;
            client.start();
            LOGGER.info("[provider] native session for " + tickerName + " via " + uri);
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.SEVERE,
                    "[provider] cannot start the session for " + tickerName,
                    e);
        }
    }

    /**
     * Starts the in-memory history engine on the coin's provider session.
     *
     * <p>It runs after the wallet's addresses exist and before the coin's RPC server accepts
     * requests, so {@code listtransactions} never sees a half-built engine. A failure here is fatal
     * to the coin's history, not to the wallet: the engine keeps retrying, and the RPC method
     * reports "not ready" instead of serving an incomplete list.
     */
    private void startHistoryEngine() {
        String tickerName = CoinTickerUtils.tickerToString(ticker);
        ElectrumApi api = electrumApi;
        if (api == null || networkParameters == null) {
            LOGGER.warning("[history] no provider session for " + tickerName);
            return;
        }
        try {
            HistoryEngine engine =
                    new HistoryEngine(
                            tickerName,
                            networkParameters,
                            api,
                            new EngineSink(),
                            Function.identity());
            historyEngine = engine;
            engine.start();
            LOGGER.info("[history] native history enabled for " + tickerName);
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.SEVERE,
                    "[history] cannot start native history for " + tickerName,
                    e);
        }
    }

    public HistoryEngine getHistoryEngine() {
        return historyEngine;
    }

    /** The coin's one native provider connection, or null while it is not configured. */
    public ElectrumApi getElectrumApi() {
        return electrumApi;
    }

    /** Replace the whole history snapshot atomically; readers keep a consistent map. */
    public void publishHistory(List<Transaction> rows) {
        ConcurrentHashMap<String, Transaction> next = new ConcurrentHashMap<>();
        for (Transaction row : rows) {
            next.put(row.uid(), row);
        }
        transactionList = next;
    }

    /** The engine's view of this coin: its addresses, a published snapshot and the tip. */
    private final class EngineSink implements HistoryEngine.Sink {
        @Override
        public List<AddressBalance> addresses() {
            return getAddressKeyPairs();
        }

        @Override
        public void publish(List<Transaction> rows) {
            publishHistory(rows);
        }

        @Override
        public void onTip(int height) {
            addBlockCount(ticker, height);
        }

        /**
         * Makes the address's unspent set what the indexer says it is.
         *
         * <p>This is the only live feed of the wallet's coin balance: the local RPC used to
         * populate it as a side effect of {@code listunspent}, so a fresh wallet reported zero
         * until someone happened to call it.
         */
        @Override
        public void applyUnspents(String address, JsonArray rows) {
            AddressBalance balance = getAddressBalance(address);
            if (balance == null) {
                return;
            }
            List<UTXO> fresh = new ArrayList<>();
            for (JsonElement row : rows) {
                if (!row.isJsonObject()) {
                    continue;
                }
                UTXO utxo = UTXO.fromUnspentRow(ticker, address, row.getAsJsonObject());
                if (utxo != null) {
                    fresh.add(utxo);
                }
            }
            balance.replaceUnspents(fresh);
        }

        /** The chain is reachable: this is the first moment discovery can be attempted. */
        @Override
        public void onReady() {
            scheduleAddressDiscovery();
        }
    }

    private void startRpcIfConfigured() {
        resolveRpcPort();
        if (!configHelper.isRpcEnabled() || !configHelper.validAuth() || rpcPort == -1) {
            return;
        }
        if (!configHelper.validBindAddress()) {
            LOGGER.severe(
                    "[rpc] Refusing to start JSON-RPC server for coin "
                            + CoinTickerUtils.tickerToString(getTicker())
                            + ": rpcBindAddress '"
                            + configHelper.getRpcBindAddress()
                            + "' is not a specific interface"
                            + " (config: "
                            + configHelper.getFilePath()
                            + "). Set it to 127.0.0.1 or a named local address.");
            return;
        }
        coinRPCServer = JSONRPCController.getRPCServer(this);
        // Readiness is announced by JSONRPCServer itself AFTER a successful bind
        // ("[rpc] RPC server listening for …") — do not log a success-shaped line before
        // the socket exists.
        LOGGER.finer(
                "[rpc] Requesting start of JSON-RPC server for coin "
                        + CoinTickerUtils.tickerToString(getTicker())
                        + " on port "
                        + getRPCPort());

        if (coinRPCServer.isAlive()) {
            coinRPCServer.deinit();
        }
        coinRPCServer.start();

        // Block until bound: the GUI reads configs after master RPC announces readiness, so a
        // late write would be a dead port.
        if (coinRPCServer.awaitBound(8000)) {
            recordBoundPort();
            return;
        }
        int[] candidates = getRpcCandidates();
        LOGGER.severe(
                "[rpc] Failed to start JSON-RPC server for coin "
                        + CoinTickerUtils.tickerToString(getTicker())
                        + " on ports "
                        + describePorts(candidates)
                        + "; RPC will be unavailable for this coin. State: "
                        + coinRPCServer.lifecycleState());
        rpcPort = -1;
    }

    /** Configured port, else BCF Port + 3. */
    private void resolveRpcPort() {
        int configured = configHelper.getRpcPort();
        rpcPort =
                configured != ConfigHelper.UNSET_RPC_PORT
                        ? configured
                        : RpcPorts.xlitePort(
                                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker))
                                        .port());
    }

    /** Persists the port that bound, so configs and GUI agree next start. */
    private void recordBoundPort() {
        int boundPort = coinRPCServer.port();
        rpcPort = boundPort;
        if (!configHelper.setRpcPort(boundPort)) {
            LOGGER.warning(
                    "[coin] Bound port "
                            + boundPort
                            + " could not be recorded in "
                            + configHelper.getFilePath());
            return;
        }
        configHelper.writeConfig();
    }

    /** Candidate list for diagnostics: a range when contiguous, else a list. */
    private static String describePorts(int[] ports) {
        if (ports.length == 0) {
            return "none";
        }
        if (ports.length == 1) {
            return Integer.toString(ports[0]);
        }
        for (int i = 1; i < ports.length; i++) {
            if (ports[i] != ports[i - 1] + 1) {
                return Arrays.toString(ports);
            }
        }
        return ports[0] + "-" + ports[ports.length - 1];
    }

    /**
     * The compiled signing policy and the configuration repository's transaction builder must name
     * the same signature hash. A disagreement means one of the two no longer describes this chain,
     * and signing with the wrong hash produces a transaction the network rejects. Refuse the coin
     * at unlock with both values logged rather than fail on the user's first send.
     */
    private CoinError checkSighashPolicy() {
        String tickerStr = CoinTickerUtils.tickerToString(ticker);
        String method;
        try {
            method = CoinConfigRegistry.get(tickerStr).createTxMethod();
        } catch (RuntimeException e) {
            LOGGER.severe("[coin] [" + tickerStr + "] " + e.getMessage());
            return new CoinError("Unsupported coin", CoinError.CoinErrorCode.UNSUPPORTEDCOIN);
        }
        SighashStyle compiled = CompiledCoinSupplement.forTicker(tickerStr).sighashStyle();
        if (!compiled.matchesCreateTxMethod(method)) {
            LOGGER.severe(
                    "[coin] ["
                            + tickerStr
                            + "] signing policy mismatch: config CreateTxMethod="
                            + method
                            + " ("
                            + SighashStyle.fromCreateTxMethod(method)
                            + "), compiled "
                            + compiled
                            + "; refusing the coin");
            return new CoinError("Unsupported coin", CoinError.CoinErrorCode.UNSUPPORTEDCOIN);
        }
        return null;
    }

    private CoinError loadMigratedParams() {
        try {
            networkParameters =
                    io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters.from(
                            CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker)));
            return null;
        } catch (IllegalStateException | IllegalArgumentException e) {
            LOGGER.warning("[coin] [" + ticker + "] missing/invalid config: " + e.getMessage());
            return new CoinError("Unsupported coin", CoinError.CoinErrorCode.UNSUPPORTEDCOIN);
        }
    }

    private void generateForwardAddresses(boolean fromStartup) {
        int configAddressCount = configHelper.getAddressCount();
        boolean updateConfig = false;
        if (configAddressCount < FORWARD_ADDRESS_COUNT) { // minimum starting addresses
            configAddressCount = FORWARD_ADDRESS_COUNT;
            updateConfig = true;
        }

        LOGGER.fine(
                "[wallet] Generating "
                        + configAddressCount
                        + " forward addresses for network "
                        + getTicker().toString()
                        + ".");

        // Ensure that internal HD wallet pointer matches the count we're expecting.
        // Required because wallet doesn't remember last HD wallet address prior to
        // reboot.
        if (fromStartup) {
            for (int i = 0; i < generatedAddressCount; i++) {
                getWalletHelper().generateAddress();
            }
        }
        for (int i = generatedAddressCount; i < configAddressCount; i++) {
            generateAddress(false);
        }
        generatedAddressCount = configAddressCount;

        if (updateConfig) {
            configHelper.setAddressCount(configAddressCount);
            configHelper.writeConfig();
        }
    }

    public Wallet getWallet() {
        return wallet;
    }

    public String getMnemonic() {
        return Joiner.on(" ")
                .join(Objects.requireNonNull(getWallet().getKeyChainSeed().getMnemonicCode()));
    }

    public double getAllBalances() {
        double balance = 0;

        for (AddressBalance inst : getAddressKeyPairs()) {
            balance += inst.getBalanceProp();
        }

        return balance;
    }

    /**
     * Plain-amount rendering for a value held in base units of {@code coinFactor}.
     *
     * <p>The divisor comes from the {@link Monetary}'s {@code smallestUnitExponent()}, not from the
     * format. bitcoinj's {@code Coin.smallestUnitExponent()} is the constant 8, so passing {@code
     * Coin.valueOf(...)} to a 6-decimal coin's format renders it 100x small no matter what the
     * format is configured with -- {@code minDecimals} only controls how many trailing digits are
     * shown.
     */
    // Package-private for tests: the 6-decimal rendering is the whole point of
    // this change and must be pinned against the real implementation, not a
    // copy of it.
    static String formatBaseUnits(long baseUnits, long coinFactor) {
        final int decimals = Sats.decimalPlaces(coinFactor);
        Monetary amount =
                new Monetary() {
                    @Override
                    public int smallestUnitExponent() {
                        return decimals;
                    }

                    @Override
                    public long getValue() {
                        return baseUnits;
                    }

                    @Override
                    public int signum() {
                        return Long.signum(baseUnits);
                    }
                };
        return MonetaryFormat.BTC
                .withLocale(Locale.ROOT)
                .minDecimals(decimals)
                .repeatOptionalDecimals(1, 0)
                .noCode()
                .format(amount)
                .toString();
    }

    public String getAllBalancesFormatted() {
        // Plain amount at the coin's own decimal places. Replacing
        // BtcFormat.COIN_SCALE, a fixed two-decimal format that truncated
        // every balance -- 100000 base units as "0.00" -- and used the
        // locale's decimal mark.
        long coinFactor =
                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(getTicker())).coinFactor();
        return formatBaseUnits(Sats.fromWholeCoins(getAllBalances(), coinFactor), coinFactor);
    }

    public void sendXrGetTransaction(BlocknetPeer blocknetPeer, String txid) {
        String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

        HashMap<String, Object> body = new HashMap<>();
        body.put("currency", currentCurrency);
        body.put("txid", txid);

        getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrGetTransaction", body);
    }

    public void sendXrGetBlockCount(BlocknetPeer blocknetPeer) {
        String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

        HashMap<String, Object> body = new HashMap<>();
        body.put("currency", currentCurrency);

        getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrGetBlockCount", body);
    }

    public void sendXrGetUtxos(BlocknetPeer blocknetPeer) {
        if (System.currentTimeMillis() - lastUtxoUpdate < MINIMUM_UTXO_UPDATE_INTERVAL) {
            LOGGER.fine(
                    "[coin] Aborting UTXO checking as the list was updated less than 1 second"
                            + " ago.");
            return;
        }

        String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

        HashMap<String, Object> body = new HashMap<>();
        body.put("currency", currentCurrency);
        body.put("command", "xrmgetutxos");
        body.put(
                "params",
                getInstance(CoinTickerUtils.stringToTicker(currentCurrency)).getUTXOParams());

        getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrService", body);
    }

    public String sendXrMessage(
            BlocknetPeer blocknetPeer, String command, Map<String, Object> params) {
        return sendXrMessage(blocknetPeer, UUID.randomUUID().toString(), command, params);
    }

    public String sendXrMessage(
            BlocknetPeer blocknetPeer, String uuid, String command, Map<String, Object> params) {
        if (blocknetPeer == null || !blocknetPeer.getHaveConfig().get()) {
            LOGGER.fine("[sendXrMessage] Config not received yet");
            return null;
        }
        XRouterMessage message = buildXrMessage(blocknetPeer, uuid, command, params);
        if (message == null) {
            return null;
        }
        blocknetPeerGroup.sendMessage(blocknetPeer, message);
        return uuid;
    }

    private XRouterMessage buildXrMessage(
            BlocknetPeer blocknetPeer, String uuid, String command, Map<String, Object> params) {
        XRouterPacketManager.XRouterRequest request =
                new XRouterPacketManager.XRouterRequest(
                        blocknetPeer, uuid, keyHandler.getBaseECKey(), keyHandler.getPublicKey());
        return switch (command) {
            case "xrGetBlockCount" ->
                    getXRouterPacketManager()
                            .getXrGetBlockCount(request, (String) params.get("currency"));
            case "xrService" ->
                    getXRouterPacketManager()
                            .getXrService(
                                    request,
                                    (String) params.get("command"),
                                    (List<?>) params.get("params"));
            case "xrSendTransaction" ->
                    getXRouterPacketManager()
                            .getXrSendTransaction(
                                    request,
                                    (String) params.get("feetx"),
                                    (String) params.get("currency"),
                                    (String) params.get("transaction"));
            case "xrGetBlockHash" ->
                    getXRouterPacketManager()
                            .getXrGetBlockHash(
                                    request,
                                    (String) params.get("feetx"),
                                    (String) params.get("currency"),
                                    (String) params.get("blockIndex"));
            case "xrGetBlock" ->
                    getXRouterPacketManager()
                            .getXrGetBlock(
                                    request,
                                    (String) params.get("feetx"),
                                    (String) params.get("currency"),
                                    (String) params.get("blockHash"));
            case "xrGetTransaction" ->
                    getXRouterPacketManager()
                            .getXrGetTransaction(
                                    request,
                                    (String) params.get("currency"),
                                    (String) params.get("txid"));
            case "xrGetConfig" -> getXRouterPacketManager().getXrGetConfig(request, "self");
            default -> {
                LOGGER.fine("[coin] ERROR: Unknown XRouter Message! Command: " + command);
                yield null;
            }
        };
    }

    public JsonArray getAllUTXOS() {
        final int coinDecimals =
                Sats.decimalPlaces(
                        CoinConfigRegistry.get(CoinTickerUtils.tickerToString(getTicker()))
                                .coinFactor());
        MonetaryFormat plainFormat =
                MonetaryFormat.BTC
                        .withLocale(Locale.ROOT)
                        .minDecimals(coinDecimals)
                        .repeatOptionalDecimals(1, 0)
                        .noCode();

        JsonArray unspentTxsJSON = new JsonArray();
        for (AddressBalance addressBalance : getAddressKeyPairs()) {
            for (UTXO utxo : addressBalance.getUtxos()) {
                if (utxo.isSpent()) {
                    continue;
                }
                JsonObject row = utxoJson(utxo, coinDecimals, plainFormat);
                if (row != null) {
                    unspentTxsJSON.add(row);
                }
            }
        }
        return unspentTxsJSON;
    }

    /** One unspent output as the RPC surface states it, or null when the address cannot parse. */
    private JsonObject utxoJson(UTXO utxo, int coinDecimals, MonetaryFormat plainFormat) {
        org.bitcoinj.core.UTXO bUtxo;
        try {
            bUtxo = utxo.createUTXO();
        } catch (RuntimeException e) {
            // One malformed/bech32 address must not kill the whole listunspent response —
            // skip the row, keep the rest.
            LOGGER.warning(
                    "[coin] Skipping unparseable UTXO for "
                            + CoinTickerUtils.tickerToString(getTicker())
                            + " ("
                            + e.getClass().getSimpleName()
                            + ": "
                            + e.getMessage()
                            + ")");
            return null;
        }

        JsonObject row = new JsonObject();
        row.addProperty("txid", bUtxo.getHash().toString());
        row.addProperty("vout", bUtxo.getIndex());
        row.addProperty("address", bUtxo.getAddress());
        row.addProperty("amount", wholeCoins(utxo.getValue(), coinDecimals, plainFormat));
        row.addProperty(
                "scriptPubKey",
                new String(Hex.encode(bUtxo.getScript().getProgram()), StandardCharsets.UTF_8));
        row.addProperty("spendable", true);

        int confirmations =
                bUtxo.getHeight() == 0
                        ? 0
                        : getBlockCountByTicker(getTicker()) - bUtxo.getHeight() + 1;
        // Pre-first-poll the height cache holds a negative sentinel (-1); clamp so no caller
        // ever sees negative confirmations.
        row.addProperty("confirmations", Math.max(0, confirmations));
        return row;
    }

    /** A base-unit value as whole coins, with the coin's own number of decimals. */
    private static BigDecimal wholeCoins(
            long valueSats, int coinDecimals, MonetaryFormat plainFormat) {
        Monetary monetary =
                new Monetary() {
                    @Override
                    public int smallestUnitExponent() {
                        return coinDecimals;
                    }

                    @Override
                    public long getValue() {
                        return valueSats;
                    }

                    @Override
                    public int signum() {
                        return Long.signum(valueSats);
                    }
                };
        return new BigDecimal(plainFormat.format(monetary).toString());
    }

    public JsonArray getAllTransactions() {
        List<Transaction> rows = new ArrayList<>(transactionList.values());
        rows.sort(
                Comparator.comparingInt(Transaction::getTime)
                        .thenComparing(Transaction::getTxid)
                        .thenComparingInt(Transaction::getVout)
                        .thenComparing(Transaction::getCategory));
        int tip = getBlockCountByTicker(getTicker());
        JsonArray transactionsJSON = new JsonArray();
        for (Transaction tx : rows) {
            JsonObject txJSON = new JsonObject();
            txJSON.addProperty("category", tx.getCategory());
            txJSON.addProperty("txid", tx.getTxid());
            txJSON.addProperty("blockhash", tx.getBlockhash());
            txJSON.addProperty("vout", tx.getVout());
            txJSON.addProperty("address", tx.getAddress());

            txJSON.addProperty("amount", tx.getValue());
            txJSON.addProperty("fee", tx.getFee());
            txJSON.addProperty("trusted", false);
            txJSON.addProperty("blocktime", tx.getBlocktime());
            txJSON.addProperty("time", tx.getTime());
            txJSON.addProperty("blockheight", tx.getBlockheight());
            txJSON.addProperty("confirmations", confirmations(tx, tip));

            transactionsJSON.add(txJSON);
        }

        return transactionsJSON;
    }

    /** Confirmations are derived from the current tip, so a new block never needs a row rewrite. */
    private static int confirmations(Transaction tx, int tip) {
        if (tx.getBlockheight() <= 0) {
            return 0;
        }
        return Math.max(0, tip - tx.getBlockheight() + 1);
    }

    public List<String> getUTXOParams() {
        List<String> params = new ArrayList<>();
        params.add(CoinTickerUtils.tickerToString(getTicker()));

        JSONArray utxoAddresses = new JSONArray();
        for (AddressBalance addressBalance : getAddressKeyPairs()) {
            utxoAddresses.put(addressBalance.getAddress().toBase58());
        }

        params.add(utxoAddresses.toString());

        return params;
    }

    public AddressBalance getAddressBalance(String address) {
        for (AddressBalance addressBalance : getAddressKeyPairs()) {
            if (addressBalance.getAddress().toBase58().equals(address)) {
                return addressBalance;
            }
        }

        return null;
    }

    public ConfigHelper getConfigHelper() {
        return this.configHelper;
    }

    public WalletHelper getWalletHelper() {
        if (this.walletHelper == null) {
            this.walletHelper = new WalletHelper(this);
        }

        return this.walletHelper;
    }

    public void addBlockCount(CoinTicker ticker, Integer blockCount) {
        if (blockCount == null) {
            LOGGER.fine(
                    "[coin-"
                            + CoinTickerUtils.tickerToString(ticker)
                            + "] Ignoring an absent block count");
            return;
        }
        // Plain set: the cache must track the true tip. The old Math.max
        // ratchet pinned a stale-high height forever (e.g. after network
        // switch or a rollback), and pre-first-poll zeros poisoned
        // confirmation math.
        blockCounts.computeIfAbsent(ticker, k -> new AtomicInteger(0)).set(blockCount);
    }

    public void addCloudTransaction(CloudTransaction cloudTransaction) {
        synchronized (cloudTransactionLock) {
            if (transactionObservableList.isEmpty()) {
                transactionObservableList.add(cloudTransaction);
                return;
            }

            CloudTransaction tx =
                    transactionObservableList.stream()
                            .filter(e -> e.getTxHash().equals(cloudTransaction.getTxHash()))
                            .findAny()
                            .orElse(null);

            if (tx == null) {
                transactionObservableList.add(cloudTransaction);
            }
        }
    }

    public void processUtxos(List<UTXO> utxoList) {
        if (utxoList == null) {
            LOGGER.warning(
                    "[coin-"
                            + CoinTickerUtils.tickerToString(getTicker())
                            + "] processUtxos: null UTXO list received");
            return;
        }
        LOGGER.fine(
                "[coin-"
                        + CoinTickerUtils.tickerToString(getTicker())
                        + "] processUtxos: remote returned "
                        + utxoList.size()
                        + " UTXOs, tracking "
                        + addressKeyPairs.size()
                        + " addresses locally");
        int added = 0;
        int skipped = 0;

        Set<String> clearedAddresses = new HashSet<>();
        for (UTXO utxo : utxoList) {
            String addr = utxo.getAddress();
            if (clearedAddresses.add(addr)) {
                AddressBalance addressBalance = getAddress(addr);
                if (addressBalance != null) {
                    addressBalance.clearUtxos();
                }
            }
        }

        for (UTXO utxo : utxoList) {
            AddressBalance addressBalance = getAddress(utxo.getAddress());
            if (addressBalance == null) {
                LOGGER.warning(
                        "[utxo-parser] Warning: Encountered non-tracked address in reply: "
                                + utxo.getAddress());
                skipped++;
                continue;
            }
            boolean isNewUtxo = addressBalance.addUtxo(utxo);
            if (isNewUtxo) {
                added++;
                addCloudTransaction(new CloudTransaction(utxo));
                LOGGER.finer(
                        "[utxo-parser] Added new UTXO, address: "
                                + utxo.getAddress()
                                + " value: "
                                + utxo.getAmount());
            }
        }
        LOGGER.fine(
                "[coin-"
                        + CoinTickerUtils.tickerToString(getTicker())
                        + "] processUtxos: added="
                        + added
                        + ", skipped="
                        + skipped);
        setLastUtxoUpdate(System.currentTimeMillis());
    }

    public void processHistoryTxs(List<Transaction> transactions) {
        for (Transaction tx : transactions) {
            transactionList.put(tx.uid(), tx);
        }
    }

    private void setLastUtxoUpdate(long newUtxoTime) {
        lastUtxoUpdate = newUtxoTime;
    }

    public void reloadConfig() {
        this.configHelper.loadConfig();
        resolveRpcPort();
        if (configHelper.getAddressCount() != generatedAddressCount) {
            generateForwardAddresses(false);
            syncHistoryAddresses();
        }

        if (coinRPCServer == null) {
            return; // no rpc available, skip

            // Same fail-closed gate as initial start: a config edited to a
            // wildcard between reloads must not rebind onto every interface.
        }
        if (!configHelper.validBindAddress()) {
            LOGGER.severe(
                    "[rpc] Refusing to rebind JSON-RPC server for coin "
                            + CoinTickerUtils.tickerToString(getTicker())
                            + ": rpcBindAddress '"
                            + configHelper.getRpcBindAddress()
                            + "' is not a specific interface"
                            + " (config: "
                            + configHelper.getFilePath()
                            + "). Set it to 127.0.0.1 or a named local address.");
            return;
        }

        // Atomic retire+create: a concurrent reloadconfig must never
        // receive the retiring server instance.
        coinRPCServer = JSONRPCController.rebindRPCServer(this);

        LOGGER.finer(
                "[rpc] Requesting start of JSON-RPC server for coin "
                        + CoinTickerUtils.tickerToString(getTicker())
                        + " on port "
                        + getRPCPort());
        coinRPCServer.start();

        // Verify the rebind took; a silent bind failure left the coin dead.
        if (!coinRPCServer.awaitBound(10000)) {
            LOGGER.severe(
                    "[rpc] Failed to rebind JSON-RPC server for coin "
                            + CoinTickerUtils.tickerToString(getTicker())
                            + " on port "
                            + getRPCPort()
                            + " after reloadconfig"
                            + "; new-server state: "
                            + coinRPCServer.lifecycleState());
            rpcPort = -1;
        } else {
            recordBoundPort();
        }
    }

    public KeyHandler getKeyHandler() {
        return keyHandler;
    }

    public BlocknetPeerGroup getBlocknetPeerGroup() {
        return blocknetPeerGroup;
    }

    /** Called once by {@link BlocknetPeerGroup} as it is constructed for this coin. */
    public void attachBlocknetPeerGroup(BlocknetPeerGroup group) {
        blocknetPeerGroup = group;
    }

    public BlocknetPeer getBestBlocknetPeer(String currency) {
        if (blocknetPeerGroup == null) {
            return null;
        }

        return blocknetPeerGroup.getBestBlocknetPeer(currency);
    }

    public List<AddressBalance> getAddressKeyPairs() {
        return Collections.unmodifiableList(addressKeyPairs);
    }

    public List<CloudTransaction> getTransactionList() {
        return Collections.unmodifiableList(transactionObservableList);
    }

    public static AtomicInteger getBlockCount(CoinTicker ticker) {
        return blockCounts.putIfAbsent(ticker, new AtomicInteger(0));
    }

    public static ConcurrentHashMap<CoinTicker, AtomicInteger> getBlockCounts() {
        return blockCounts;
    }

    public int getRPCPort() {
        return rpcPort;
    }

    /** Ports this listener may bind, in order, excluding other coins' claims. */
    public int[] getRpcCandidates() {
        return RpcPorts.candidates(
                rpcPort,
                RpcPorts.WINDOW,
                RpcPortClaims.excluding(
                        CoinTickerUtils.tickerToString(ticker),
                        JSONRPCController.getMasterCandidatePorts()));
    }

    public String getRpcBindAddress() {
        return configHelper.getRpcBindAddress();
    }

    public boolean isTestnet() {
        return testnet;
    }

    public static String getVersionString() {
        return Version.SUBVERSION;
    }

    /**
     * Scans the chain for the wallet's used addresses, then grows the wallet to match.
     *
     * @return true when the scan covered the addresses it needed to; false when there is no session
     *     yet or the scan was cut short, in which case the caller retries on the next connect
     */
    public boolean runAddressDiscovery() {
        String currency = CoinTickerUtils.tickerToString(this.getTicker());

        if (!configHelper.isRpcEnabled()) {
            LOGGER.fine("[coin-" + currency + "] RPC disabled, skipping address discovery");
            return true;
        }

        // Without a session there is nothing to scan; the next onReady retries.
        ElectrumApi api = electrumApi;
        if (api == null || !api.isConnected()) {
            LOGGER.fine("[coin-" + currency + "] Provider not connected, deferring discovery");
            return false;
        }

        // A fresh service per attempt: it clears its cached chain key when it finishes, so a
        // retry has to derive one again rather than scan with none.
        AddressDiscoveryService discoveryService = new AddressDiscoveryService(this);
        int discoveredCount = discoveryService.discoverAddressCount();
        discoveryService.clearExternalChainKey();
        int currentCount = configHelper.getAddressCount();

        if (discoveredCount > currentCount) {
            LOGGER.info(
                    "[coin-"
                            + currency
                            + "] Address discovery found "
                            + discoveredCount
                            + " addresses (was "
                            + currentCount
                            + ")");

            // Update config and generate missing addresses
            configHelper.setAddressCount(discoveredCount);
            configHelper.writeConfig();
            generateForwardAddresses(false);
            syncHistoryAddresses();

            LOGGER.info("[coin-" + currency + "] Updated address count to " + discoveredCount);
        } else {
            LOGGER.fine(
                    "[coin-"
                            + currency
                            + "] No new addresses discovered, "
                            + "keeping current count: "
                            + currentCount);
        }
        return api.isConnected() && discoveryService.wasComplete();
    }

    /**
     * Runs address discovery in the background, once per coin and only after a session is up.
     *
     * <p>It is the one startup task that needs the provider, so it must never be on the wallet- or
     * RPC-start path: a coin the backend does not serve yet simply stays undiscovered until it is
     * served, and a coin served later is discovered then.
     */
    private void scheduleAddressDiscovery() {
        if (!addressDiscoveryEnabled
                || discoveryComplete
                || !discoveryRunning.compareAndSet(false, true)) {
            return;
        }
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                discoveryComplete = runAddressDiscovery();
                            } finally {
                                discoveryRunning.set(false);
                            }
                        },
                        "discovery-" + CoinTickerUtils.tickerToString(ticker));
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isInstanceRunning() {
        HistoryEngine engine = historyEngine;
        return engine != null && engine.isReady();
    }

    public static void setAddressDiscoveryEnabled(boolean enabled) {
        addressDiscoveryEnabled = enabled;
    }

    public static boolean isAddressDiscoveryEnabled() {
        return addressDiscoveryEnabled;
    }

    /**
     * Split a mnemonic char[] into individual word strings without materializing the full mnemonic
     * as a String.
     */
    private static List<String> splitMnemonicChars(char[] chars) {
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= chars.length; i++) {
            if (i == chars.length || chars[i] == ' ') {
                if (i > start) {
                    words.add(new String(chars, start, i - start));
                }
                start = i + 1;
            }
        }
        return words;
    }
}
