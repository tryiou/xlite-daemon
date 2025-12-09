package io.cloudchains.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import org.bitcoinj.core.Address;

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
    
    // Simplified configuration values
    private static final int GAP_LIMIT = 25;
    private static final int BATCH_SIZE = 100;
    private static final int MAX_DISCOVERY_DEPTH = 10000;
    private static final int DISCOVERY_TIMEOUT_MS = 30000; // 30 seconds max
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    
    private final CoinInstance coinInstance;
    private final HTTPClient httpClient;
    private final ConfigHelper configHelper;
    private final String currencyString;
    
    // Enhanced logging helper
    private String getLogPrefix() {
        return "[discovery-" + currencyString + "]";
    }
    
    public AddressDiscoveryService(CoinInstance coinInstance) {
        this.coinInstance = coinInstance;
        this.httpClient = new HTTPClient(5);
        this.configHelper = coinInstance.getConfigHelper();
        this.currencyString = CoinTickerUtils.tickerToString(coinInstance.getTicker());
        LOGGER.log(Level.INFO, getLogPrefix() + " AddressDiscoveryService initialized for " + currencyString);
    }
    
    /**
     * Main discovery method - determines correct addressCount based on last used address with funds + 1
     */
    public int discoverAddressCount() {
        LOGGER.log(Level.INFO, getLogPrefix() + " Starting address discovery for " + currencyString);
        
        long discoveryStartTime = System.currentTimeMillis();
        int consecutiveFailures = 0;
        int lastUsedIndex = -1;
        int consecutiveEmpty = 0;
        int currentAddressCount = configHelper.getAddressCount();
        int batchStart = currentAddressCount;
        
        LOGGER.log(Level.INFO, getLogPrefix() + " Starting discovery from address index: " + currentAddressCount);
        
        try {
            while (consecutiveEmpty < GAP_LIMIT && batchStart < MAX_DISCOVERY_DEPTH) {
                // Check for discovery timeout
                long elapsedTime = System.currentTimeMillis() - discoveryStartTime;
                if (elapsedTime > DISCOVERY_TIMEOUT_MS) {
                    LOGGER.log(Level.WARNING, getLogPrefix() + " Discovery timeout reached after " +
                              (elapsedTime / 1000) + " seconds, aborting discovery");
                    return configHelper.getAddressCount();
                }
                
                // Check for consecutive failures (circuit breaker)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    LOGGER.log(Level.SEVERE, getLogPrefix() + " Maximum consecutive failures (" +
                              MAX_CONSECUTIVE_FAILURES + ") reached, aborting discovery");
                    return configHelper.getAddressCount();
                }
                
                // Progress logging every 5 batches
                if (batchStart > currentAddressCount && batchStart % (BATCH_SIZE * 5) == 0) {
                    LOGGER.log(Level.INFO, getLogPrefix() + " Discovery progress: " + batchStart +
                              " addresses checked, " + consecutiveEmpty + " consecutive empty");
                }
                
                LOGGER.log(Level.FINE, getLogPrefix() + " Processing batch starting at index " + batchStart);
                
                // Generate batch of addresses
                List<AddressBalance> batch = generateAddressBatch(batchStart, BATCH_SIZE);
                
                // Check for UTXOs in batch
                List<UTXO> batchUtxos = checkBatchForUtxos(batch);
                
                // Handle HTTP failures with circuit breaker
                if (batchUtxos == null) {
                    consecutiveFailures++;
                    LOGGER.log(Level.WARNING, getLogPrefix() + " HTTP failure " + consecutiveFailures +
                              "/" + MAX_CONSECUTIVE_FAILURES + " for batch starting at " + batchStart);
                    // Continue to next batch instead of failing immediately
                    batchStart += BATCH_SIZE;
                    continue;
                } else {
                    consecutiveFailures = 0; // Reset failure count on success
                }
                
                if (!batchUtxos.isEmpty()) {
                    // Found UTXOs - update last used index
                    int batchLastUsedIndex = findLastUsedIndex(batch, batchUtxos);
                    int globalLastUsedIndex = batchStart + batchLastUsedIndex;
                    lastUsedIndex = Math.max(lastUsedIndex, globalLastUsedIndex);
                    consecutiveEmpty = 0;
                    
                    LOGGER.log(Level.INFO, getLogPrefix() + " Found UTXOs in batch, last used index: " + 
                              globalLastUsedIndex + ", batch range: " + batchStart + "-" + 
                              (batchStart + BATCH_SIZE - 1));
                } else {
                    consecutiveEmpty += BATCH_SIZE;
                    LOGGER.log(Level.INFO, getLogPrefix() + " Empty batch (addresses " + batchStart + "-" + 
                              (batchStart + BATCH_SIZE - 1) + "), consecutive empty: " + consecutiveEmpty);
                }
                
                batchStart += BATCH_SIZE;
                
                // Safety check for max depth
                if (batchStart >= MAX_DISCOVERY_DEPTH) {
                    LOGGER.log(Level.WARNING, getLogPrefix() + " Hit max discovery depth at " + MAX_DISCOVERY_DEPTH);
                    break;
                }
            }
            
            // Calculate final address count: last used address with funds detected + 1
            int finalCount;
            if (lastUsedIndex >= 0) {
                // Found used addresses, set to last used + 1
                finalCount = lastUsedIndex + 1;
                LOGGER.log(Level.INFO, getLogPrefix() + " Found used addresses, setting address count to: " + finalCount);
            } else {
                // No used addresses found, keep current config value
                finalCount = configHelper.getAddressCount();
                LOGGER.log(Level.INFO, getLogPrefix() + " No used addresses found, keeping current address count: " + finalCount);
            }
            
            LOGGER.log(Level.INFO, getLogPrefix() + " Discovery complete for " + currencyString +
                      ". Last used index: " + lastUsedIndex + ", final address count: " + finalCount);
            
            return finalCount;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, getLogPrefix() + " Error during discovery for " + currencyString, e);
            return configHelper.getAddressCount();
        }
    }
    
    /**
     * Generate a batch of addresses starting from a specific index
     */
    private List<AddressBalance> generateAddressBatch(int startIndex, int batchSize) {
        List<AddressBalance> batch = new ArrayList<>();
        
        // Ensure we have enough addresses generated
        int currentGenerated = coinInstance.getAddressKeyPairs().size();
        int needed = startIndex + batchSize;
        
        if (needed > currentGenerated) {
            // Generate additional addresses starting from currentGenerated
            for (int i = currentGenerated; i < needed; i++) {
                AddressBalance addr = coinInstance.generateAddress(false);
                // Don't add to batch here - we'll extract the correct slice below
            }
            LOGGER.log(Level.INFO, getLogPrefix() + " Generated " + (needed - currentGenerated) +
                      " new addresses for " + currencyString);
        }
        
        // Always extract the batch from the correct startIndex range
        for (int i = startIndex; i < needed; i++) {
            batch.add(coinInstance.getAddressKeyPairs().get(i));
        }
        
        return batch;
    }
    
    /**
     * Check a batch of addresses for UTXOs
     */
    private List<UTXO> checkBatchForUtxos(List<AddressBalance> batch) {
        if (batch.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Extract addresses for UTXO query
        String[] addresses = batch.stream()
            .map(addr -> addr.getAddress().toBase58())
            .toArray(String[]::new);
        
        JsonArray utxoResponse = null;
        try {
            utxoResponse = httpClient.getUtxosUncached(coinInstance.getTicker(), addresses);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, getLogPrefix() + " HTTP request failed for addresses " +
                      addresses[0] + "..." + addresses[addresses.length - 1], e);
            return null; // Signal failure to caller
        }
        
        if (utxoResponse == null || utxoResponse.size() == 0) {
            return new ArrayList<>();
        }
        
        List<UTXO> utxos = new ArrayList<>();
        for (JsonElement element : utxoResponse) {
            try {
                JsonObject utxoJson = element.getAsJsonObject();
                UTXO utxo = new UTXO(
                    coinInstance.getTicker(),
                    utxoJson.get("address").getAsString(),
                    utxoJson.get("txid").getAsString(),
                    utxoJson.get("vout").getAsInt(),
                    utxoJson.get("confirmations").getAsInt(),
                    (long) (utxoJson.get("value").getAsDouble() * 100000000.0)
                );
                utxos.add(utxo);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, getLogPrefix() + " Failed to parse UTXO response element", e);
                // Continue processing other UTXOs instead of failing completely
            }
        }
        
        return utxos;
    }
    
    /**
     * Find the last used address index in the batch
     * Uses HashMap for O(1) lookups when batch size is large enough to benefit
     */
    private int findLastUsedIndex(List<AddressBalance> batch, List<UTXO> utxos) {
        // Use HashMap for O(1) lookups when batch is large enough to benefit
        if (batch.size() > 20) {
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
        } else {
            // For small batches, linear search is faster due to cache locality
            int lastIndex = 0;
            for (UTXO utxo : utxos) {
                for (int i = 0; i < batch.size(); i++) {
                    if (batch.get(i).getAddress().toBase58().equals(utxo.getAddress())) {
                        lastIndex = Math.max(lastIndex, i);
                        break;
                    }
                }
            }
            return lastIndex;
        }
    }
}