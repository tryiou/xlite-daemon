package io.xlite.daemon.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.xlite.daemon.app.coinconfig.CompiledCoinSupplement;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.testing.CoinFixture;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.junit.jupiter.api.Test;

/** CashAddr decoding, against the vectors published in the format specification. */
class CashAddressTest {

    private static final String PREFIX = "bitcoincash";

    private static NetworkParameters params() {
        return ConfigurableNetworkParameters.from(
                CoinFixture.coin("BCH", "Bitcoin Cash")
                        .prefixes(0, 5, 128)
                        .createTxMethod("BCH")
                        .build());
    }

    private static LegacyAddress legacy(String cashAddr) {
        return CashAddress.toLegacyAddress(params(), cashAddr, PREFIX);
    }

    /**
     * The specification's own P2PKH examples. Every one must decode to the legacy address printed
     * beside it: the two spellings share the script hash, which is the whole point of the alias.
     */
    @Test
    void decodesSpecificationP2pkhVectors() {
        assertEquals(
                "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu",
                legacy("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a").toBase58());
        assertEquals(
                "1KXrWXciRDZUpQwQmuM1DbwsKDLYAYsVLR",
                legacy("bitcoincash:qr95sy3j9xwd2ap32xkykttr4cvcu7as4y0qverfuy").toBase58());
        assertEquals(
                "16w1D5WRVKJuZUsSRzdLp9w3YGcgoxDXb",
                legacy("bitcoincash:qqq3728yw0y47sqn6l2na30mcw6zm78dzqre909m2r").toBase58());
    }

    /** The specification's P2SH example decodes to the script-hash legacy form. */
    @Test
    void decodesSpecificationP2shVector() {
        LegacyAddress decoded = legacy("bitcoincash:ppm2qsznhks23z7629mms6s4cwef74vcwvn0h829pq");
        assertEquals("3CWFddi6m4ndiGyKqzYvsFYagqDLPVMTzC", decoded.toBase58());
        assertEquals(true, decoded.isP2SHAddress());
    }

    /**
     * A prefixless address is valid and takes the coin's prefix; uppercase is valid too. Both name
     * the same key as the lower-case, prefixed form a peer sends.
     */
    @Test
    void acceptsPrefixlessAndUppercaseSpellings() {
        String canonical = "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a";
        String legacyAddress = legacy(canonical).toBase58();

        assertEquals(
                legacyAddress, legacy("qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a").toBase58());
        assertEquals(legacyAddress, legacy(canonical.toUpperCase()).toBase58());
    }

    /** A checksum that does not verify is not an address. */
    @Test
    void rejectsBadChecksum() {
        assertThrows(
                AddressFormatException.class,
                () -> legacy("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6b"));
    }

    /** Mixed case is ambiguous by construction, so the format forbids it. */
    @Test
    void rejectsMixedCase() {
        assertThrows(
                AddressFormatException.class,
                () -> legacy("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6A"));
    }

    /** Another chain's prefix is not this coin's address. */
    @Test
    void rejectsForeignPrefix() {
        assertThrows(
                AddressFormatException.class,
                () -> legacy("bchtest:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"));
    }

    /** A truncated payload is rejected on the version/length check, not accepted by accident. */
    @Test
    void rejectsTruncatedPayload() {
        assertThrows(AddressFormatException.class, () -> legacy("bitcoincash:qpm2qsznhks23z76"));
    }

    /** Only BCH declares a CashAddr spelling; every other coin has none. */
    @Test
    void prefixIsDeclaredForBchOnly() {
        assertEquals("bitcoincash", CompiledCoinSupplement.cashAddrPrefix("BCH"));
        assertEquals("", CompiledCoinSupplement.cashAddrPrefix("LTC"));
        assertEquals("", CompiledCoinSupplement.cashAddrPrefix("BLOCK"));
    }

    /**
     * The wallet's ownership test is address equality, so both spellings of one key must decode to
     * the same {@link LegacyAddress}: that is what makes a CashAddr UTXO address resolve to the key
     * the wallet already tracks.
     */
    @Test
    void legacyAndCashAddrSpellingsDecodeToTheSameAddress() {
        String legacyAddress = "1MymJvqYN16k5cXUKeQtP59VnhgAVLk1Kx";
        String cashAddr = "bitcoincash:qrnpanqz45mmudmvs9mfzjnc64tfpk9ggsurdxwm85";
        Address fromCashAddr = Utility.parseAddress(params(), "BCH", cashAddr);
        Address fromLegacy = Utility.parseAddress(params(), "BCH", legacyAddress);

        assertEquals(fromLegacy, fromCashAddr);
        assertEquals(legacyAddress, ((LegacyAddress) fromCashAddr).toBase58());
    }

    /** A different key is a different address, whichever spelling is used. */
    @Test
    void differentKeysDoNotCollide() {
        Address first = Utility.parseAddress(params(), "BCH", "1MymJvqYN16k5cXUKeQtP59VnhgAVLk1Kx");
        Address second =
                Utility.parseAddress(params(), "BCH", "1BhE14f5z2Gyb8PVmtHChPjpk64YUDmakK");

        assertNotEquals(first, second);
    }

    /** A coin without a CashAddr HRP does not accept the spelling. */
    @Test
    void coinWithoutCashAddrPrefixRejectsTheSpelling() {
        assertNull(
                Utility.parseAddress(
                        params(), "LTC", "bitcoincash:qrnpanqz45mmudmvs9mfzjnc64tfpk9ggsurdxwm85"));
    }
}
