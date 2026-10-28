package io.xlite.daemon.app.coinconfig;

import io.xlite.daemon.app.wallet.WalletHelper;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The config-driven parameter set must take every value the configuration
 * repository carries straight from the configuration file, and the remaining
 * values from the one compiled supplement — with no per-coin class in between
 * that could hold a third, drifted copy.
 *
 * <p>These cases assert against the config the test supplies, never against a
 * coin's real numbers. Configuration values change routinely — a fee retarget
 * is an ordinary edit — so a test that pinned them would fail every time the
 * configuration repository moved while proving nothing about the code. What
 * must hold regardless is the wiring.
 *
 * <p>The per-coin hardcoded parameter classes this replaced have been deleted,
 * so their migration fidelity can no longer be re-checked against a live
 * reference. That is stated here rather than papered over with a frozen copy
 * of numbers that would themselves drift out of date — which is why, despite
 * the historical name, this no longer compares against anything external.
 *
 * <p>Fields deliberately NOT asserted, each verified to be a dead code path in
 * this daemon (zero callers on migrated coins):</p>
 * <ul>
 *   <li>{@code getUriScheme()} — derived from the ticker</li>
 *   <li>{@code getInterval()}/{@code getSubsidyDecreaseBlockCount()} — inert
 *       stubs (no BlockChain is ever constructed in this daemon)</li>
 *   <li>{@code getProtocolVersionNum(non-CURRENT)} — the generic class returns
 *       its fixed value</li>
 *   <li>{@code getPort()} — see the disclosure on
 *       {@link ConfigurableNetworkParameters}</li>
 * </ul>
 */
class ConfigurableNetworkParametersCrossCheckTest {

    @Test
    void testEveryValueComesFromTheConfiguration() {
        // A real supplemented ticker, so this needs no test-only hook, with
        // values chosen by the test and claimed to mirror no coin.
        ConfigurableNetworkParameters p = ConfigurableNetworkParameters.from(
                config("LTC", 17, 18, 19, 1_000_000L, 777L, 88_800L, 34567));

        assertEquals(17, p.getAddressHeader(), "address header from config");
        assertEquals(18, p.getP2SHHeader(), "p2sh header from config");
        assertEquals(19, p.getDumpedPrivateKeyHeader(), "dumped key header from config");
        assertEquals(777L, WalletHelper.getFeePerByte(p), "fee rate from config");
        assertEquals(88_800L, WalletHelper.getMinTxFee(p), "minimum fee from config");
    }

    @Test
    void testEachConfigurationGetsItsOwnValues() {
        // Two coins, deliberately different. A class that cached, defaulted, or
        // fell back to a constant would give both the same answer.
        ConfigurableNetworkParameters a = ConfigurableNetworkParameters.from(
                config("LTC", 30, 63, 128, 1_000_000L, 11L, 22L, 1111));
        ConfigurableNetworkParameters b = ConfigurableNetworkParameters.from(
                config("DASH", 60, 122, 200, 100_000_000L, 33L, 44L, 2222));

        assertEquals(11L, WalletHelper.getFeePerByte(a));
        assertEquals(22L, WalletHelper.getMinTxFee(a));
        assertEquals(33L, WalletHelper.getFeePerByte(b));
        assertEquals(44L, WalletHelper.getMinTxFee(b));
        assertEquals(30, a.getAddressHeader());
        assertEquals(60, b.getAddressHeader());
        // The supply ceiling is deliberately not asserted here. It is derived
        // from the supplement's supply, not from the configuration, so it is
        // neither configuration data nor something a config-driven test should
        // claim to cover.
    }

    /**
     * The values the configuration repository does NOT carry come from the
     * compiled supplement instead. Those are in-repo constants, not
     * configuration, so they are asserted here: an edit to the supplement that
     * changed a coin's protocol version or network id has to be deliberate, not
     * a side effect. The dust floor is no longer here: it is configuration.
     */
    @Test
    void testSupplementDerivedValuesComeFromTheSupplement() {
        CoinConfig cfg = config("LTC", 17, 18, 19, 1_000_000L, 777L, 88_800L, 34567);
        CompiledCoinSupplement.Supplement s =
                CompiledCoinSupplement.forTicker(cfg.getTicker());
        ConfigurableNetworkParameters p = ConfigurableNetworkParameters.from(cfg);

        assertEquals(s.id, p.getId(), "network id from the supplement");
        assertEquals(s.chainName, p.getPaymentProtocolId(), "payment protocol id");
        // Wallet creation rejects the unit-test network id, so a supplement row
        // carrying it would let a coin start against a throwaway network.
        assertNotEquals(NetworkParameters.ID_UNITTESTNET, p.getId(),
                "a coin must not report the unit-test network id");
        assertEquals(s.protocolVersion,
                p.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
                "protocol version");
        assertNotNull(p.getSerializer(false), "serializer");
        // The supply ceiling is deliberately not asserted here: it is the one
        // supplement value that scales with the coin's configured divisor, and
        // whether it does so is covered where that behaviour is introduced.
    }

    /**
     * An address built from a coin's parameters must carry that coin's
     * configured version byte, not another network's.
     *
     * <p>This is the check the deleted per-coin comparison used to give, and it
     * is deliberately not a round-trip through the same object — that would pass
     * for any self-consistent prefix, including a wrong one. Decoding the
     * address back and comparing the version byte to the configuration catches
     * a class whose encoder used a different header, which is the failure that
     * sends a user's funds to an address of the wrong network.
     *
     * <p>The tickers are the coins whose configuration departs from what the
     * retired hardcoded classes carried, because those are the prefixes most
     * likely to be wrong. The prefix values themselves are arbitrary test
     * inputs, not claims about any live coin — which is why the expected
     * strings are recomputed here rather than copied from a real address.
     */
    @Test
    void testEncodedAddressUsesTheConfiguredPrefix() {
        // The check is on the encoded string, not on getAddressHeader() read
        // back out of the same object: reading the prefix back only proves the
        // getter returns what it was told, which the value wiring test above
        // already covers. What can actually go wrong is the encoder ignoring
        // the configured prefix, and only the encoded bytes show that.
        //
        // The three expected strings are base58check of version byte + hash160
        // + checksum, computed independently of this code. The prefixes are
        // arbitrary (not claimed to mirror any live coin) precisely so that a
        // change in someone's configuration cannot break this.
        byte[] hash = new byte[20];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (i * 7 + 3);
        }
        int[][] coins = {{30, 63, 128}, {60, 122, 128}, {48, 50, 176}};
        String[] tickers = {"DGB", "RVN", "LTC"};
        String[] encoded = {
                "D5RAdHXFoBo1zCUNnCMukoBMYzckSY7GKc",
                "R9ZGAYTu6bhJXCeyWnMUJZLxS8M3kk52iF",
                "LKW2MEtSaS8nhzywDkMeV45Wt5FjFizyaC",
        };
        for (int i = 0; i < tickers.length; i++) {
            ConfigurableNetworkParameters p = ConfigurableNetworkParameters.from(config(
                    tickers[i], coins[i][0], coins[i][1], coins[i][2], 100_000_000L, 10L, 5_000L, 9332));
            assertEquals(encoded[i],
                    LegacyAddress.fromPubKeyHash(p, hash).toString(),
                    tickers[i] + " must encode with its configured prefix " + coins[i][0]);
        }
    }

    /**
     * The dust floor comes from the configuration, not from a constant compiled
     * per coin.
     *
     * <p>This is a money path: a change output below the floor is dropped and its
     * value is charged as fee instead of being returned to the sender. A stale
     * per-coin constant is therefore not a cosmetic defect — Litecoin carried
     * 100000 satoshis, eighteen times the fallback used for an unspecified
     * value, so a 0.0001 change was charged as fee rather than returned.
     */
    @Test
    void testDustFloorComesFromTheConfiguration() {
        // This is a money path: a change output below the floor is dropped and
        // its value is charged as fee rather than returned to the sender. A
        // per-coin constant here is therefore not cosmetic, and the compiler
        // must invent nothing when the configuration is silent.
        CoinConfig zero = config("LTC", 30, 63, 128, 100_000_000L, 10L, 5_000L, 9332);
        assertEquals(0L,
                ConfigurableNetworkParameters.from(zero).getMinNonDustOutput().getValue(),
                "a zero dust floor means no floor, not an invented one");

        // Genuinely absent, as opposed to zero: the key is not in the map at all.
        // A missing required key must fail loudly rather than silently become a
        // zero floor.
        Map<String, String> withoutKey = new LinkedHashMap<>(zero.getConfEntries());
        withoutKey.remove("DustAmount");
        CoinConfig absent = new CoinConfig(zero.getTicker(), zero.getBlockchain(),
                zero.getVerId(), withoutKey);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> ConfigurableNetworkParameters.from(absent),
                "an absent DustAmount must be rejected, not read as no floor");
        assertTrue(missing.getMessage().contains("DustAmount"),
                "the failure must name the key, got: " + missing.getMessage());

        // A negative floor is a money bug, not a cosmetic one: every change
        // output would sit "above" it, so nothing would be trimmed and the
        // change would be paid to the miner as fee instead of returned.
        Map<String, String> negative = new LinkedHashMap<>(zero.getConfEntries());
        negative.put("DustAmount", "-1");
        CoinConfig bad = new CoinConfig(zero.getTicker(), zero.getBlockchain(),
                zero.getVerId(), negative);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigurableNetworkParameters.from(bad),
                "a negative dust floor must be rejected, not silently disable trimming");
        // Asserting on "negative" rather than just the key name pins where the
        // rejection comes from: the gate's rule, checked in the constructor
        // before the value is read. The other DustAmount failure this code can
        // produce is "too large to represent", which is a different condition
        // and would not match.
        assertTrue(e.getMessage().contains("negative"),
                "the failure must come from the negative-dust rule, got: " + e.getMessage());

        assertEquals(1_234L, ConfigurableNetworkParameters.from(
                        configWithDust("LTC", 1_234L)).getMinNonDustOutput().getValue(),
                "a configured dust floor is used verbatim");
        assertEquals(0L, ConfigurableNetworkParameters.from(
                        configWithDust("LTC", 0L)).getMinNonDustOutput().getValue(),
                "zero is honoured as stated");
    }

    @Test
    @DisplayName("every required key is required: a missing one fails, naming itself")
    void testEachRequiredKeyIsRequired() {
        // These are the keys a coin cannot start without. A coin that falls back
        // to a default here would be priced from a value nobody chose, so each
        // one has to be a hard failure that says which key was absent.
        String[] required = {"AddressPrefix", "ScriptPrefix", "SecretPrefix",
                "COIN", "FeePerByte", "MinTxFee", "Port"};
        for (String key : required) {
            CoinConfig good = config("LTC", 30, 63, 128, 1_000_000L, 777L, 88_800L, 34567);
            Map<String, String> missing = new LinkedHashMap<>(good.getConfEntries());
            assertTrue(missing.remove(key) != null, "fixture must actually carry " + key);
            CoinConfig bad = new CoinConfig(good.getTicker(), good.getBlockchain(),
                    good.getVerId(), missing);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ConfigurableNetworkParameters.from(bad),
                    "a config missing " + key + " must be rejected, not defaulted");
            assertTrue(e.getMessage().contains(key),
                    "the failure must name the missing key, got: " + e.getMessage());
        }
    }

    @Test
    void testGateRejectsPrefixOutOfByteRange() {
        CoinConfig good = config("LTC", 30, 63, 128, 1_000_000L, 777L, 88_800L, 34567);
        Map<String, String> broken = new LinkedHashMap<>(good.getConfEntries());
        broken.put("AddressPrefix", "9999");
        CoinConfig bad = new CoinConfig(good.getTicker(), good.getBlockchain(),
                good.getVerId(), broken);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigurableNetworkParameters.from(bad));
        assertTrue(e.getMessage().contains("coin config rejected"), e.getMessage());
        assertTrue(e.getMessage().contains("out of byte range"), e.getMessage());
    }

    @Test
    void testGateRejectsPrefixesThatWouldCollide() {
        // Distinct prefixes are what stop an address from one network parsing
        // on another, so a collision is a configuration error to reject, not a
        // value to default away.
        CoinConfig good = config("LTC", 30, 63, 128, 1_000_000L, 777L, 88_800L, 34567);
        Map<String, String> broken = new LinkedHashMap<>(good.getConfEntries());
        broken.put("ScriptPrefix", broken.get("AddressPrefix"));
        CoinConfig bad = new CoinConfig(good.getTicker(), good.getBlockchain(),
                good.getVerId(), broken);
        assertThrows(IllegalStateException.class,
                () -> ConfigurableNetworkParameters.from(bad));
    }

    /** The same synthetic configuration, with an explicit dust floor. */
    private static CoinConfig configWithDust(String ticker, long dust) {
        CoinConfig base = config(ticker, 30, 63, 128, 100_000_000L, 10L, 5_000L, 9332);
        Map<String, String> e = new LinkedHashMap<>(base.getConfEntries());
        e.put("DustAmount", Long.toString(dust));
        return new CoinConfig(ticker, ticker, ticker.toLowerCase() + "--test", e);
    }

    /** A synthetic configuration: every value chosen by the test, none claimed
     *  to mirror any real coin. */
    private static CoinConfig config(String ticker, int addr, int p2sh, int dpk,
                                     long coin, long feePerByte, long minTxFee, int port) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(addr));
        e.put("ScriptPrefix", Integer.toString(p2sh));
        e.put("SecretPrefix", Integer.toString(dpk));
        e.put("COIN", Long.toString(coin));
        e.put("FeePerByte", Long.toString(feePerByte));
        e.put("MinTxFee", Long.toString(minTxFee));
        e.put("Port", Integer.toString(port));
        e.put("DustAmount", "0");
        return new CoinConfig(ticker, ticker, ticker.toLowerCase() + "--test", e);
    }
}
