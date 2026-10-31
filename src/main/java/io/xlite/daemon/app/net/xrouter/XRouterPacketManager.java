package io.xlite.daemon.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.net.protocols.blocknet.BlocknetParameters;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javax.annotation.Nullable;

public class XRouterPacketManager {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final int XROUTER_PACKET_VERSION = 0xff000023;

    private final XRouterMessageSerializer xRouterMessageSerializer;
    private final BlocknetParameters blocknetNetworkParameters;

    public XRouterPacketManager(
            XRouterMessageSerializer xRouterMessageSerializer,
            BlocknetParameters blocknetNetworkParameters) {
        this.xRouterMessageSerializer = xRouterMessageSerializer;
        this.blocknetNetworkParameters = blocknetNetworkParameters;
    }

    public static int getXRouterPacketVersion() {
        return XROUTER_PACKET_VERSION;
    }

    private byte[] signPacket(byte[] packetBytes, ECKey ecPrivateKey) {
        LOGGER.finer(
                "[xrouter] DEBUG: Packet bytes: "
                        + new String(Hex.encode(packetBytes), StandardCharsets.UTF_8));
        Sha256Hash packetHash = Sha256Hash.wrap(Sha256Hash.hash(packetBytes));
        LOGGER.finer("[xrouter] DEBUG: Packet byte hash: " + packetHash.toString());
        ECKey.ECDSASignature rawSignature = ecPrivateKey.sign(packetHash).toCanonicalised();

        byte[] r = rawSignature.r.toByteArray();
        byte[] s = rawSignature.s.toByteArray();

        if (r.length > 32) {
            LOGGER.finer(
                    "[xrouter] WARNING: Signature R is greater than 32 bytes! Trimming from the"
                            + " beginning. Size: "
                            + r.length);
            LOGGER.finer(
                    "[xrouter] WARNING: Signature R: "
                            + new String(Hex.encode(r), StandardCharsets.UTF_8));
        } else if (r.length < 32) {
            LOGGER.finer(
                    "[xrouter] WARNING: Signature R is less than 32 bytes! Prepending null bytes to"
                            + " the beginning. Size: "
                            + s.length);
            LOGGER.finer(
                    "[xrouter] WARNING: Signature R: "
                            + new String(Hex.encode(r), StandardCharsets.UTF_8));

            r = prependNullTo32(r);
        }

        if (s.length > 32) {
            LOGGER.finer(
                    "[xrouter] WARNING: Signature S is greater than 32 bytes! Trimming from the"
                            + " beginning. Size: "
                            + s.length);
            LOGGER.finer(
                    "[xrouter] WARNING: Signature S: "
                            + new String(Hex.encode(s), StandardCharsets.UTF_8));
        } else if (s.length < 32) {
            LOGGER.finer(
                    "[xrouter] WARNING: Signature S is less than 32 bytes! Prepending null bytes."
                            + " Size: "
                            + s.length);
            LOGGER.finer(
                    "[xrouter] WARNING: Signature S: "
                            + new String(Hex.encode(s), StandardCharsets.UTF_8));

            s = prependNullTo32(s);
        }

        byte[] signature = new byte[64];

        System.arraycopy(r, r.length - 32, signature, 0, 32);
        System.arraycopy(s, s.length - 32, signature, 32, 32);

        LOGGER.finer(
                "[xrouter] Signature: "
                        + new String(Hex.encode(signature), StandardCharsets.UTF_8)
                        + ", byte length "
                        + signature.length);

        return signature;
    }

    private byte[] prependNullTo32(byte[] toPrependTo) {
        Preconditions.checkState(
                toPrependTo.length < 32, "This array is too large (32 bytes or more)");

        byte[] processed = new byte[32];

        System.arraycopy(
                new byte[32 - toPrependTo.length], 0, processed, 0, 32 - toPrependTo.length);
        System.arraycopy(toPrependTo, 0, processed, 32 - toPrependTo.length, toPrependTo.length);

        return processed;
    }

    /**
     * Everything one XRouter request carries besides its command-specific fields: the peer, the
     * request id, and the key that signs the packet.
     */
    public record XRouterRequest(
            BlocknetPeer peer, String uuid, ECKey privateKey, ECKey publicKey) {}

    private XRouterMessage getPacket(
            XRouterRequest request, int size, int commandId, HashMap<String, Object> body) {
        Preconditions.checkNotNull(xRouterMessageSerializer);
        Preconditions.checkNotNull(blocknetNetworkParameters);

        PacketHeader header = buildHeader(request, size, commandId);

        LOGGER.finer("[xrouter] Serializing XRouter message (phase 1).");
        XRouterMessage message =
                new XRouterMessage(
                        request.peer(),
                        new XRouterPacketHeader(ByteBuffer.wrap(header.bytes())),
                        body);
        byte[] rawPacket = message.bitcoinSerialize();
        byte[] toSign = Arrays.copyOfRange(rawPacket, header.compactSizeBytes(), rawPacket.length);

        byte[] signature = signPacket(toSign, request.privateKey());
        ByteBuffer signed = ByteBuffer.allocate(header.bytes().length);
        signed.put(header.bytes());
        // The signature replaces the reserved area the header carries, not the bytes after it.
        signed.position(header.signatureOffset());
        signed.put(signature);
        signed.flip();

        LOGGER.finer("[xrouter] Serializing XRouter message (phase 2).");
        return new XRouterMessage(request.peer(), new XRouterPacketHeader(signed), body);
    }

    /** The header, its compact-size prefix length, and where its signature belongs. */
    private record PacketHeader(byte[] bytes, int compactSizeBytes, int signatureOffset) {}

    private static PacketHeader buildHeader(XRouterRequest request, int size, int commandId) {
        int extSize = size + 157;
        byte[] bytes = new byte[headerLength(extSize)];
        int compactSizeBytes = writeCompactSizePrefix(bytes, extSize);

        int cursor = compactSizeBytes;
        Utils.uint32ToByteArrayLE(XROUTER_PACKET_VERSION, bytes, cursor);
        cursor += 4;
        Utils.uint32ToByteArrayLE(commandId, bytes, cursor);
        cursor += 4;
        Utils.uint32ToByteArrayLE(System.currentTimeMillis() / 1000L, bytes, cursor);
        cursor += 4;
        Utils.uint32ToByteArrayLE(size, bytes, cursor);
        cursor += 4;

        // fill in reserved header fields with zeros
        System.arraycopy(new byte[8], 0, bytes, cursor, 8);
        cursor += 8;

        System.arraycopy(request.uuid().getBytes(StandardCharsets.UTF_8), 0, bytes, cursor, 36);
        cursor += 36;

        System.arraycopy(request.publicKey().getPubKey(), 0, bytes, cursor, 33);
        cursor += 33;

        int signatureOffset = cursor;
        System.arraycopy(new byte[64], 0, bytes, cursor, 64);
        return new PacketHeader(bytes, compactSizeBytes, signatureOffset);
    }

    /** Header length including the compact-size prefix: 157 bytes of fields plus that prefix. */
    private static int headerLength(int extSize) {
        if (extSize < 253) {
            return 158;
        }
        return extSize <= 65535 ? 160 : 162;
    }

    private static int writeCompactSizePrefix(byte[] bytes, int extSize) {
        if (extSize < 253) {
            bytes[0] = (byte) extSize;
            return 1;
        }
        if (extSize <= 65535) {
            bytes[0] = (byte) 253;
            bytes[1] = (byte) (0xFF & extSize);
            bytes[2] = (byte) (0xFF & (extSize >> 8));
            return 3;
        }
        bytes[0] = (byte) 254;
        Utils.uint32ToByteArrayLE(extSize, bytes, 1);
        return 5;
    }

    private HashMap<String, Object> getBody(@Nullable String paymentTx) {
        HashMap<String, Object> body = new HashMap<>();
        if (paymentTx != null) {
            body.put("paymentTx", paymentTx);
        }

        return body;
    }

    public XRouterMessage getXrGetBlockCount(XRouterRequest request, String currency) {
        String paymentTx = XRouterFeeUtils.getXRouterFeeTx(request.peer(), "xrGetBlockCount");

        HashMap<String, Object> body = getBody(paymentTx);
        body.put("currency", currency);

        int size = currency.length() + 1 + paymentTx.length() + 1 + 4;

        return getPacket(
                request, size, XRouterCommandUtils.commandStringToInt("xrGetBlockCount"), body);
    }

    public XRouterMessage getXrService(XRouterRequest request, String command, List<?> params) {
        String paymentTx = XRouterFeeUtils.getXRouterFeeTx(request.peer(), "xrService");

        HashMap<String, Object> body = getBody(paymentTx);
        body.put("command", command);
        body.put("params", params);

        int size = command.length() + 1 + paymentTx.length() + 1 + 4 + paramSize(params);

        return getPacket(request, size, XRouterCommandUtils.commandStringToInt("xrService"), body);
    }

    private static int paramSize(List<?> params) {
        int size = 0;
        for (Object param : params) {
            if (param instanceof Boolean) {
                size += 4;
            } else if (param instanceof String) {
                size += ((String) param).length() + 1;
            } else if (param instanceof Integer) {
                size += 4;
            } else {
                throw new IllegalArgumentException(
                        "Argument of unsupported class: " + param.getClass().getSimpleName());
            }
        }
        return size;
    }

    public XRouterMessage getXrGetConfig(XRouterRequest request, String address) {
        HashMap<String, Object> body = getBody(null);
        body.put("addr", address);

        int size = address.length() + 1;

        return getPacket(
                request, size, XRouterCommandUtils.commandStringToInt("xrGetConfig"), body);
    }

    public XRouterMessage getXrSendTransaction(
            XRouterRequest request, String feePayment, String currency, String transaction) {
        HashMap<String, Object> body = getBody(feePayment);
        body.put("currency", currency);
        body.put("transaction", transaction);

        int size = feePayment.length() + 1 + currency.length() + 1 + 4 + transaction.length() + 1;

        return getPacket(
                request, size, XRouterCommandUtils.commandStringToInt("xrSendTransaction"), body);
    }

    public XRouterMessage getXrGetBlockHash(
            XRouterRequest request, String feePayment, String currency, String blockIndex) {
        HashMap<String, Object> body = getBody(feePayment);
        body.put("currency", currency);
        body.put("blockId", blockIndex);

        int size = feePayment.length() + 1 + currency.length() + 1 + 4 + blockIndex.length() + 1;

        return getPacket(
                request, size, XRouterCommandUtils.commandStringToInt("xrGetBlockHash"), body);
    }

    public XRouterMessage getXrGetBlock(
            XRouterRequest request, String feePayment, String currency, String blockHash) {
        HashMap<String, Object> body = getBody(feePayment);
        body.put("currency", currency);
        body.put("blockHash", blockHash);

        int size = feePayment.length() + 1 + currency.length() + 1 + blockHash.length() + 1 + 4;

        return getPacket(request, size, XRouterCommandUtils.commandStringToInt("xrGetBlock"), body);
    }

    public XRouterMessage getXrGetTransaction(
            XRouterRequest request, String currency, String txid) {
        String paymentTx = XRouterFeeUtils.getXRouterFeeTx(request.peer(), "xrGetBlockCount");

        HashMap<String, Object> body = getBody(paymentTx);
        body.put("currency", currency);
        body.put("txid", txid);

        int size = paymentTx.length() + 1 + currency.length() + 1 + txid.length() + 1 + 4;

        return getPacket(
                request, size, XRouterCommandUtils.commandStringToInt("xrGetTransaction"), body);
    }
}
