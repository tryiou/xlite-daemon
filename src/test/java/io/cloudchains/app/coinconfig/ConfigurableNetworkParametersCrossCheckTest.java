package io.cloudchains.app.coinconfig;

import io.cloudchains.app.net.protocols.bitcoin.BitcoinNetworkParameters;
import io.cloudchains.app.net.protocols.bitcoincash.BitcoinCashNetworkParameters;
import io.cloudchains.app.net.protocols.dashcoin.DashcoinNetworkParameters;
import io.cloudchains.app.net.protocols.digibyte.DigibyteNetworkParameters;
import io.cloudchains.app.net.protocols.dogecoin.DogecoinNetworkParameters;
import io.cloudchains.app.net.protocols.litecoin.LitecoinNetworkParameters;
import io.cloudchains.app.net.protocols.pivx.PivxNetworkParameters;
import io.cloudchains.app.net.protocols.pocketcoin.PocketcoinNetworkParameters;
import io.cloudchains.app.net.protocols.ravencoin.RavencoinNetworkParameters;
import io.cloudchains.app.net.protocols.syscoin.SyscoinNetworkParameters;
import io.cloudchains.app.net.protocols.unobtanium.UnobtaniumNetworkParameters;
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
        return new CoinConfigSource(
                Paths.get("..", "blockchain-configuration-files")
                        .toAbsolutePath().normalize().toString()).loadAll();
    }

    @Test
    void testGenericReproducesEveryLiveGetterOfLegacyClasses() {
        Map<String, CoinConfig> cfgs = configs();

        forLegacy(new String[]{"BTC", "BCH", "DASH", "DOGE", "LTC", "PIVX", "PKOIN", "SYS", "UNO"},
                cfgs, false, false);
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
                factory = BitcoinNetworkParameters::new; break;
            case "BCH":
                factory = BitcoinCashNetworkParameters::new; break;
            case "DASH":
                factory = DashcoinNetworkParameters::new; break;
            case "DGB":
                factory = DigibyteNetworkParameters::new; break;
            case "DOGE":
                factory = DogecoinNetworkParameters::new; break;
            case "LTC":
                factory = LitecoinNetworkParameters::new; break;
            case "PIVX":
                factory = PivxNetworkParameters::new; break;
            case "PKOIN":
                factory = PocketcoinNetworkParameters::new; break;
            case "RVN":
                factory = RavencoinNetworkParameters::new; break;
            case "SYS":
                factory = SyscoinNetworkParameters::new; break;
            case "UNO":
                factory = UnobtaniumNetworkParameters::new; break;
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
