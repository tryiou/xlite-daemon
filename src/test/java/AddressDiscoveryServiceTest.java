import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.cloudchains.app.crypto.LoginUtils;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.util.AddressDiscoveryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for AddressDiscoveryService sequential batch scan.
 *
 * Algorithm: scan batches 0, 1, 2, ... Stop at first empty batch.
 * 3 consecutive HTTP failures → abort. No gap limit.
 */
class AddressDiscoveryServiceTest extends TestHelper {

    private CoinInstance coinInstance;
    private AddressDiscoveryService discoveryService;
    private HTTPClient mockHttpClient;

    @BeforeEach
    void setup() {
        commonSetup();
        MockitoAnnotations.openMocks(this);

        coinInstance = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coinInstance);
        coinInstance.getConfigHelper().setAddressCount(getAddressCountInitial());
        assertNull(coinInstance.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

        mockHttpClient = mock(HTTPClient.class);
        AddressDiscoveryService.setDiscoveryTimeoutMs(30000);
        discoveryService = new AddressDiscoveryService(coinInstance, mockHttpClient);
    }

    @AfterAll
    static void cleanup() {
        commonCleanup();
    }

    private JsonArray buildUtxoResponse(String address) {
        JsonArray result = new JsonArray();
        JsonObject utxo = new JsonObject();
        utxo.addProperty("address", address);
        utxo.addProperty("txid", "test-txid");
        utxo.addProperty("vout", 0);
        utxo.addProperty("confirmations", 10);
        utxo.addProperty("value", 1.5);
        result.add(utxo);
        return result;
    }

    /** Batch 0 has UTXOs at index 30, batch 1 empty. Returns 31. */
    @Test
    void testDiscovery_FindsUsedAddresses() {
        int usedAddressIndex = getAddressCountInitial() + 10;
        for (int i = 0; i < 50; i++) coinInstance.generateAddress(false);

        String usedAddress = coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58();

        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    String[] addrs = inv.getArgument(1);
                    for (String a : addrs)
                        if (a.equals(usedAddress)) return buildUtxoResponse(usedAddress);
                    return new JsonArray();
                });

        assertEquals(usedAddressIndex + 1, discoveryService.discoverAddressCount());
        verify(mockHttpClient, times(2)).getUtxosUncached(any(), any(String[].class));
    }

    /** Batch 0 empty → wallet unused. 1 HTTP call. */
    @Test
    void testDiscovery_EmptyWallet() {
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenReturn(new JsonArray());

        assertEquals(getAddressCountInitial(), discoveryService.discoverAddressCount());
        verify(mockHttpClient, times(1)).getUtxosUncached(any(), any(String[].class));
    }

    /** 3 consecutive HTTP failures → abort. 3 HTTP calls. */
    @Test
    @Timeout(30)
    void testDiscovery_ThreeConsecutiveErrorsAbort() {
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenThrow(new RuntimeException("Network error"));

        assertEquals(getAddressCountInitial(), discoveryService.discoverAddressCount());
        verify(mockHttpClient, times(3)).getUtxosUncached(any(), any(String[].class));
    }

    /** Batch 0 fails (1 error), batch 1 has UTXOs, batch 2 empty. Error counter resets on success. */
    @Test
    void testDiscovery_SkipsFailedBatch() {
        int batchSize = AddressDiscoveryService.getBatchSize();
        for (int i = 0; i < 2 * batchSize; i++) coinInstance.generateAddress(false);

        int usedAddressIndex = getAddressCountInitial() + batchSize + batchSize / 2; // batch 1
        String usedAddress = coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58();

        AtomicInteger callCount = new AtomicInteger(0);
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) throw new RuntimeException("Transient error");
                    String[] addrs = inv.getArgument(1);
                    for (String a : addrs)
                        if (a.equals(usedAddress)) return buildUtxoResponse(usedAddress);
                    return new JsonArray();
                });

        assertEquals(usedAddressIndex + 1, discoveryService.discoverAddressCount());
        verify(mockHttpClient, times(3)).getUtxosUncached(any(), any(String[].class));
    }

    /** Valid UTXO plus invalid entry. Should still find the address. */
    @Test
    void testDiscovery_UtxoParsingErrors() {
        for (int i = 0; i < 100; i++) coinInstance.generateAddress(false);

        int usedAddressIndex = getAddressCountInitial() + 10;
        String usedAddress = coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58();

        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    String[] addrs = inv.getArgument(1);
                    for (String a : addrs) {
                        if (a.equals(usedAddress)) {
                            JsonArray result = new JsonArray();
                            result.add(buildUtxoResponse(usedAddress).get(0));
                            JsonObject invalid = new JsonObject();
                            invalid.addProperty("address", "invalid");
                            result.add(invalid);
                            return result;
                        }
                    }
                    return new JsonArray();
                });

        assertEquals(usedAddressIndex + 1, discoveryService.discoverAddressCount());
    }

    /** All NUM_BATCHES have UTXOs. Scans all batches. */
    @Test
    void testDiscovery_AllBatchesFunded() {
        int batchSize = AddressDiscoveryService.getBatchSize();
        int numBatches = AddressDiscoveryService.getNumBatches();
        for (int i = 0; i < numBatches * batchSize; i++) coinInstance.generateAddress(false);

        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(inv -> buildUtxoResponse(((String[]) inv.getArgument(1))[0]));

        assertEquals((numBatches - 1) * batchSize + 1, discoveryService.discoverAddressCount());
        verify(mockHttpClient, times(numBatches)).getUtxosUncached(any(), any(String[].class));
    }

    /** Concurrent access — all threads return same result. */
    @Test
    void testDiscovery_ConcurrentAccess() throws InterruptedException {
        for (int i = 0; i < 100; i++) coinInstance.generateAddress(false);

        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenReturn(new JsonArray());

        int numThreads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        List<Integer> results = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    int result = discoveryService.discoverAddressCount();
                    synchronized (results) {
                        results.add(result);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(numThreads, results.size());
        for (Integer result : results)
            assertEquals(getAddressCountInitial(), result);
    }

    /** Batch probes use BATCH_SIZE addresses. */
    @Test
    void testDiscovery_BatchSizeRespected() {
        int batchSize = AddressDiscoveryService.getBatchSize();
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    assertEquals(batchSize, ((String[]) inv.getArgument(1)).length);
                    return new JsonArray();
                });

        discoveryService.discoverAddressCount();
    }
}
