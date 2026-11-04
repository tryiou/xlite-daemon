package io.xlite.daemon.app.util;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;

import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class AddressDiscoveryService {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final int BATCH_SIZE = 500;
    private static final int NUM_BATCHES = 100; // 500 * 100 = 50,000 address range
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private static int DISCOVERY_TIMEOUT_MS = 30000; // non-final for testing

    private boolean lastScanComplete;

    public static int getBatchSize() {
        return BATCH_SIZE;
    }

    public static int getNumBatches() {
        return NUM_BATCHES;
    }

    private final CoinInstance coinInstance;
    private final HTTPClient httpClient;
    private final ConfigHelper configHelper;
    private final String currencyString;
    private DeterministicKey externalChainKey;

    private String getLogPrefix() {
        return "[discovery-" + currencyString + "]";
    }

    public AddressDiscoveryService(CoinInstance coinInstance) {
        this(coinInstance, new HTTPClient());
    }

    public AddressDiscoveryService(CoinInstance coinInstance, HTTPClient httpClient) {
        this.coinInstance = coinInstance;
        this.httpClient = httpClient;
        this.configHelper = coinInstance.getConfigHelper();
        this.currencyString = CoinTickerUtils.tickerToString(coinInstance.getTicker());
        this.externalChainKey = initExternalChainKey(coinInstance.getWallet());
        LOGGER.finer(getLogPrefix() + " AddressDiscoveryService initialized for " + currencyString);
    }

    /**
     * Derive and cache the external chain key from the wallet seed. Clears the seed copy
     * immediately after derivation per security conventions. Note: the wallet's original seed is
     * never modified.
     */
    private static DeterministicKey initExternalChainKey(Wallet wallet) {
        byte[] seedBytes = wallet.getKeyChainSeed().getSeedBytes();
        if (seedBytes == null) {
            return null;
        }
        byte[] seedCopy = Arrays.copyOf(seedBytes, seedBytes.length);
        try {
            DeterministicKey masterKey = HDKeyDerivation.createMasterPrivateKey(seedCopy);
            ImmutableList<ChildNumber> accountPath = wallet.getActiveKeyChain().getAccountPath();
            DeterministicKey accountKey = masterKey;
            for (ChildNumber child : accountPath) {
                accountKey = HDKeyDerivation.deriveChildKey(accountKey, child);
            }
            return HDKeyDerivation.deriveChildKey(accountKey, ChildNumber.ZERO);
        } finally {
            Arrays.fill(seedCopy, (byte) 0);
        }
    }

    /** Clear the cached external chain key from memory after address discovery is complete. */
    public void clearExternalChainKey() {
        this.externalChainKey = null;
    }

    public static void setDiscoveryTimeoutMs(int timeoutMs) {
        DISCOVERY_TIMEOUT_MS = timeoutMs;
    }

    /**
     * Discovers the correct address count by scanning batches sequentially from batch 0.
     *
     * <p>Each batch (100 addresses) is checked for any UTXOs. As long as a batch has funds, the
     * scan continues to the next batch. The first empty batch marks the boundary. Within the last
     * non-empty batch, the exact highest funded address is found.
     *
     * <p>Returns the index of the last funded address + 1 as the discovered address count. Returns
     * the current config value if batch 0 is empty (wallet unused) or on failure.
     */
    public int discoverAddressCount() {
        long startTime = System.currentTimeMillis();
        int currentAddressCount = configHelper.getAddressCount();
        lastScanComplete = false;
        LOGGER.fine(getLogPrefix() + " Starting sequential batch scan");
        try {
            if (isTimedOut(startTime)) {
                return currentAddressCount;
            }
            BatchScan scan = scanBatches(startTime);
            lastScanComplete = scan.complete();
            if (scan.lastNonEmptyBatch() < 0) {
                LOGGER.info(getLogPrefix() + " No UTXOs found");
                return currentAddressCount;
            }
            int discoveredCount = discoveredCount(scan);
            LOGGER.info(
                    getLogPrefix()
                            + " Discovery complete: lastBatch="
                            + scan.lastNonEmptyBatch()
                            + ", count="
                            + discoveredCount
                            + ", time="
                            + (System.currentTimeMillis() - startTime)
                            + "ms");
            return discoveredCount;
        } catch (RuntimeException e) {
            LOGGER.warning(getLogPrefix() + " Error during discovery" + e.getMessage());
            return currentAddressCount;
        }
    }

    /**
     * Whether the last {@link #discoverAddressCount()} run scanned the whole chain it needed to.
     *
     * <p>A scan aborted by a timeout or by repeated provider failures is not a statement about the
     * wallet, so the caller must retry it later instead of treating the count as final.
     */
    public boolean wasComplete() {
        return lastScanComplete;
    }

    /**
     * Scans batches from 0 until the first empty one, a timeout, or too many consecutive failures.
     * Keeps the last non-empty batch, which is where the exact address count is found.
     */
    private BatchScan scanBatches(long startTime) {
        int lastNonEmptyBatch = -1;
        List<UTXO> lastBatchUtxos = null;
        int consecutiveErrors = 0;
        for (int batchIndex = 0; batchIndex < NUM_BATCHES; batchIndex++) {
            if (isTimedOut(startTime)) {
                LOGGER.warning(getLogPrefix() + " Timeout at batch " + batchIndex);
                return new BatchScan(lastNonEmptyBatch, lastBatchUtxos, false);
            }
            List<UTXO> utxos = probeBatch(batchIndex);
            if (utxos == null) {
                consecutiveErrors++;
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    LOGGER.warning(
                            getLogPrefix()
                                    + " Aborting: "
                                    + MAX_CONSECUTIVE_ERRORS
                                    + " consecutive provider failures");
                    return new BatchScan(lastNonEmptyBatch, lastBatchUtxos, false);
                }
                continue;
            }
            consecutiveErrors = 0;
            if (utxos.isEmpty()) {
                return new BatchScan(lastNonEmptyBatch, lastBatchUtxos, true);
            }
            lastNonEmptyBatch = batchIndex;
            lastBatchUtxos = utxos;
        }
        return new BatchScan(lastNonEmptyBatch, lastBatchUtxos, true);
    }

    /** The index of the last funded address plus one, found in the last non-empty batch. */
    private int discoveredCount(BatchScan scan) {
        int batchStart = scan.lastNonEmptyBatch() * BATCH_SIZE;
        List<AddressBalance> batch = deriveAddressRange(batchStart, BATCH_SIZE);
        try {
            return batchStart + findLastUsedIndexInBatch(batch, scan.lastBatchUtxos()) + 1;
        } finally {
            for (AddressBalance address : batch) {
                address.clearPrivateKey();
            }
        }
    }

    /**
     * The last batch that held UTXOs, or -1 when none did; complete when the scan reached its end.
     */
    private record BatchScan(int lastNonEmptyBatch, List<UTXO> lastBatchUtxos, boolean complete) {}

    /**
     * Probe a batch for UTXOs.
     *
     * @return UTXO list (may be empty), or null on provider failure
     */
    private List<UTXO> probeBatch(int batchIndex) {
        int startIndex = batchIndex * BATCH_SIZE;
        List<AddressBalance> batch = deriveAddressRange(startIndex, BATCH_SIZE);
        try {
            return checkBatchForUtxos(batch);
        } finally {
            for (AddressBalance addr : batch) {
                addr.clearPrivateKey();
            }
        }
    }

    /**
     * Derive addresses at specific HD indices without modifying wallet state. Uses the pre-derived
     * external chain key, bypassing the wallet lookahead window limitation.
     */
    private List<AddressBalance> deriveAddressRange(int startIndex, int count) {
        List<AddressBalance> result = new ArrayList<>(count);
        if (externalChainKey == null) {
            return result;
        }
        NetworkParameters params = coinInstance.getNetworkParameters();

        for (int i = 0; i < count; i++) {
            int index = startIndex + i;
            DeterministicKey addressKey =
                    HDKeyDerivation.deriveChildKey(externalChainKey, new ChildNumber(index, false));
            LegacyAddress address =
                    LegacyAddress.fromPubKeyHash(params, addressKey.getPubKeyHash());
            DumpedPrivateKey privKey = addressKey.getPrivateKeyEncoded(params);
            result.add(new AddressBalance(address, privKey));
        }
        return result;
    }

    private boolean isTimedOut(long startTime) {
        return (System.currentTimeMillis() - startTime) > DISCOVERY_TIMEOUT_MS;
    }

    /**
     * Check a batch of addresses for UTXOs via HTTP.
     *
     * @return list of UTXOs found (may be empty), or null on provider failure
     */
    private List<UTXO> checkBatchForUtxos(List<AddressBalance> batch) {
        if (batch.isEmpty()) {
            return new ArrayList<>();
        }
        String[] addresses =
                batch.stream().map(addr -> addr.getAddress().toBase58()).toArray(String[]::new);
        JsonArray utxoResponse = null;
        try {
            utxoResponse = httpClient.getUtxosUncached(coinInstance.getTicker(), addresses);
        } catch (Exception e) {
            LOGGER.warning(
                    getLogPrefix()
                            + " provider request failed for addresses "
                            + addresses[0]
                            + "..."
                            + addresses[addresses.length - 1]
                            + " - "
                            + e.getMessage());
            return null;
        }
        if (utxoResponse == null || utxoResponse.size() == 0) {
            return new ArrayList<>();
        }
        List<UTXO> utxos = new ArrayList<>();
        for (JsonElement element : utxoResponse) {
            if (!element.isJsonObject()) {
                continue;
            }
            UTXO utxo = parseUtxo(element.getAsJsonObject());
            if (utxo != null) {
                utxos.add(utxo);
            }
        }
        return utxos;
    }

    /**
     * The UTXO a rendered provider row describes, or null when a required field is missing.
     *
     * <p>This reads the RPC row shape ({@code txid}, {@code vout}, {@code confirmations}, {@code
     * value} in whole coins), because the probe consumes {@code getUtxosUncached}'s rendered
     * output. The engine reads native {@code scripthash.listunspent} rows instead, so the two
     * shapes are parsed where they are produced, not through a shared shape that fits neither.
     */
    private UTXO parseUtxo(JsonObject row) {
        String address = requiredString(row, "address");
        String txid = requiredString(row, "txid");
        JsonElement vout = row.get("vout");
        JsonElement confirmations = row.get("confirmations");
        JsonElement value = row.get("value");
        if (address == null
                || txid == null
                || isNull(vout)
                || isNull(confirmations)
                || isNull(value)) {
            LOGGER.warning(getLogPrefix() + " Skipping invalid UTXO - missing required fields");
            return null;
        }
        return new UTXO(
                coinInstance.getTicker(),
                address,
                txid,
                vout.getAsInt(),
                confirmations.getAsInt(),
                UTXO.baseUnitsForTicker(coinInstance.getTicker(), value.getAsDouble()));
    }

    private static String requiredString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        return isNull(element) ? null : element.getAsString();
    }

    private static boolean isNull(JsonElement element) {
        return element == null || element.isJsonNull();
    }

    /**
     * Find the highest address index within a batch that has UTXOs. Uses HashMap for O(1) lookups
     * when batch size > 20.
     */
    private int findLastUsedIndexInBatch(List<AddressBalance> batch, List<UTXO> utxos) {
        Map<String, Integer> addressToIndex = new HashMap<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            addressToIndex.put(batch.get(i).getAddress().toBase58(), i);
        }

        int lastIndex = 0;
        for (UTXO utxo : utxos) {
            Integer index = addressToIndex.get(utxo.getAddress());
            if (index != null) {
                lastIndex = Math.max(lastIndex, index);
            }
        }
        return lastIndex;
    }
}
