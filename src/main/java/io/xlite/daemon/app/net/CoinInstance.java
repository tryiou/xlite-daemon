package io.xlite.daemon.app.net;

import com.google.common.base.Joiner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;
//import com.subgraph.orchid.encoders.Hex;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.crypto.KeyHandler;
import io.xlite.daemon.app.net.api.JSONRPCController;
import io.xlite.daemon.app.net.api.JSONRPCServer;
import io.xlite.daemon.app.net.protocols.blocknet.*;
import io.xlite.daemon.app.net.xrouter.XRouterMessage;
import io.xlite.daemon.app.net.xrouter.XRouterPacketManager;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.AddressDiscoveryService;
import io.xlite.daemon.app.util.CloudTransaction;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.util.history.Transaction;
import io.xlite.daemon.app.wallet.WalletHelper;
import org.bitcoinj.core.*;
import org.bitcoinj.utils.BtcFormat;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.json.JSONArray;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class CoinInstance {
    public static class CoinError {
        public enum CoinErrorCode {
            BADPASSWORD, UNSUPPORTEDCOIN, BADMNEMONIC, CHANGEPASSWORDFAILED
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

    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final int MINIMUM_UTXO_UPDATE_INTERVAL = 500;

    private static final int FORWARD_ADDRESS_COUNT = 0;

    private static final List<CoinInstance> coinInstances = new CopyOnWriteArrayList<>();
    private static volatile CoinInstance activeCurrency;
    private static CoinTicker activeBlocknetNetwork = null;
    private static CopyOnWriteArrayList<ListenerRegistration<ActiveCoinChangedEventListener>> activeCoinChangedListeners = new CopyOnWriteArrayList<>();
    private static ConcurrentHashMap<CoinTicker, AtomicInteger> blockCounts = new ConcurrentHashMap<>();

    private ConfigHelper configHelper;
    private WalletHelper walletHelper = null;
    private CoinTicker ticker;
    private ConcurrentHashMap<String, Transaction> transactionList = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AddressBalance> addressKeyPairs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CloudTransaction> transactionObservableList = new CopyOnWriteArrayList<>();
    private BlocknetPeerGroup blocknetPeerGroup;
    private BlocknetParameters blocknetNetworkParameters;
    private NetworkParameters networkParameters;
    private Wallet wallet;
    private BlockChain chain;
    private boolean hasXRouter = false;
    private KeyHandler keyHandler;
    private XRouterPacketManager xRouterPacketManager = null;
    private int rpcPort = -1;
    private boolean testnet = false;
    // Volatile: written by reloadconfig on a Netty event-loop thread and
    // read by lifecycle code (deinit) on other threads.
    private volatile JSONRPCServer coinRPCServer = null;
    private volatile long lastUtxoUpdate = 0;
    private final AtomicInteger updateFailures = new AtomicInteger(0);
    private int generatedAddressCount;
    private AddressDiscoveryService discoveryService = null;
    private static volatile boolean addressDiscoveryEnabled = true;

    private CoinInstance(CoinTicker ticker, ConfigHelper configHelper) {
        this.ticker = ticker;
        this.configHelper = configHelper;

        addBlockCount(ticker, 0);

        if (isBlocknetNetwork())
            setActiveCurrency(this);
    }

    /**
    * Return mnemonic seed from wallet stored on disk. Correct passphrase required.
    * Returns empty string on error or failure to retrieve mnemonic (or if mnemonic
    * doesn't exist).
    * @param pw caller-owned char array; must be zeroed by the caller after use
    * @return String
    */
    public static String getMnemonicForPw(char[] pw) {
        if (!KeyHandler.existsBaseECKeyFromLocal())
            return "";

        List<String> seed = KeyHandler.getBaseSeed(pw);
        if (seed == null)
            return "";
        return Joiner.on(" ").join(seed);
    }

    public static int getBlockCountByTicker(CoinTicker ticker) {
        if (!blockCounts.containsKey(ticker)) {
            return -1;
        }

        return blockCounts.get(ticker).get();
    }

    public static List<CoinInstance> getCoinInstances() {
        return coinInstances;
    }

    public boolean isBlocknetNetwork() {
        return getTicker() == CoinTicker.BLOCKNET || getTicker() == CoinTicker.BLOCKNET_TESTNET5;
    }

    public static void setActiveCurrency(CoinInstance newActiveCurrency) {
        activeCurrency = newActiveCurrency;

        for (ListenerRegistration<ActiveCoinChangedEventListener> registration : activeCoinChangedListeners) {
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
            if (address.getAddress().toBase58().equals(addressB58))
                return address;
        }

        return null;
    }

    public AddressBalance generateAddress(boolean updateConfig) {
        AddressBalance addressKeyPair = getWalletHelper().generateAddress();
        LegacyAddress address = (LegacyAddress) addressKeyPair.getAddress();
        DumpedPrivateKey privateKey = addressKeyPair.getPrivateKey();
        addressKeyPairs.add(addressKeyPair);
        LOGGER.finer("[wallet] Generated new address, have " + addressKeyPairs.size() + ": " + address.toBase58());

        if (updateConfig) {
            configHelper.setAddressCount(configHelper.getAddressCount() + 1);
            configHelper.writeConfig();
            generatedAddressCount = configHelper.getAddressCount();
        }

        return addressKeyPair;
    }

    public void importPrivateKey(String privKey) {
        AddressBalance addressKeyPair = getWalletHelper().generateFromPrivateKey(privKey);
        AddressBalance addrExists = addressKeyPairs.stream()
                .filter(e -> e.getAddress().equals(addressKeyPair.getAddress())).findAny().orElse(null);

        if (addrExists == null)
            addressKeyPairs.add(addressKeyPair);
    }

    public CoinTicker getTicker() {
        return ticker;
    }

    private static CoinInstance getInstanceByTicker(CoinTicker ticker) {
        for (CoinInstance instance : coinInstances) {
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
                if (ticker == CoinTicker.BLOCKNET)
                    coinInstances.add(0, instance);
                else
                    coinInstances.add(instance);
            }

            if (ticker == CoinTicker.BLOCKNET || ticker == CoinTicker.BLOCKNET_TESTNET5) {
                activeBlocknetNetwork = ticker;
            }

            if (getActiveBlocknetNetwork() != null && (ticker == CoinTicker.BLOCKNET || ticker == CoinTicker.BLOCKNET_TESTNET5)) {
                return getInstanceByTicker(activeBlocknetNetwork);
            }

            return instance;
        }
    }

    /**
    * Change the password. Recreates the wallet file and encrypts with new password.
    * @param oldPassword caller-owned char array; must be zeroed by the caller after use
     * @param newPassword caller-owned char array; must be zeroed by the caller after use
     * @return Error or null
    */
    public static CoinError changePassword(char[] oldPassword, char[] newPassword) {
        if (!KeyHandler.existsBaseECKeyFromLocal()) {
            LOGGER.warning("[wallet] Unable to change the password: Wallet not found on disk");
            return new CoinError("Unable to change the password: Wallet not found on disk",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        List<String> baseSeed = KeyHandler.getBaseSeed(oldPassword);
        if (baseSeed == null) {
            LOGGER.warning("[wallet] Unable to change the password: Incorrect password");
            return new CoinError("Unable to change the password: Incorrect password",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        DeterministicSeed seed = new DeterministicSeed(baseSeed, null, "", System.currentTimeMillis() / 1000);
        List<String> mnemonic = seed.getMnemonicCode();

        if (!KeyHandler.importFromMnemonic(mnemonic, newPassword)) {
            LOGGER.warning("[wallet] Unable to change the password: Failed to create new wallet file");
            return new CoinError("Unable to change the password: Failed to create new wallet file",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        return null;
    }

    public NetworkParameters getNetworkParameters() {
        return networkParameters;
    }

    private BlocknetParameters getBlocknetNetworkParameters() {
        return blocknetNetworkParameters;
    }

    public boolean hasXRouter() {
        return hasXRouter;
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
                LOGGER.warning("[coin] deinit join interrupted for "
                        + CoinTickerUtils.tickerToString(ticker));
            } catch (Exception e) {
                LOGGER.warning("[coin] Error deinitializing RPC server for " + CoinTickerUtils.tickerToString(ticker) + e.getMessage());
            }
            if (coinRPCServer.isAlive())
                LOGGER.warning("[coin] RPC server thread for "
                        + CoinTickerUtils.tickerToString(ticker)
                        + " still alive after deinit"
                        + (interruptedDuringJoin ? " (join was interrupted)" : ""));
        }
    }

    public CoinError init(char[] pw, String userMnemonic, boolean isMnemonic) {
        return init(pw, userMnemonic, isMnemonic, false);
    }

    public CoinError init(char[] pw, String userMnemonic, boolean isMnemonic, boolean xliteRPC) {
        switch (ticker) {
            case BLOCKNET: {
                LOGGER.fine("[coin] Initializing for Blocknet main network.");
                blocknetNetworkParameters = new BlocknetNetworkParameters();
                networkParameters = blocknetNetworkParameters;
                hasXRouter = true;
                rpcPort = 41419;
                break;
            }
            case BLOCKNET_TESTNET5: {
                LOGGER.fine("[coin] Initializing for Blocknet test network v5.");
                blocknetNetworkParameters = new BlocknetTestnet5NetworkParameters();
                networkParameters = blocknetNetworkParameters;
                hasXRouter = true;
                rpcPort = 41419;
                testnet = true;
                break;
            }
            case BITCOIN: {
                LOGGER.fine("[coin] Initializing for Bitcoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 8332;
                break;
            }
            // case BITCOIN_CASH: {
            // 	LOGGER.fine("[coin] Initializing for BitcoinCash main network.");
            // 	networkParameters = new io.xlite.daemon.app.net.protocols.bitcoincash.BitcoinCashNetworkParametersLegacy();
            // 	rpcPort = 48332;
            // 	break;
            // }
            case LITECOIN: {
                LOGGER.fine("[coin] Initializing for Litecoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 9332;
                break;
            }
            case DASHCOIN: {
                LOGGER.fine("[coin] Initializing for Dashcoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 9998;
                break;
            }
            case DIGIBYTE: {
                LOGGER.fine("[coin] Initializing for Digibyte main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 14022;
                break;
            }
            case DOGECOIN: {
                LOGGER.fine("[coin] Initializing for Dogecoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 22555;
                break;
            }
            case SYSCOIN: {
                LOGGER.fine("[coin] Initializing for Syscoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 8370;
                break;
            }
            case PIVX: {
                LOGGER.fine("[coin] Initializing for Pivx main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 9951;
                break;
            }
            case UNOBTANIUM: {
                LOGGER.fine("[coin] Initializing for Unobtanium main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 65111;
                break;
            }
            case PKOIN: {
                LOGGER.fine("[coin] Initializing for Pocketcoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 37071;
                break;
            }
            case RAVENCOIN: {
                LOGGER.fine("[coin] Initializing for Ravencoin main network.");
                CoinError err = loadMigratedParams();
                if (err != null) return err;
                rpcPort = 8766;
                break;
            }
            default: {
                LOGGER.fine("[coin] ERROR: Invalid/unsupported network: " + ticker.toString());
                return new CoinError("Unsupported coin", CoinError.CoinErrorCode.UNSUPPORTEDCOIN);
            }
        }

        configHelper.writeConfig();

        if (xliteRPC) {
            rpcPort = rpcPort + 1;

            if (!configHelper.setRpcPort(rpcPort)) {
                LOGGER.warning("[coin] Failed to allocate RPC port, skipping RPC config");
            }
            configHelper.writeConfig();
        }

        Context.propagate(new Context(networkParameters));

        List<String> baseSeed;
        boolean existsOnDisk = false;

        if (isMnemonic) {
            baseSeed = splitMnemonicChars(pw);
        } else {
            if (KeyHandler.existsBaseECKeyFromLocal()) {
                existsOnDisk = true;
                if (userMnemonic != null) {
                    LOGGER.warning("[wallet] Wallet already exists on disk, ignoring provided mnemonic");
                }
            } else if (userMnemonic != null) {
                if (!KeyHandler.importFromMnemonic(Arrays.asList(userMnemonic.split(" ")), pw)) {
                    LOGGER.warning("[wallet] Unable to create wallet from mnemonic");
                    return new CoinError("Unable to create wallet from mnemonic", CoinError.CoinErrorCode.BADMNEMONIC);
                }
            }

            baseSeed = KeyHandler.getBaseSeed(pw);
        }

        if (baseSeed == null) {
            LOGGER.warning("[wallet] Possible Bad password: Unable to import or create base seed!");
            return new CoinError("Bad password", CoinError.CoinErrorCode.BADPASSWORD);
        }

        // In-memory wallet only
        DeterministicSeed seed = new DeterministicSeed(baseSeed, null, "", System.currentTimeMillis() / 1000);
        wallet = Wallet.fromSeed(networkParameters, seed);
        if (isBlocknetNetwork()) {
            String mnemonic = getMnemonic();
            // LOGGER.fine("[wallet] Mnemonic = " + mnemonic);
        }

        // RUN ADDRESS DISCOVERY ONLY DURING WALLET INITIALIZATION
        // This ensures discovery runs once at wallet startup in ANY case
        if (addressDiscoveryEnabled) {
            LOGGER.fine("[coinAddressDiscoveryService created] Running address discovery");
            runAddressDiscovery();
        } else {
            LOGGER.fine("[coin] Address discovery disabled");
        }

        // Make sure wallet addresses are available
        generateForwardAddresses(true);

        if (configHelper.getRpcPort() == -1000) {
            if (!configHelper.setRpcPort(rpcPort)) {
                LOGGER.warning("[coin] Failed to allocate RPC port, RPC server will not start");
            }
            configHelper.writeConfig();
        } else {
            rpcPort = configHelper.getRpcPort();
        }

        if (configHelper.isRpcEnabled() && configHelper.validAuth() && rpcPort != -1) {
            coinRPCServer = JSONRPCController.getRPCServer(this);
            // Readiness is announced by JSONRPCServer itself AFTER a successful
            // bind ("[rpc] RPC server listening for …") — do not log a
            // success-shaped line before the socket exists.
            LOGGER.finer("[rpc] Requesting start of JSON-RPC server for coin " + CoinTickerUtils.tickerToString(getTicker()) + " on port " + getRPCPort());

            if (coinRPCServer.isAlive())
                coinRPCServer.deinit();

            coinRPCServer.start();
        }

        // Blocknet Network / XRouter not used (Dec 10)
//		if (isBlocknetNetwork() && hasXRouter()) {
//			XRouterMessageSerializer xRouterMessageSerializer = (getBlocknetNetworkParameters()).getXRouterMessageSerializer(false);
//			xRouterPacketManager = new XRouterPacketManager(xRouterMessageSerializer, blocknetNetworkParameters);
//			LOGGER.fine("[coin] This network is a Blocknet network and supports XRouter. Our packet version is " + Integer.toString(XRouterPacketManager.getXRouterPacketVersion(), 16));
//		} else {
//			LOGGER.fine("[coin] WARNING: This network (" + CoinTickerUtils.tickerToString(getTicker()) + ") does not support XRouter.");
//		}
//
//		if (isBlocknetNetwork()) {
//			String userHome = ConfigHelper.getLocalDataDirectory();
//			Preconditions.checkNotNull(userHome);
//
//			File spvDat = new File(userHome,"spv-" + CoinTickerUtils.tickerToString(getTicker()) + ".dat");
//			try {
//				chain = new BlockChain(networkParameters, getWallet(), new SPVBlockStore(networkParameters, spvDat));
//			} catch (BlockStoreException e) {
//				try {
//					chain = new BlockChain(networkParameters, getWallet(), new SPVBlockStore(networkParameters, spvDat));
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
//			blocknetPeerGroup = new BlocknetPeerGroup(this, (BlocknetNetworkParameters) blocknetNetworkParameters, chain);
//			connectToBlocknetNetwork();
//		}

        return null;
    }

    private CoinError loadMigratedParams() {
        try {
            networkParameters = io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters
                    .from(io.xlite.daemon.app.coinconfig.CoinConfigRegistry
                            .get(CoinTickerUtils.tickerToString(ticker)));
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

        LOGGER.fine("[wallet] Generating " + configAddressCount + " forward addresses for network " + getTicker().toString() + ".");

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

    private void connectToBlocknetNetwork() {
        try {
            blocknetPeerGroup.start();
        } catch (Exception e) {
            LOGGER.warning("[coin] Error initializing blocking client for " + CoinTickerUtils.tickerToString(ticker) + e.getMessage());
            return;
        }

        LOGGER.fine("[coin] This network is connecting/connected.");
    }

    public Wallet getWallet() {
        return wallet;
    }

    public String getMnemonic() {
        return Joiner.on(" ").join(Objects.requireNonNull(getWallet().getKeyChainSeed().getMnemonicCode()));
    }

    public double getAllBalances() {
        double balance = 0;

        for (AddressBalance inst : getAddressKeyPairs()) {
            balance += inst.getBalanceProp();
        }

        return balance;
    }

    public String getAllBalancesFormatted() {
        BtcFormat f = BtcFormat.getInstance(BtcFormat.COIN_SCALE);
        return f.format(Coin.valueOf(Sats.fromWholeCoins(getAllBalances())));
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
            LOGGER.fine("[coin] Aborting UTXO checking as the list was updated less than 1 second ago.");
            return;
        }

        String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

        HashMap<String, Object> body = new HashMap<>();
        body.put("currency", currentCurrency);
        body.put("command", "xrmgetutxos");
        body.put("params", getInstance(CoinTickerUtils.stringToTicker(currentCurrency)).getUTXOParams());

        getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrService", body);
    }

    public String sendXrMessage(BlocknetPeer blocknetPeer, String command, HashMap<String, Object> params) {
        return sendXrMessage(blocknetPeer, UUID.randomUUID().toString(), command, params);
    }

    public String sendXrMessage(BlocknetPeer blocknetPeer, String uuid, String command, HashMap<String, Object> params) {
        XRouterMessage message = null;

        if (blocknetPeer == null || !blocknetPeer.getHaveConfig().get()) {
            LOGGER.fine("[sendXrMessage] Config not received yet");
            return null;
        }

        switch (command) {
            case "xrGetBlockCount": {
                String currency = (String) params.get("currency");

                message = getXRouterPacketManager().getXrGetBlockCount(
                        blocknetPeer,
                        uuid,
                        currency,
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            case "xrService": {
                String xrCustomCmd = (String) params.get("command");

                ArrayList paramsList = (ArrayList) params.get("params");

                message = getXRouterPacketManager().getXrService(
                        blocknetPeer,
                        uuid,
                        xrCustomCmd,
                        paramsList,
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            case "xrSendTransaction": {
                String feePayment = (String) params.get("feetx");
                String transaction = (String) params.get("transaction");
                String currency = (String) params.get("currency");

                message = getXRouterPacketManager().getXrSendTransaction(
                        blocknetPeer,
                        uuid,
                        feePayment,
                        currency,
                        transaction,
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            case "xrGetBlockHash": {
                String feePayment = (String) params.get("feetx");
                String blockIndex = (String) params.get("blockIndex");
                String currency = (String) params.get("currency");

                message = getXRouterPacketManager().getXrGetBlockHash(
                        blocknetPeer,
                        uuid,
                        feePayment,
                        currency,
                        blockIndex,
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            case "xrGetBlock": {
                String feePayment = (String) params.get("feetx");
                String blockHash = (String) params.get("blockHash");
                String currency = (String) params.get("currency");

                message = getXRouterPacketManager().getXrGetBlock(
                        blocknetPeer,
                        uuid,
                        feePayment,
                        currency,
                        blockHash,
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            case "xrGetTransaction": {
                String txid = (String) params.get("txid");
                String currency = (String) params.get("currency");

                message = getXRouterPacketManager().getXrGetTransaction(
                        blocknetPeer,
                        uuid,
                        currency,
                        txid,
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            case "xrGetConfig": {
                message = getXRouterPacketManager().getXrGetConfig(
                        blocknetPeer,
                        uuid,
                        "self",
                        keyHandler.getBaseECKey(),
                        keyHandler.getPublicKey());
                break;
            }
            default: {
                LOGGER.fine("[coin] ERROR: Unknown XRouter Message! Command: " + command);
                uuid = null;
                break;
            }
        }

        if (message != null)
            blocknetPeerGroup.sendMessage(blocknetPeer, message);

        return uuid;
    }

    public JsonArray getAllUTXOS() {
        MonetaryFormat PLAIN_FORMAT = MonetaryFormat.BTC.minDecimals(8).repeatOptionalDecimals(1, 0).noCode();

        JsonArray unspentTxsJSON = new JsonArray();
        for (AddressBalance addressBalance : getAddressKeyPairs()) {
            for (UTXO utxo : addressBalance.getUtxos()) {
                if (utxo.isSpent())
                    continue;

                org.bitcoinj.core.UTXO bUtxo;
                try {
                    bUtxo = utxo.createUTXO();
                } catch (Exception e) {
                    // One malformed/bech32 address must not kill the whole
                    // listunspent response — skip the row, keep the rest.
                    LOGGER.warning("[coin] Skipping unparseable UTXO for " + CoinTickerUtils.tickerToString(getTicker())
                            + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                    continue;
                }

                JsonObject utxoJSON = new JsonObject();
                utxoJSON.addProperty("txid", bUtxo.getHash().toString());
                utxoJSON.addProperty("vout", bUtxo.getIndex());
                utxoJSON.addProperty("address", bUtxo.getAddress());

                Monetary monetary = new Monetary() {
                    @Override
                    public int smallestUnitExponent() {
                        return 8;
                    }

                    @Override
                    public long getValue() {
                        return utxo.getValue();
                    }

                    @Override
                    public int signum() {
                        if (this.getValue() == 0)
                            return 0;
                        return this.getValue() < 0 ? -1 : 1;
                    }
                };

                BigDecimal amountDecimal = new BigDecimal(PLAIN_FORMAT.format(monetary).toString());

                utxoJSON.addProperty("amount", amountDecimal);
                utxoJSON.addProperty("scriptPubKey", new String(Hex.encode(bUtxo.getScript().getProgram())));
                utxoJSON.addProperty("spendable", true);

                int totalBlocks = CoinInstance.getBlockCountByTicker(getTicker());
                int confirmations = (totalBlocks - bUtxo.getHeight()) + 1;
                if (bUtxo.getHeight() == 0)
                    confirmations = 0;
                // Pre-first-poll the height cache holds a negative sentinel
                // (-1); clamp so no caller ever sees negative confirmations.
                confirmations = Math.max(0, confirmations);

                utxoJSON.addProperty("confirmations", confirmations);

                unspentTxsJSON.add(utxoJSON);
            }
        }

        return unspentTxsJSON;
    }

    public JsonArray getAllTransactions() {
        JsonArray transactionsJSON = new JsonArray();
        for (Transaction tx : transactionList.values()) {
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
            txJSON.addProperty("time", tx.getBlocktime());

            txJSON.addProperty("confirmations", tx.getConfirmations());

            transactionsJSON.add(txJSON);
        }


        return transactionsJSON;
    }

    public ArrayList<String> getUTXOParams() {
        ArrayList<String> params = new ArrayList<>();
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
        if (this.walletHelper == null)
            this.walletHelper = new WalletHelper(this);

        return this.walletHelper;
    }

    public void addBlockCount(CoinTicker ticker, Integer blockCount) {
        // Plain set: the cache must track the true tip. The old Math.max
        // ratchet pinned a stale-high height forever (e.g. after network
        // switch or a rollback), and pre-first-poll zeros poisoned
        // confirmation math.
        blockCounts.computeIfAbsent(ticker, k -> new AtomicInteger(0))
                .set(blockCount);
    }

    public void addCloudTransaction(CloudTransaction cloudTransaction) {
        synchronized (transactionObservableList) {
            if (transactionObservableList.isEmpty()) {
                transactionObservableList.add(cloudTransaction);
                return;
            }

            CloudTransaction tx = transactionObservableList.stream()
                    .filter(e -> e.getTxHash().equals(cloudTransaction.getTxHash())).findAny().orElse(null);

            if (tx == null) {
                transactionObservableList.add(cloudTransaction);
            }
        }
    }

    public void processUtxos(List<UTXO> utxoList) {
        if (utxoList == null) {
            LOGGER.warning("[coin-" + CoinTickerUtils.tickerToString(getTicker()) + "] processUtxos: null UTXO list received");
            return;
        }
        LOGGER.fine("[coin-" + CoinTickerUtils.tickerToString(getTicker()) + "] processUtxos: remote returned " + utxoList.size() + " UTXOs, tracking " + addressKeyPairs.size() + " addresses locally");
        int added = 0, skipped = 0;

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
                LOGGER.warning("[utxo-parser] Warning: Encountered non-tracked address in reply: " + utxo.getAddress());
                skipped++;
                continue;
            }
            boolean isNewUtxo = addressBalance.addUtxo(utxo);
            if (isNewUtxo) {
                added++;
                addCloudTransaction(new CloudTransaction(utxo));
                LOGGER.finer("[utxo-parser] Added new UTXO, address: " + utxo.getAddress() + " value: " + utxo.getAmount());
            }
        }
        LOGGER.fine("[coin-" + CoinTickerUtils.tickerToString(getTicker()) + "] processUtxos: added=" + added + ", skipped=" + skipped);
        setLastUtxoUpdate(System.currentTimeMillis());
    }

    public void processHistoryTxs(List<Transaction> transactions) {
        for (Transaction tx : transactions)
            transactionList.put(tx.uid(), tx);
    }

    private void setLastUtxoUpdate(long newUtxoTime) {
        lastUtxoUpdate = newUtxoTime;
    }

    public void reloadConfig() {
        this.configHelper.loadConfig();
        rpcPort = configHelper.getRpcPort();
        if (configHelper.getAddressCount() != generatedAddressCount)
            generateForwardAddresses(false);

        if (coinRPCServer == null)
            return; // no rpc available, skip

        // Atomic retire+create: a concurrent reloadconfig must never
        // receive the retiring server instance.
        coinRPCServer = JSONRPCController.rebindRPCServer(this);

        LOGGER.finer("[rpc] Requesting start of JSON-RPC server for coin " + CoinTickerUtils.tickerToString(getTicker()) + " on port " + getRPCPort());
        coinRPCServer.start();

        // Verify the rebind actually took — a silent bind failure used to
        // leave the coin RPC dead while callers already got success. Budget
        // exceeds the server's own 20x250ms retry window so a slow but
        // successful late rebind cannot trip this alarm.
        if (!coinRPCServer.awaitBound(10000)) {
            LOGGER.severe("[rpc] Failed to rebind JSON-RPC server for coin "
                    + CoinTickerUtils.tickerToString(getTicker())
                    + " on port " + getRPCPort() + " after reloadconfig"
                    + "; new-server state: "
                    + coinRPCServer.lifecycleState());
        }
    }

    public KeyHandler getKeyHandler() {
        return keyHandler;
    }

    public BlocknetPeerGroup getBlocknetPeerGroup() {
        return blocknetPeerGroup;
    }

    public BlocknetPeer getBestBlocknetPeer(String currency) {
        if (blocknetPeerGroup == null)
            return null;

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

    public boolean isTestnet() {
        return testnet;
    }

    public static String getVersionString() {
        return Version.SUBVERSION;
    }

    public void incrementUpdateFailures() {
        updateFailures.incrementAndGet();
    }

    public void resetUpdateFailures() {
        updateFailures.set(0);
    }

    public void runAddressDiscovery() {
        String currency = CoinTickerUtils.tickerToString(this.getTicker());

        if (!configHelper.isRpcEnabled()) {
            LOGGER.fine("[coin-" + currency + "] RPC disabled, skipping address discovery");
            return;
        }

        if (discoveryService == null) {
            discoveryService = new AddressDiscoveryService(this);
            LOGGER.fine("[coin-" + currency + "] AddressDiscoveryService created");
        }

        int discoveredCount = discoveryService.discoverAddressCount();
        discoveryService.clearExternalChainKey();
        int currentCount = configHelper.getAddressCount();

        if (discoveredCount > currentCount) {
            LOGGER.info("[coin-" + currency + "] Address discovery found " +
                    discoveredCount + " addresses (was " + currentCount + ")");

            // Update config and generate missing addresses
            configHelper.setAddressCount(discoveredCount);
            configHelper.writeConfig();

            LOGGER.info("[coin-" + currency + "] Updated address count to " +
                    discoveredCount);
        } else {
            LOGGER.fine("[coin-" + currency + "] No new addresses discovered, " +
                    "keeping current count: " + currentCount);
        }
    }

    public boolean isInstanceRunning() {
        return updateFailures.get() < 5;
    }

    public static void setAddressDiscoveryEnabled(boolean enabled) {
        addressDiscoveryEnabled = enabled;
    }

    public static boolean isAddressDiscoveryEnabled() {
        return addressDiscoveryEnabled;
    }

    /**
     * Split a mnemonic char[] into individual word strings without
     * materializing the full mnemonic as a String.
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