package io.xlite.daemon.app.net.api.http.server;

import com.subgraph.orchid.encoders.Base64;

import io.xlite.daemon.app.net.CoinTicker;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VarInt;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Map;

/**
 * Bitcoin-style signed messages: the per-coin header prefix, signing and key recovery.
 *
 * <p>Not every coin has a header mapping. A coin without one cannot sign a message that another
 * Bitcoin-derived wallet can verify, and it fails here rather than signing against a made-up
 * prefix.
 */
final class SignedMessages {

    private static final Map<CoinTicker, String> HEADERS =
            Map.ofEntries(
                    Map.entry(CoinTicker.BLOCKNET, "Blocknet Signed Message:\n"),
                    Map.entry(CoinTicker.BITCOIN, "Bitcoin Signed Message:\n"),
                    Map.entry(CoinTicker.BITCOIN_CASH, "Bitcoin Signed Message:\n"),
                    Map.entry(CoinTicker.BITCORE, "BitCore Signed Message:\n"),
                    Map.entry(CoinTicker.LITECOIN, "Litecoin Signed Message:\n"),
                    Map.entry(CoinTicker.PIVX, "DarkNet Signed Message:\n"),
                    Map.entry(CoinTicker.DASHCOIN, "DarkCoin Signed Message:\n"),
                    Map.entry(CoinTicker.UNOBTANIUM, "Unobtanium Signed Message:\n"),
                    Map.entry(CoinTicker.PKOIN, "Pocketcoin Signed Message:\n"),
                    Map.entry(CoinTicker.DIGIBYTE, "DigiByte Signed Message:\n"),
                    Map.entry(CoinTicker.RAVENCOIN, "Raven Signed Message:\n"),
                    Map.entry(CoinTicker.DOGECOIN, "Dogecoin Signed Message:\n"),
                    Map.entry(CoinTicker.SYSCOIN, "Syscoin Signed Message:\n"),
                    Map.entry(CoinTicker.VERGE, "VERGE Signed Message:\n"));

    private SignedMessages() {}

    /**
     * The coin's signed-message header prefix.
     *
     * <p>Package-private for testability: a wrong prefix produces signatures that verify nowhere
     * else, and no other test can observe the string. A coin without a mapping throws, because
     * signing under another coin's prefix produces a signature its own network will not verify.
     */
    static String header(CoinTicker ticker) {
        if (ticker == null) {
            throw new IllegalArgumentException("a ticker is required");
        }
        String header = HEADERS.get(ticker);
        if (header == null) {
            throw new IllegalArgumentException("no signed-message header for " + ticker);
        }
        return header;
    }

    /** A base64 compact signature over {@code message}, as Bitcoin wallets produce it. */
    static String sign(CoinTicker ticker, ECKey key, String message) {
        Sha256Hash hash = Sha256Hash.twiceOf(format(ticker, message));
        ECKey.ECDSASignature signature = key.sign(hash);
        byte recoveryId = -1;
        for (int i = 0; i < 4; i++) {
            ECKey recovered = ECKey.recoverFromSignature(i, signature, hash, key.isCompressed());
            if (recovered != null && Arrays.equals(recovered.getPubKey(), key.getPubKey())) {
                recoveryId = (byte) i;
                break;
            }
        }
        if (recoveryId == -1) {
            throw new IllegalStateException("Recovery ID is invalid.");
        }

        byte[] sigData = new byte[65];
        sigData[0] = (byte) (recoveryId + 27 + (key.isCompressed() ? 4 : 0));
        System.arraycopy(Utils.bigIntegerToBytes(signature.r, 32), 0, sigData, 1, 32);
        System.arraycopy(Utils.bigIntegerToBytes(signature.s, 32), 0, sigData, 33, 32);
        return new String(Base64.encode(sigData), StandardCharsets.UTF_8);
    }

    /** The key that produced {@code signatureB64} over {@code message}. */
    static ECKey recoverKey(CoinTicker ticker, String signatureB64, String message)
            throws SignatureException {
        byte[] signatureEncoded;
        try {
            signatureEncoded = Base64.decode(signatureB64);
        } catch (RuntimeException e) {
            throw new SignatureException("Could not decode base64", e);
        }
        if (signatureEncoded.length < 65) {
            throw new SignatureException(
                    "Signature truncated, expected 65 bytes and got " + signatureEncoded.length);
        }

        int headerByte = signatureEncoded[0] & 0xFF;
        if (headerByte < 27 || headerByte > 34) {
            throw new SignatureException("Header byte out of range: " + headerByte);
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signatureEncoded, 1, 33));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signatureEncoded, 33, 65));
        boolean compressed = headerByte >= 31;
        int recoveryId = headerByte - (compressed ? 31 : 27);

        Sha256Hash messageHash = Sha256Hash.twiceOf(format(ticker, message));
        ECKey key =
                ECKey.recoverFromSignature(
                        recoveryId, new ECKey.ECDSASignature(r, s), messageHash, compressed);
        if (key == null) {
            throw new SignatureException("Could not recover public key from signature");
        }
        return key;
    }

    /**
     * The bytes a signature covers: a length-prefixed header, then the length-prefixed message.
     *
     * <p>The header length is written as a single byte, which is what every Bitcoin-derived
     * implementation does; the message length is a VarInt.
     */
    private static byte[] format(CoinTicker ticker, String message) {
        String header = header(ticker);
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream formatted = new ByteArrayOutputStream();
        formatted.write(headerBytes.length);
        formatted.writeBytes(headerBytes);
        formatted.writeBytes(new VarInt(messageBytes.length).encode());
        formatted.writeBytes(messageBytes);
        return formatted.toByteArray();
    }
}
