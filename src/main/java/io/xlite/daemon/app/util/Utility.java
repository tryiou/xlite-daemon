package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CompiledCoinSupplement;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;

/**
 * Address parsing: every spelling a chain accepts, resolved to one object.
 *
 * <p>xlite publishes, stores and signs addresses in their legacy form. A peer, however, rebuilds an
 * address from the 20-byte identifier it carries on the wire, and on Bitcoin Cash that rebuild is a
 * CashAddr. Both spellings name the same key, so anything that receives an address from outside the
 * wallet must accept either and compare the decoded address, never the string.
 *
 * <p>This is the single entry point for that: every RPC method and every transaction builder parses
 * an incoming address here.
 */
public final class Utility {

    private Utility() {}

    /**
     * Parses an address in any spelling {@code ticker}'s chain accepts, or returns {@code null}.
     *
     * <p>base58check is tried first (every coin), then bech32 (chains with native segwit), then the
     * chain-specific alias — CashAddr, for the coins whose compiled supplement declares an HRP.
     *
     * @param params the coin's network parameters
     * @param ticker the coin's ticker, which selects the chain-specific spelling
     * @param address the caller's spelling
     * @return the decoded address, or null when no supported spelling matches
     */
    public static Address parseAddress(NetworkParameters params, String ticker, String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }

        try {
            return LegacyAddress.fromBase58(params, address);
        } catch (AddressFormatException ignored) {
            // fall through to the segwit and chain-specific spellings
        }

        try {
            return SegwitAddress.fromBech32(params, address);
        } catch (AddressFormatException ignored) {
            // fall through to the chain-specific spelling
        }

        String cashAddrPrefix = ticker == null ? "" : CompiledCoinSupplement.cashAddrPrefix(ticker);
        if (cashAddrPrefix.isEmpty()) {
            return null;
        }

        try {
            return CashAddress.toLegacyAddress(params, address, cashAddrPrefix);
        } catch (AddressFormatException ignored) {
            return null;
        }
    }

    /**
     * The address in its legacy form, or an exception when it names none.
     *
     * <p>Transaction builders need the legacy object: they derive an output script from its hash,
     * and every coin xlite serves pays to a base58check or a script-hash destination.
     *
     * @throws AddressFormatException when the address is unparseable or is not a legacy one
     */
    public static LegacyAddress requireLegacyAddress(
            NetworkParameters params, String ticker, String address) {
        if (parseAddress(params, ticker, address) instanceof LegacyAddress legacy) {
            return legacy;
        }
        throw new AddressFormatException(
                "not a " + ticker + " address in any accepted spelling: " + address);
    }
}
