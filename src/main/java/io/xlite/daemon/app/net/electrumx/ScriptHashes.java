package io.xlite.daemon.app.net.electrumx;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Script hashes as ElectrumX names them.
 *
 * <p>The indexer never sees addresses: it indexes {@code sha256(scriptPubKey)}. A subscription or a
 * history query therefore needs the same script the wallet would pay to, hashed and byte-reversed
 * for the wire. Ownership checks inside a transaction use hashX (the first eleven bytes of the same
 * digest, in forward order), which is what ElectrumX's own history code compares.
 */
public final class ScriptHashes {

    public static final int HASH_X_LENGTH = 11;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ScriptHashes() {}

    /** Wire scripthash for a wallet address. */
    public static String scripthash(Address address) {
        return scripthash(ScriptBuilder.createOutputScript(address).getProgram());
    }

    /** Ownership token for a wallet address. */
    public static String hashX(Address address) {
        return hashX(ScriptBuilder.createOutputScript(address).getProgram());
    }

    /** Wire scripthash: sha256(script), reversed, hex. */
    public static String scripthash(byte[] script) {
        byte[] digest = sha256(script);
        reverse(digest);
        return hex(digest);
    }

    /** Ownership token: the first eleven bytes of sha256(script), hex. */
    public static String hashX(byte[] script) {
        return hex(Arrays.copyOf(sha256(script), HASH_X_LENGTH));
    }

    /** Ownership token from a scriptPubKey hex string, or {@code null} when it is not hex. */
    public static String hashXFromScriptHex(String scriptHex) {
        if (scriptHex == null || scriptHex.isEmpty()) {
            return null;
        }
        try {
            return hashX(Utils.HEX.decode(scriptHex));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Ownership token from an address in a provider's spelling, or {@code null} if undecodable. */
    public static String hashXFromAddress(NetworkParameters parameters, String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        try {
            Script script =
                    ScriptBuilder.createOutputScript(Address.fromString(parameters, address));
            return hashX(script.getProgram());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] sha256(byte[] script) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(script);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void reverse(byte[] bytes) {
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte value = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = value;
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            out[index * 2] = HEX[value >>> 4];
            out[index * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
