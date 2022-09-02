package io.cloudchains.app.util;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;

public class Utility {
    public static boolean isValidAddress(NetworkParameters params, String address) {
        try {
            Address.fromBase58(params, address);
            return true;
        } catch(AddressFormatException e) {
            return false;
        }
    }
}
