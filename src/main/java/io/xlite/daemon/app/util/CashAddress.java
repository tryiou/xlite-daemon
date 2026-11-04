package io.xlite.daemon.app.util;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Decoding for Bitcoin Cash's CashAddr spelling of a legacy address.
 *
 * <p>CashAddr and base58check encode the same 160-bit script hash; they differ only in the spelling
 * a caller uses. XBridge's BCH connector normalises every UTXO it handles through CashAddr, so a
 * wallet that owns the key must resolve that alias to the address it already tracks. This class
 * does the decoding half only: xlite keeps publishing legacy addresses and never has to produce a
 * CashAddr string.
 *
 * <p>A direct port of the reference codec the Blocknet C++ connector vendors (Bitcoin ABC, MIT
 * licence, {@code src/cashaddr/cashaddr.cpp}): the same prefixes, checksum polynomial and
 * version-byte layout, so both implementations accept and reject exactly the same strings.
 */
public final class CashAddress {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final byte[] CHARSET_REV = new byte[128];
    private static final long[] GENERATOR = {
        0x98f2bc8e61L, 0x79b76d99e2L, 0xf33e5fb3c4L, 0xae2eabe2a8L, 0x1e4f43e470L
    };
    private static final int CHECKSUM_LENGTH = 8;
    private static final int HASH160_LENGTH = 20;
    private static final int P2PKH_TYPE = 0;
    private static final int P2SH_TYPE = 1;

    static {
        Arrays.fill(CHARSET_REV, (byte) -1);
        for (int index = 0; index < CHARSET.length(); index++) {
            CHARSET_REV[CHARSET.charAt(index)] = (byte) index;
        }
    }

    private CashAddress() {}

    /**
     * Decodes a CashAddr string for {@code prefix} into the legacy address form the wallet uses.
     *
     * @param params the coin's network parameters
     * @param address a CashAddr with or without its prefix, in any case
     * @param prefix the coin's CashAddr HRP (e.g. {@code bitcoincash})
     * @return the same key as a legacy address
     * @throws AddressFormatException when the spelling, checksum, type or length is invalid
     */
    public static LegacyAddress toLegacyAddress(
            NetworkParameters params, String address, String prefix) {
        byte[] payload = decode(address, prefix);
        int version = payload[0] & 0xFF;
        if ((version & 0x80) != 0) {
            throw new AddressFormatException("CashAddr reserves the high version bit");
        }
        int type = (version >>> 3) & 0x1F;
        int hashLength = 20 + 4 * (version & 0x03);
        if ((version & 0x04) != 0) {
            hashLength *= 2;
        }
        if (payload.length != hashLength + 1) {
            throw new AddressFormatException("CashAddr payload length does not match its version");
        }
        if (hashLength != HASH160_LENGTH) {
            throw new AddressFormatException("CashAddr hash is not 160 bits");
        }
        byte[] hash = Arrays.copyOfRange(payload, 1, payload.length);
        return switch (type) {
            case P2PKH_TYPE -> LegacyAddress.fromPubKeyHash(params, hash);
            case P2SH_TYPE -> LegacyAddress.fromScriptHash(params, hash);
            default -> throw new AddressFormatException("unsupported CashAddr type " + type);
        };
    }

    /** The version byte plus hash, as a byte array, after checksum verification. */
    private static byte[] decode(String address, String expectedPrefix) {
        String lower = normaliseCase(address);
        int separator = lower.indexOf(':');
        String prefix = separator < 0 ? expectedPrefix : lower.substring(0, separator);
        String payload = separator < 0 ? lower : lower.substring(separator + 1);
        if (!prefix.equals(expectedPrefix)) {
            throw new AddressFormatException("CashAddr prefix is not " + expectedPrefix);
        }
        int[] values = symbols(payload);
        if (!verifyChecksum(prefix, values)) {
            throw new AddressFormatException.InvalidChecksum("CashAddr checksum");
        }
        return convertBits(Arrays.copyOf(values, values.length - CHECKSUM_LENGTH), 5, 8, false);
    }

    /**
     * Lower-cases the address and rejects mixed case, which the format forbids.
     *
     * <p>All-lower and all-upper are both valid; a mixed spelling is not, because it would make the
     * checksum ambiguous.
     */
    private static String normaliseCase(String address) {
        if (address == null || address.isEmpty()) {
            throw new AddressFormatException("empty address");
        }
        String lower = address.toLowerCase(Locale.ROOT);
        if (!address.equals(lower) && !address.equals(address.toUpperCase(Locale.ROOT))) {
            throw new AddressFormatException("mixed-case CashAddr");
        }
        return lower;
    }

    private static int[] symbols(String payload) {
        int[] out = new int[payload.length()];
        for (int index = 0; index < payload.length(); index++) {
            char value = payload.charAt(index);
            if (value > 127 || CHARSET_REV[value] < 0) {
                throw new AddressFormatException("invalid CashAddr character");
            }
            out[index] = CHARSET_REV[value];
        }
        return out;
    }

    private static boolean verifyChecksum(String prefix, int[] values) {
        int[] expanded = new int[prefix.length() + 1 + values.length];
        for (int index = 0; index < prefix.length(); index++) {
            expanded[index] = prefix.charAt(index) & 0x1F;
        }
        System.arraycopy(values, 0, expanded, prefix.length() + 1, values.length);
        return polymod(expanded) == 1;
    }

    /**
     * Bitcoin ABC's CashAddr checksum polynomial over 5-bit symbols.
     *
     * <p>The residual of a valid address is 1, not 0: the reference implementation XORs that 1 away
     * and tests for 0, which is the same check written the other way round.
     */
    private static long polymod(int[] values) {
        long checksum = 1;
        for (int value : values) {
            long top = checksum >>> 35;
            checksum = ((checksum & 0x07FFFFFFFFL) << 5) ^ value;
            for (int bit = 0; bit < GENERATOR.length; bit++) {
                if (((top >>> bit) & 1) != 0) {
                    checksum ^= GENERATOR[bit];
                }
            }
        }
        return checksum;
    }

    /** Regroups 5-bit symbols into bytes, rejecting non-zero padding. */
    private static byte[] convertBits(int[] values, int from, int to, boolean pad) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int accumulator = 0;
        int bits = 0;
        int maxValue = (1 << to) - 1;
        for (int value : values) {
            if (value < 0 || (value >>> from) != 0) {
                throw new AddressFormatException("CashAddr symbol exceeds its bit width");
            }
            accumulator = (accumulator << from) | value;
            bits += from;
            while (bits >= to) {
                bits -= to;
                out.write((accumulator >>> bits) & maxValue);
            }
        }
        if (pad) {
            if (bits > 0) {
                out.write((accumulator << (to - bits)) & maxValue);
            }
        } else if (bits >= from || ((accumulator << (to - bits)) & maxValue) != 0) {
            throw new AddressFormatException("invalid CashAddr padding");
        }
        return out.toByteArray();
    }
}
