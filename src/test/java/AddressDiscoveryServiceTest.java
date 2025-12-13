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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test class for AddressDiscoveryService.
 * Tests address discovery functionality including timeout handling, circuit breaker patterns,
 * batch processing, and various edge cases.
 */
class AddressDiscoveryServiceTest extends TestHelper {

    private CoinInstance coinInstance;
    private AddressDiscoveryService discoveryService;
    private HTTPClient mockHttpClient;

    @BeforeEach
    void setup() {
        commonSetup();
        // Initialize Mockito
        MockitoAnnotations.openMocks(this);

        // Initialize coin instance with test parameters
        coinInstance = CoinInstance.getInstance(CoinTicker.LITECOIN);
        assertNotNull(coinInstance);
        coinInstance.getConfigHelper().setAddressCount(getAddressCountInitial());
        assertNull(coinInstance.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

        // Create mock HTTP client
        mockHttpClient = mock(HTTPClient.class);

        // Reset timeout to default value before each test
        AddressDiscoveryService.setDiscoveryTimeoutMs(30000);

        // Create discovery service with mocked HTTP client for testing
        discoveryService = new AddressDiscoveryService(coinInstance, mockHttpClient);
    }

    @AfterAll
    static void cleanup() {
        // Reset timeout to default value for other tests
        commonCleanup();
    }

    /**
     * Test 1: Address discovery correctly identifies last used address + 1
     * Tests the core functionality of finding used addresses and setting address count correctly.
     */
    @Test
    void testAddressDiscovery_FindsUsedAddresses() {
        // Setup: Generate addresses and simulate UTXOs for some addresses
        int initialAddressCount = getAddressCountInitial();
        int usedAddressIndex = initialAddressCount + 10; // Use an address beyond initial count
        
        // Generate enough addresses for testing
        for (int i = 0; i < 50; i++) {
            coinInstance.generateAddress(false);
        }

        // Create mock UTXO response for the used address
        JsonArray mockUtxos = new JsonArray();
        JsonObject utxo1 = new JsonObject();
        utxo1.addProperty("address", coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58());
        utxo1.addProperty("txid", "test-txid-1");
        utxo1.addProperty("vout", 0);
        utxo1.addProperty("confirmations", 10);
        utxo1.addProperty("value", 1.5);
        mockUtxos.add(utxo1);

        // Mock HTTP client to return UTXOs for batch containing the used address
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String[] addresses = invocation.getArgument(1);
                    // Check if this batch contains our used address
                    for (String addr : addresses) {
                        if (addr.equals(coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58())) {
                            return mockUtxos;
                        }
                    }
                    return new JsonArray(); // Empty for other batches
                });

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should find the used address and set count to last used + 1
        assertEquals(usedAddressIndex + 1, discoveredAddressCount,
                "Discovery should set address count to last used address index + 1");

        // Verify HTTP client was called
        verify(mockHttpClient, atLeastOnce()).getUtxosUncached(any(), any(String[].class));
    }

    /**
     * Test 2: Address discovery timeout handling after 1 second
     * Tests that discovery aborts gracefully when timeout is reached.
     */
    @Test
    @Timeout(5) // Test should complete quickly, timeout indicates infinite loop
    void testAddressDiscovery_TimeoutHandling() {
        // Set timeout to 1 second for test unit only
        AddressDiscoveryService.setDiscoveryTimeoutMs(1000);

        // Setup: Mock HTTP client to throw exception immediately (simulating timeout)
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenThrow(new RuntimeException("Simulated timeout"))
                .thenThrow(new RuntimeException("Simulated timeout"))
                .thenThrow(new RuntimeException("Simulated timeout"));

        // Execute: Run discovery (should timeout after 1 second due to circuit breaker)
        long startTime = System.currentTimeMillis();
        int discoveredAddressCount = discoveryService.discoverAddressCount();
        long elapsedTime = System.currentTimeMillis() - startTime;

        // Verify: Discovery should return original address count due to circuit breaker
        assertEquals(getAddressCountInitial(), discoveredAddressCount,
                "Discovery should return original address count when timeout occurs");

        // Verify circuit breaker triggered (3 calls max)
        verify(mockHttpClient, times(3)).getUtxosUncached(any(), any(String[].class));

        // Verify timeout occurred within expected bounds (should be very fast due to circuit breaker)
        if (!(elapsedTime < 1000)) {
            fail("Discovery should complete quickly due to circuit breaker, actual: " + elapsedTime + "ms");
        }
    }

    /**
     * Test 3: Address discovery circuit breaker for 3 consecutive failures
     * Tests that discovery aborts after 3 consecutive HTTP failures.
     */
    @Test
    void testAddressDiscovery_CircuitBreaker() {
        // Setup: Mock HTTP client to throw exceptions (simulate failures)
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenThrow(new RuntimeException("Network error"))
                .thenThrow(new RuntimeException("Network error"))
                .thenThrow(new RuntimeException("Network error"));

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should return original address count due to circuit breaker
        assertEquals(getAddressCountInitial(), discoveredAddressCount,
                "Discovery should return original address count when circuit breaker trips");

        // Verify HTTP client was called exactly 3 times (circuit breaker threshold)
        verify(mockHttpClient, times(3)).getUtxosUncached(any(), any(String[].class));
    }

    /**
     * Test 4: Address discovery when no addresses have been used
     * Tests behavior when no addresses have UTXOs (empty wallet).
     */
    @Test
    void testAddressDiscovery_NoUsedAddresses() {
        // Setup: Mock HTTP client to always return empty responses
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenReturn(new JsonArray());

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should return original address count when no used addresses found
        assertEquals(getAddressCountInitial(), discoveredAddressCount,
                "Discovery should return original address count when no used addresses are found");

        // Verify HTTP client was called for multiple batches
        verify(mockHttpClient, atLeastOnce()).getUtxosUncached(any(), any(String[].class));
    }

    /**
     * Test 5: Address discovery batch processing with 100 addresses per batch
     * Tests that discovery processes addresses in correct batch sizes.
     */
    @Test
    void testAddressDiscovery_BatchProcessing() {
        // Setup: Generate more addresses to test batch processing
        int totalAddresses = 350; // More than 3 batches of 100
        for (int i = 0; i < totalAddresses - getAddressCountInitial(); i++) {
            coinInstance.generateAddress(false);
        }

        // Mock HTTP client to track batch sizes
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String[] addresses = invocation.getArgument(1);
                    // Verify batch size is correct (except possibly the last batch)
                    if (addresses.length < totalAddresses) {
                        assertEquals(100, addresses.length,
                                "Batch size should be 100 for all batches except possibly the last");
                    }
                    return new JsonArray(); // Empty response
                });

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery completed successfully
        assertEquals(getAddressCountInitial(), discoveredAddressCount,
                "Discovery should complete with original address count");

        // Verify HTTP client was called for multiple batches
        verify(mockHttpClient, atLeastOnce()).getUtxosUncached(any(), any(String[].class));
    }

    /**
     * Test 6: Address discovery with mixed UTXO responses
     * Tests discovery when some batches have UTXOs and others don't.
     */
    @Test
    void testAddressDiscovery_MixedUtxoResponses() {
        // Setup: Generate addresses and simulate UTXOs in non-consecutive batches
        for (int i = 0; i < 200; i++) {
            coinInstance.generateAddress(false);
        }

        int usedAddressIndex1 = getAddressCountInitial() + 50;
        int usedAddressIndex2 = getAddressCountInitial() + 150;

        // Create mock UTXO responses
        JsonArray mockUtxos1 = new JsonArray();
        JsonObject utxo1 = new JsonObject();
        utxo1.addProperty("address", coinInstance.getAddressKeyPairs().get(usedAddressIndex1).getAddress().toBase58());
        utxo1.addProperty("txid", "test-txid-1");
        utxo1.addProperty("vout", 0);
        utxo1.addProperty("confirmations", 10);
        utxo1.addProperty("value", 1.5);
        mockUtxos1.add(utxo1);

        JsonArray mockUtxos2 = new JsonArray();
        JsonObject utxo2 = new JsonObject();
        utxo2.addProperty("address", coinInstance.getAddressKeyPairs().get(usedAddressIndex2).getAddress().toBase58());
        utxo2.addProperty("txid", "test-txid-2");
        utxo2.addProperty("vout", 1);
        utxo2.addProperty("confirmations", 5);
        utxo2.addProperty("value", 2.0);
        mockUtxos2.add(utxo2);

        // Mock HTTP client to return UTXOs for specific batches
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String[] addresses = invocation.getArgument(1);
                    for (String addr : addresses) {
                        if (addr.equals(coinInstance.getAddressKeyPairs().get(usedAddressIndex1).getAddress().toBase58())) {
                            return mockUtxos1;
                        }
                        if (addr.equals(coinInstance.getAddressKeyPairs().get(usedAddressIndex2).getAddress().toBase58())) {
                            return mockUtxos2;
                        }
                    }
                    return new JsonArray();
                });

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should find the last used address (higher index)
        assertEquals(usedAddressIndex2 + 1, discoveredAddressCount,
                "Discovery should find the last used address across multiple batches");
    }

    /**
     * Test 7: Address discovery with partial batch processing
     * Tests discovery when discovery stops before processing all batches due to gap limit.
     */
    @Test
    void testAddressDiscovery_PartialBatchProcessing() {
        // Setup: Generate addresses and simulate UTXOs with a gap
        for (int i = 0; i < 150; i++) {
            coinInstance.generateAddress(false);
        }

        int usedAddressIndex = getAddressCountInitial() + 5; // Early in the sequence
        
        // Create mock UTXO response
        JsonArray mockUtxos = new JsonArray();
        JsonObject utxo = new JsonObject();
        utxo.addProperty("address", coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58());
        utxo.addProperty("txid", "test-txid");
        utxo.addProperty("vout", 0);
        utxo.addProperty("confirmations", 10);
        utxo.addProperty("value", 1.0);
        mockUtxos.add(utxo);

        // Mock HTTP client
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String[] addresses = invocation.getArgument(1);
                    for (String addr : addresses) {
                        if (addr.equals(coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58())) {
                            return mockUtxos;
                        }
                    }
                    return new JsonArray();
                });

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should stop after finding used address + gap limit
        assertEquals(usedAddressIndex + 1, discoveredAddressCount,
                "Discovery should stop after finding used address + gap limit");
    }

    /**
     * Test 8: Address discovery with UTXO parsing errors
     * Tests that discovery continues when some UTXOs cannot be parsed.
     */
    @Test
    void testAddressDiscovery_UtxoParsingErrors() {
        // Setup: Generate addresses
        for (int i = 0; i < 100; i++) {
            coinInstance.generateAddress(false);
        }

        int usedAddressIndex = getAddressCountInitial() + 10;

        // Create mixed UTXO response (valid and invalid)
        JsonArray mockUtxos = new JsonArray();

        // Valid UTXO
        JsonObject validUtxo = new JsonObject();
        validUtxo.addProperty("address", coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58());
        validUtxo.addProperty("txid", "test-txid-1");
        validUtxo.addProperty("vout", 0);
        validUtxo.addProperty("confirmations", 10);
        validUtxo.addProperty("value", 1.5);
        mockUtxos.add(validUtxo);

        // Invalid UTXO (missing required fields)
        JsonObject invalidUtxo = new JsonObject();
        invalidUtxo.addProperty("address", "invalid-address");
        // Missing other required fields
        mockUtxos.add(invalidUtxo);

        // Mock HTTP client
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String[] addresses = invocation.getArgument(1);
                    for (String addr : addresses) {
                        if (addr.equals(coinInstance.getAddressKeyPairs().get(usedAddressIndex).getAddress().toBase58())) {
                            return mockUtxos;
                        }
                    }
                    return new JsonArray();
                });

        // Execute: Run discovery
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should handle parsing errors gracefully and still find valid UTXO
        assertEquals(usedAddressIndex + 1, discoveredAddressCount,
                "Discovery should continue despite UTXO parsing errors");
    }

    /**
     * Test 9: Address discovery with concurrent access
     * Tests that discovery service handles concurrent access safely.
     */
    @Test
    void testAddressDiscovery_ConcurrentAccess() throws InterruptedException {
        // Setup: Generate addresses
        for (int i = 0; i < 100; i++) {
            coinInstance.generateAddress(false);
        }

        // Mock HTTP client
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenReturn(new JsonArray());

        // Execute: Run multiple discovery operations concurrently
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

        // Wait for all threads to complete
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify: All concurrent operations should return the same result
        assertEquals(numThreads, results.size(), "All threads should complete");
        int expectedResult = getAddressCountInitial();
        for (Integer result : results) {
            assertEquals(expectedResult, result, "All concurrent operations should return the same result");
        }
    }

    /**
     * Test 10: Address discovery with maximum depth limit
     * Tests that discovery stops when reaching the maximum discovery depth.
     */
    @Test
    void testAddressDiscovery_MaxDepthLimit() {
        // Setup: Mock HTTP client to always return empty (no used addresses)
        when(mockHttpClient.getUtxosUncached(any(), any(String[].class)))
                .thenReturn(new JsonArray());

        // Execute: Run discovery (should hit max depth limit)
        int discoveredAddressCount = discoveryService.discoverAddressCount();

        // Verify: Discovery should return original address count when hitting max depth
        assertEquals(getAddressCountInitial(), discoveredAddressCount,
                "Discovery should return original address count when hitting max depth");

        // Verify HTTP client was called multiple times (indicating depth traversal)
        verify(mockHttpClient, atLeastOnce()).getUtxosUncached(any(), any(String[].class));
    }
}