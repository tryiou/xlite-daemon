package io.cloudchains.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class AddressDiscoveryService {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final int BATCH_SIZE = 250;
    private static final int NUM_BATCHES = 100;  // 250 * 100 = 25,000 address range
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private static int DISCOVERY_TIMEOUT_MS = 30000; // non-final for testing

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

    private String getLogPrefix() {
        return "[discovery-" + currencyString + "]";
    }

    public AddressDiscoveryService(CoinInstance coinInstance) {
        this(coinInstance, new HTTPClient(5));
    }

    public AddressDiscoveryService(CoinInstance coinInstance, HTTPClient httpClient) {
        this.coinInstance = coinInstance;
        this.httpClient = httpClient;
        this.configHelper = coinInstance.getConfigHelper();
        this.currencyString = CoinTickerUtils.tickerToString(coinInstance.getTicker());
        LOGGER.log(Level.FINER, getLogPrefix() + " AddressDiscoveryService initialized for " + currencyString);
    }

    public static void setDiscoveryTimeoutMs(int timeoutMs) {
        DISCOVERY_TIMEOUT_MS = timeoutMs;
    }

    /**
     * Discovers the correct address count by scanning batches sequentially from batch 0.
     *
     * Each batch (100 addresses) is checked for any UTXOs. As long as a batch has funds,
     * the scan continues to the next batch. The first empty batch marks the boundary.
     * Within the last non-empty batch, the exact highest funded address is found.
     *
     * Returns the index of the last funded address + 1 as the discovered address count.
     * Returns the current config value if batch 0 is empty (wallet unused) or on failure.
     */
    public int discoverAddressCount() {
        long startTime = System.currentTimeMillis();
        int currentAddressCount = configHelper.getAddressCount();

        LOGGER.log(Level.FINE, getLogPrefix() + " Starting sequential batch scan");

        try {
            if (isTimedOut(startTime)) return currentAddressCount;

            int lastNonEmptyBatch = -1;
            List<UTXO> lastBatchUtxos = null;
            int consecutiveErrors = 0;

            for (int i = 0; i < NUM_BATCHES; i++) {
                if (isTimedOut(startTime)) {
                    LOGGER.log(Level.WARNING, getLogPrefix() + " Timeout at batch " + i);
                    break;
                }

                ensureAddressesGenerated((i + 1) * BATCH_SIZE);

                List<UTXO> utxos = probeBatch(i);

                if (utxos == null) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        LOGGER.log(Level.WARNING, getLogPrefix()
                                + " Aborting: " + MAX_CONSECUTIVE_ERRORS + " consecutive HTTP failures");
                        break;
                    }
                    continue;
                }

                consecutiveErrors = 0;
                if (utxos.isEmpty()) break;

                lastNonEmptyBatch = i;
                lastBatchUtxos = utxos;
            }

            if (lastNonEmptyBatch < 0) {
                LOGGER.log(Level.INFO, getLogPrefix() + " No UTXOs found");
                return currentAddressCount;
            }

            // Find exact highest funded address in the last non-empty batch
            int batchStart = lastNonEmptyBatch * BATCH_SIZE;
            List<AddressBalance> batch = getBatch(batchStart, BATCH_SIZE);

            int lastUsedInBatch = findLastUsedIndexInBatch(batch, lastBatchUtxos);
            int discoveredCount = batchStart + lastUsedInBatch + 1;

            LOGGER.log(Level.INFO, getLogPrefix() + " Discovery complete: lastBatch="
                    + lastNonEmptyBatch + ", count=" + discoveredCount
                    + ", time=" + (System.currentTimeMillis() - startTime) + "ms");

            return discoveredCount;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, getLogPrefix() + " Error during discovery", e);
            return currentAddressCount;
        }
    }

    /**
     * Probe a batch for UTXOs.
     * @return UTXO list (may be empty), or null on HTTP failure
     */
    private List<UTXO> probeBatch(int batchIndex) {
        List<AddressBalance> batch = getBatch(batchIndex * BATCH_SIZE, BATCH_SIZE);
        return checkBatchForUtxos(batch);
    }

    /**
     * Get a slice of addresses [startIndex, startIndex + size) from CoinInstance.
     */
    private List<AddressBalance> getBatch(int startIndex, int size) {
        List<AddressBalance> batch = new ArrayList<>(size);
        int end = Math.min(startIndex + size, coinInstance.getAddressKeyPairs().size());
        for (int i = startIndex; i < end; i++) {
            batch.add(coinInstance.getAddressKeyPairs().get(i));
        }
        return batch;
    }

    /**
     * Ensure addresses up to count are generated in CoinInstance.
     */
    private void ensureAddressesGenerated(int count) {
        int currentGenerated = coinInstance.getAddressKeyPairs().size();
        if (count > currentGenerated) {
            int toGenerate = count - currentGenerated;
            for (int i = 0; i < toGenerate; i++) {
                coinInstance.generateAddress(false);
            }
            LOGGER.log(Level.FINE, getLogPrefix() + " Generated " + toGenerate + " addresses");
        }
    }

    private boolean isTimedOut(long startTime) {
        return (System.currentTimeMillis() - startTime) > DISCOVERY_TIMEOUT_MS;
    }

    /**
     * Check a batch of addresses for UTXOs via HTTP.
     * @return list of UTXOs found (may be empty), or null on HTTP failure
     */
    private List<UTXO> checkBatchForUtxos(List<AddressBalance> batch) {
        if (batch.isEmpty()) {
            return new ArrayList<>();
        }
        String[] addresses = batch.stream()
                .map(addr -> addr.getAddress().toBase58())
                .toArray(String[]::new);
        JsonArray utxoResponse = null;
        try {
            utxoResponse = httpClient.getUtxosUncached(coinInstance.getTicker(), addresses);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, getLogPrefix() + " HTTP request failed for addresses "
                    + addresses[0] + "..." + addresses[addresses.length - 1] + " - " + e.getMessage(), e);
            return null;
        }
        if (utxoResponse == null || utxoResponse.size() == 0) {
            return new ArrayList<>();
        }
        List<UTXO> utxos = new ArrayList<>();
        for (JsonElement element : utxoResponse) {
            try {
                JsonObject utxoJson = element.getAsJsonObject();

                JsonElement addressElement = utxoJson.get("address");
                JsonElement txidElement = utxoJson.get("txid");
                JsonElement voutElement = utxoJson.get("vout");
                JsonElement confirmationsElement = utxoJson.get("confirmations");
                JsonElement valueElement = utxoJson.get("value");

                if (addressElement == null || txidElement == null || voutElement == null ||
                        confirmationsElement == null || valueElement == null ||
                        addressElement.isJsonNull() || txidElement.isJsonNull() || voutElement.isJsonNull() ||
                        confirmationsElement.isJsonNull() || valueElement.isJsonNull()) {
                    LOGGER.log(Level.WARNING, getLogPrefix() + " Skipping invalid UTXO - missing required fields");
                    continue;
                }

                UTXO utxo = new UTXO(
                        coinInstance.getTicker(),
                        addressElement.getAsString(),
                        txidElement.getAsString(),
                        voutElement.getAsInt(),
                        confirmationsElement.getAsInt(),
                        (long) (valueElement.getAsDouble() * 100000000.0)
                );
                utxos.add(utxo);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, getLogPrefix() + " Failed to parse UTXO response element: " + e.getMessage());
            }
        }
        return utxos;
    }

    /**
     * Find the highest address index within a batch that has UTXOs.
     * Uses HashMap for O(1) lookups when batch size > 20.
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
