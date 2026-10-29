package io.xlite.daemon.app.wallet;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.util.TxTimeField;
import io.xlite.daemon.app.util.UTXO;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The nTime resolution contract, and the fee the send path reports.
 *
 * <p>Two properties are load-bearing: {@link WalletHelper#resolveNTime} must
 * fail closed -- a time-field coin with no resolver, an out-of-range value or a
 * throwing resolver must abandon the transaction rather than sign a guess -- and
 * {@link WalletHelper#feeActuallyCharged} must report the fee the network
 * actually charges, including when change is calculated but dropped as dust and
 * for a 6-decimal coin where whole-coin rounding would erase the fee entirely.
 */
class WalletHelperNTimeTest {

    private static final long GOOD_NTIME = 1_790_592_305L;

    @BeforeAll
    static void loadRegistry() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        // XVG carries nTime; BLOCK does not. Both are needed to prove the
        // per-coin branch, not a hardcoded default.
        cfgs.put("XVG", fixture("XVG", "Verge", 30, 33, 158, 1_000_000L, 20102, "true"));
        cfgs.put("BLOCK", fixture("BLOCK", "Blocknet", 26, 28, 154, 100_000_000L, 41414, "false"));
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterAll
    static void resetRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig fixture(String ticker, String blockchain,
            int addrPrefix, int scriptPrefix, int secretPrefix,
            long coin, int port, String txWithTimeField) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("AddressPrefix", Integer.toString(addrPrefix));
        entries.put("ScriptPrefix", Integer.toString(scriptPrefix));
        entries.put("SecretPrefix", Integer.toString(secretPrefix));
        entries.put("COIN", Long.toString(coin));
        entries.put("FeePerByte", "200");
        entries.put("MinTxFee", "100000");
        entries.put("Port", Integer.toString(port));
        entries.put("DustAmount", "0");
        entries.put("Title", blockchain);
        entries.put("TxWithTimeField", txWithTimeField);
        return new CoinConfig(ticker, blockchain, ticker.toLowerCase() + "--test", entries);
    }

    private static ArrayList<UTXO> selected() {
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // Fail-closed resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a coin whose transactions carry nTime needs a resolver")
    void testTimeFieldCoinWithoutResolverFailsClosed() {
        assertEquals(WalletHelper.INVALID_NTIME,
                WalletHelper.resolveNTime(
                        io.xlite.daemon.app.net.CoinTicker.VERGE, selected(), null),
                "signing a time-field coin without an nTime would produce a "
                        + "signature over bytes the chain never computes");
    }

    @Test
    @DisplayName("a resolver that throws abandons the transaction")
    void testThrowingResolverFailsClosed() {
        assertEquals(WalletHelper.INVALID_NTIME,
                WalletHelper.resolveNTime(
                        io.xlite.daemon.app.net.CoinTicker.VERGE, selected(),
                                utxos -> {
                                    throw new RuntimeException("Cannot fetch parent abc for nTime");
                                }),
                "an unfetchable parent nTime must fail the send, not fall back "
                        + "to wall-clock time");
    }

    @Test
    @DisplayName("an out-of-range nTime is refused")
    void testOutOfRangeNTimeFailsClosed() {
        assertEquals(WalletHelper.INVALID_NTIME,
                WalletHelper.resolveNTime(io.xlite.daemon.app.net.CoinTicker.VERGE,
                        selected(), utxos -> 0x1_0000_0000L));
        assertEquals(WalletHelper.INVALID_NTIME,
                WalletHelper.resolveNTime(io.xlite.daemon.app.net.CoinTicker.VERGE,
                        selected(), utxos -> -5L));
    }

    @Test
    @DisplayName("a valid nTime passes through unchanged")
    void testValidNTimePassesThrough() {
        assertEquals(GOOD_NTIME,
                WalletHelper.resolveNTime(io.xlite.daemon.app.net.CoinTicker.VERGE,
                        selected(), utxos -> GOOD_NTIME));
    }

    @Test
    @DisplayName("a coin without nTime never consults a resolver")
    void testCoinWithoutTimeFieldSkipsResolution() {
        assertEquals(WalletHelper.NO_NTIME,
                WalletHelper.resolveNTime(io.xlite.daemon.app.net.CoinTicker.BLOCKNET,
                        selected(), null),
                "a standard coin has no nTime to resolve");
        assertEquals(WalletHelper.NO_NTIME,
                WalletHelper.resolveNTime(io.xlite.daemon.app.net.CoinTicker.BLOCKNET,
                        selected(), utxos -> {
                            throw new IllegalStateException("must not be called");
                        }),
                "the resolver must not be consulted for a standard coin");
    }

    // ------------------------------------------------------------------
    // The fee the send path reports
    // ------------------------------------------------------------------

    /**
     * A transaction spending {@code outputValues} as outputs, built on XVG's
     * own network parameters so the amounts are interpreted at 6 decimals.
     * Callers pair it with {@link #utxos} to pin {@link WalletHelper#feeActuallyCharged}.
     */
    private static Transaction txWithOutputs(long... outputValues) {
        NetworkParameters params = ConfigurableNetworkParameters
                .from(CoinConfigRegistry.get("XVG"));
        Transaction tx = new Transaction(params);
        for (long v : outputValues) {
            tx.addOutput(org.bitcoinj.core.Coin.valueOf(v),
                    org.bitcoinj.core.LegacyAddress.fromPubKeyHash(params, new byte[20]));
        }
        return tx;
    }

    private static ArrayList<UTXO> utxos(long... values) {
        ArrayList<UTXO> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(new UTXO(io.xlite.daemon.app.net.CoinTicker.VERGE, "addr", "txid", i, 100,
                    values[i]));
        }
        return list;
    }

    @Test
    @DisplayName("the reported fee is the inputs minus the outputs actually added")
    void testFeeIsInputsMinusActualOutputs() {
        // 123_457 + 234_569 in (358_026), 78_901 + 45_003 out (123_904), so
        // 234_122 leaves the wallet. A formula based on the input count would
        // say 100_000 (the XVG MinTxFee) and the dry run would report a fee
        // nobody was charged.
        //
        // Every amount here is deliberately not a multiple of 100_000 (0.1 XVG):
        // round values survive whole-coin rounding intact, so they cannot
        // distinguish an exact base-unit sum from a rounded one.
        Transaction tx = txWithOutputs(78_901L, 45_003L);
        assertEquals(234_122L,
                WalletHelper.feeActuallyCharged(utxos(123_457L, 234_569L), tx));
    }

    @Test
    @DisplayName("change dropped as dust is not counted as a fee")
    void testDroppedDustChangeIsNotChargedAsFee() {
        // The wallet computed change of 5_017 and dropped it below the dust
        // threshold, so only 452_983 left. The fee is 105_017, not 110_034:
        // the dropped value never became an output and must not be subtracted.
        Transaction tx = txWithOutputs(452_983L);
        assertEquals(105_017L, WalletHelper.feeActuallyCharged(utxos(234_569L, 323_431L), tx));
    }

    @Test
    @DisplayName("the fee is computed in base units, not whole coins")
    void testFeeIsExactForSixDecimalCoin() {
        // XVG has 6 decimals. Rounding each side to whole coins first would
        // truncate 1_234_567 to 1.0 and 987_654 to 1.0, reporting a 0 fee for
        // a difference that is 246_913 base units of real money.
        Transaction tx = txWithOutputs(987_654L);
        assertEquals(246_913L, WalletHelper.feeActuallyCharged(utxos(1_234_567L), tx));
    }

    @Test
    @DisplayName("the reported fee is a plain difference, with no minimum-fee floor")
    void testFeeIsNotFlooredAtMinTxFee() {
        // Outputs equal to the inputs means nothing was left for the miner.
        // The old size-based formula could not express this: it always
        // returned at least MinTxFee (100000 for XVG), so it reported a fee
        // the transaction did not actually pay. Pinning the zero here is what
        // distinguishes the difference formula from a floored one.
        Transaction tx = txWithOutputs(1_234_567L);
        assertEquals(0L, WalletHelper.feeActuallyCharged(utxos(1_234_567L), tx));
    }

    @Test
    @DisplayName("several inputs are summed before the outputs are subtracted")
    void testFeeSumsAllInputs() {
        // 312_345 + 234_567 + 411_223 in (958_135), 812_345 out, so 145_790
        // is charged. Summing only the first input would say -499_878.
        Transaction tx = txWithOutputs(812_345L);
        assertEquals(145_790L,
                WalletHelper.feeActuallyCharged(
                        utxos(312_345L, 234_567L, 411_223L), tx));
    }

    // ------------------------------------------------------------------
    // Per-coin flag plumbing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the per-coin TxWithTimeField flag decides the branch")
    void testTxWithTimeFieldFlagIsPerCoin() {
        assertEquals(true, TxTimeField.appliesTo(io.xlite.daemon.app.net.CoinTicker.VERGE));
        assertEquals(false, TxTimeField.appliesTo(io.xlite.daemon.app.net.CoinTicker.BLOCKNET));
    }

    @Test
    @DisplayName("the sentinels are distinguishable")
    void testSentinelsAreDistinct() {
        org.junit.jupiter.api.Assertions.assertNotEquals(WalletHelper.NO_NTIME,
                WalletHelper.INVALID_NTIME,
                "a coin without nTime and a failed resolution must not look alike");
    }
}
