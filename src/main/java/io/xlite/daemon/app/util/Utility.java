package io.xlite.daemon.app.util;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;

public class Utility {
    public static boolean isValidAddress(NetworkParameters params, String address) {
        if (address == null || address.isEmpty())
            return false;

        try {
            LegacyAddress.fromBase58(params, address);
            return true;
        } catch (AddressFormatException ignored) {
            // fall through to segwit check
        }

        try {
            SegwitAddress.fromBech32(params, address);
            return true;
        } catch (AddressFormatException e) {
            return false;
        }
    }
}
