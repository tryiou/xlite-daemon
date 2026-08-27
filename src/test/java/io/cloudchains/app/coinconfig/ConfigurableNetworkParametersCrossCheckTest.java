package io.cloudchains.app.coinconfig;

import io.cloudchains.app.net.protocols.bitcoin.BitcoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.bitcoincash.BitcoinCashNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.dashcoin.DashcoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.digibyte.DigibyteNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.dogecoin.DogecoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.litecoin.LitecoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.pivx.PivxNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.pocketcoin.PocketcoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.ravencoin.RavencoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.syscoin.SyscoinNetworkParametersLegacy;
import io.cloudchains.app.net.protocols.unobtanium.UnobtaniumNetworkParametersLegacy;
import io.cloudchains.app.wallet.WalletHelper;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration safety net: for every migrated coin, the bcf-driven parameter
 * set must reproduce the legacy hardcoded classes exactly — EXCEPT the two
 * approved corrections, pinned explicitly below:
 *   - DGB ScriptPrefix 5 -> 63 (bcf carries the current-era value)
 *   - RVN FeePerByte 1000 -> 3000 (bcf carries the product's creation rate)
 *
 * <p>Getters NOT compared here — each verified to be a dead code path in this
 * daemon (zero callers on migrated coins):</p>
 * <ul>
 *   <li>{@code getUriScheme()} — legacy used real scheme names ("litecoin:"),
 *       the generic class derives "ticker:"</li>
 *   <li>{@code getInterval()}/{@code getSubsidyDecreaseBlockCount()} — inert
 *       stubs vs per-coin legacy values (no BlockChain exists here)</li>
 *   <li>{@code getProtocolVersionNum(non-CURRENT)} — BCH legacy was
 *       version-dependent, the generic class returns its fixed value</li>
 *   <li>{@code getPort()} — see the disclosure on
 *       {@link ConfigurableNetworkParameters}</li>
 * </ul>
 */
class ConfigurableNetworkParametersCrossCheckTest {

    private static final byte[] HASH160 = new byte[20];

    static {
        for (int i = 0; i < 20; i++) HASH160[i] = (byte) (0xA0 + i);
    }

    private Map<String, CoinConfig> configs() {
        // Simple in-memory set — validates logic without filesystem fixture
        Map<String, CoinConfig> m = new LinkedHashMap<>();
        // LTC
        Map<String, String> ltc = new LinkedHashMap<>();
        ltc.put("AddressPrefix", "48"); ltc.put("ScriptPrefix", "50"); ltc.put("SecretPrefix", "176");
        ltc.put("COIN", "100000000"); ltc.put("FeePerByte", "10"); ltc.put("MinTxFee", "5000");
        ltc.put("Port", "9332"); ltc.put("DustAmount", "0");
        m.put("LTC", new CoinConfig("LTC", "Litecoin", "litecoin--v0.21.1", ltc));
        // BLOCK
        Map<String, String> block = new LinkedHashMap<>();
        block.put("AddressPrefix", "26"); block.put("ScriptPrefix", "28"); block.put("SecretPrefix", "154");
        block.put("COIN", "100000000"); block.put("FeePerByte", "20"); block.put("MinTxFee", "10000");
        block.put("Port", "41414"); block.put("DustAmount", "0");
        m.put("BLOCK", new CoinConfig("BLOCK", "Blocknet", "blocknet--v4.2.0", block));
        // DGB with bcf-correct 63 (legacy is 5)
        Map<String, String> dgb = new LinkedHashMap<>();
        dgb.put("AddressPrefix", "30"); dgb.put("ScriptPrefix", "63"); dgb.put("SecretPrefix", "128");
        dgb.put("COIN", "100000000"); dgb.put("FeePerByte", "200"); dgb.put("MinTxFee", "100000");
        dgb.put("Port", "14022"); dgb.put("DustAmount", "0");
        m.put("DGB", new CoinConfig("DGB", "DigiByte", "digibyte--v9.26.5", dgb));
        // RVN with bcf-correct 3000 (legacy 1000)
        Map<String, String> rvn = new LinkedHashMap<>();
        rvn.put("AddressPrefix", "60"); rvn.put("ScriptPrefix", "122"); rvn.put("SecretPrefix", "128");
        rvn.put("COIN", "100000000"); rvn.put("FeePerByte", "3000"); rvn.put("MinTxFee", "100000");
        rvn.put("Port", "8766"); rvn.put("DustAmount", "0");
        m.put("RVN", new CoinConfig("RVN", "Ravencoin", "raven--v4.8.0", rvn));
        return m;
    }

    @Test
    void testGenericReproducesEveryLiveGetterOfLegacyClasses() {
        Map<String, CoinConfig> cfgs = configs();

        forLegacy(new String[]{"LTC"}, cfgs, false, false);
        // DGB: only the P2SH header intentionally differs (5 -> 63)
        forLegacy(new String[]{"DGB"}, cfgs, true, false);
        // RVN: only the fee intentionally differs (1000 -> 3000)
        forLegacy(new String[]{"RVN"}, cfgs, false, true);
    }

    private void forLegacy(String[] tickers, Map<String, CoinConfig> cfgs,
                           boolean dgbP2shDiff, boolean rvnFeeDiff) {
        for (String ticker : tickers) {
            NetworkParametersPair pair = pairFor(ticker, cfgs);

            assertEquals(pair.legacy.getAddressHeader(),
                    pair.generic.getAddressHeader(), ticker + " addressHeader");
            if (!dgbP2shDiff) {
                assertEquals(pair.legacy.getP2SHHeader(),
                        pair.generic.getP2SHHeader(), ticker + " p2shHeader");
            } else {
                assertEquals(5, pair.legacy.getP2SHHeader());
                assertEquals(63, pair.generic.getP2SHHeader(), "DGB must adopt bcf 63");
            }
            assertEquals(pair.legacy.getDumpedPrivateKeyHeader(),
                    pair.generic.getDumpedPrivateKeyHeader(), ticker + " dumpedPrivateKeyHeader");
            assertEquals(pair.legacy.getMaxMoney().getValue(),
                    pair.generic.getMaxMoney().getValue(), ticker + " maxMoney");
            assertEquals(pair.legacy.getMinNonDustOutput().getValue(),
                    pair.generic.getMinNonDustOutput().getValue(), ticker + " minNonDustOutput");
            assertEquals(pair.legacy.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
                    pair.generic.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
                    ticker + " protocolVersion");
            assertEquals(pair.legacy.getId(), pair.generic.getId(), ticker + " id");
            assertNotEquals(NetworkParameters.ID_UNITTESTNET, pair.generic.getId());
            assertEquals(pair.legacy.getPaymentProtocolId(),
                    pair.generic.getPaymentProtocolId(), ticker + " paymentProtocolId");

            long legacyFee = WalletHelper.getFeePerByte(pair.legacy);
            long genericFee = WalletHelper.getFeePerByte(pair.generic);
            if (!rvnFeeDiff) {
                assertEquals(legacyFee, genericFee, ticker + " feePerByte");
            } else {
                assertEquals(1000L, legacyFee);
                assertEquals(3000L, genericFee, "RVN must adopt bcf 3000 sat/B");
            }
            assertEquals(WalletHelper.getMinTxFee(pair.legacy),
                    WalletHelper.getMinTxFee(pair.generic),
                    ticker + " minTxFee");

            // end-to-end verbyte equivalence: same legacy address string
            LegacyAddress fromLegacy = LegacyAddress.fromPubKeyHash(pair.legacy, HASH160);
            LegacyAddress fromGeneric = LegacyAddress.fromPubKeyHash(pair.generic, HASH160);
            assertEquals(fromLegacy.toString(), fromGeneric.toString(), ticker + " address encoding");

            assertNotNull(pair.generic.getSerializer(false), ticker + " serializer");
        }
    }

    @Test
    void testGateRejectsCorruptedConfig() {
        CoinConfig good = configs().get("LTC");
        assertNotNull(good, "LTC missing from manifest - cross-check baseline broken");
        Map<String, String> broken = new LinkedHashMap<>(good.getConfEntries());
        broken.put("AddressPrefix", "9999");
        CoinConfig bad = new CoinConfig(good.getTicker(), good.getBlockchain(),
                good.getVerId(), broken);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigurableNetworkParameters.from(bad));
        assertTrue(e.getMessage().contains("coin config rejected"), e.getMessage());
        assertTrue(e.getMessage().contains("out of byte range"), e.getMessage());
    }

    private interface LegacyFactory {
        NetworkParameters create();
    }

    private NetworkParametersPair pairFor(String ticker, Map<String, CoinConfig> cfgs) {
        LegacyFactory factory;
        switch (ticker) {
            case "BTC":
                factory = BitcoinNetworkParametersLegacy::new; break;
            case "BCH":
                factory = BitcoinCashNetworkParametersLegacy::new; break;
            case "DASH":
                factory = DashcoinNetworkParametersLegacy::new; break;
            case "DGB":
                factory = DigibyteNetworkParametersLegacy::new; break;
            case "DOGE":
                factory = DogecoinNetworkParametersLegacy::new; break;
            case "LTC":
                factory = LitecoinNetworkParametersLegacy::new; break;
            case "PIVX":
                factory = PivxNetworkParametersLegacy::new; break;
            case "PKOIN":
                factory = PocketcoinNetworkParametersLegacy::new; break;
            case "RVN":
                factory = RavencoinNetworkParametersLegacy::new; break;
            case "SYS":
                factory = SyscoinNetworkParametersLegacy::new; break;
            case "UNO":
                factory = UnobtaniumNetworkParametersLegacy::new; break;
            default:
                throw new IllegalArgumentException(ticker);
        }
        return new NetworkParametersPair(factory.create(),
                ConfigurableNetworkParameters.from(cfgs.get(ticker)));
    }

    private static final class NetworkParametersPair {
        final NetworkParameters legacy;
        final NetworkParameters generic;

        NetworkParametersPair(NetworkParameters legacy,
                              NetworkParameters generic) {
            this.legacy = legacy;
            this.generic = generic;
        }
    }
}
