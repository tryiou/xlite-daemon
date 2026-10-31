package io.xlite.daemon.app.net.xrouter;

import com.subgraph.orchid.encoders.Hex;

import org.bitcoinj.core.Utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterPacketHeader {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private byte[] compactSizeBytes;
    private int version;
    private int command;
    private int timestamp;
    private int size;
    private String uuid;
    private byte[] pubkey;
    private byte[] signature;

    private int headerLength;

    public XRouterPacketHeader(ByteBuffer in) {
        RawHeader raw = readRawHeader(in);
        compactSizeBytes = raw.compactSizeBytes();

        int cursor = readFixedFields(raw.rawHeader(), raw.cursor());
        cursor = readIdentity(raw.rawHeader(), cursor);

        headerLength = cursor;
        LOGGER.finer("[xrouter] XRouter header read complete, at position: " + cursor);
        // should have read 157 bytes at this point (excluding compact size)
    }

    /** The header bytes as read, the compact-size prefix, and where the fields start. */
    private record RawHeader(byte[] rawHeader, byte[] compactSizeBytes, int cursor) {}

    private static RawHeader readRawHeader(ByteBuffer in) {
        byte compactSize = in.get();
        in.rewind();
        byte[] rawHeader;
        byte[] compactSizeBytes;
        int cursor;
        if (compactSize == (byte) 253) {
            rawHeader = new byte[160];
            compactSizeBytes = new byte[2];
            cursor = 1;
        } else if (compactSize == (byte) 254) {
            rawHeader = new byte[162];
            compactSizeBytes = new byte[4];
            cursor = 1;
        } else if (compactSize == (byte) 255) {
            rawHeader = new byte[166];
            compactSizeBytes = new byte[8];
            cursor = 1;
        } else {
            rawHeader = new byte[158];
            compactSizeBytes = new byte[1];
            cursor = 0;
        }
        in.get(rawHeader, 0, rawHeader.length);
        System.arraycopy(rawHeader, cursor, compactSizeBytes, 0, compactSizeBytes.length);
        cursor += compactSizeBytes.length;

        LOGGER.finer(
                "[xrouter] Retrieved compact size: "
                        + new String(Hex.encode(new byte[] {compactSize}), StandardCharsets.UTF_8));
        LOGGER.finer(
                "[xrouter] Retrieved compact size bytes: "
                        + new String(Hex.encode(compactSizeBytes), StandardCharsets.UTF_8));
        return new RawHeader(rawHeader, compactSizeBytes, cursor);
    }

    private int readFixedFields(byte[] rawHeader, int cursor) {
        version = (int) Utils.readUint32(rawHeader, cursor);
        cursor += 4;
        LOGGER.finer("[xrouter] Retrieved version: " + version);
        command = (int) Utils.readUint32(rawHeader, cursor);
        cursor += 4;
        LOGGER.finer("[xrouter] Retrieved command: " + command);
        timestamp = (int) Utils.readUint32(rawHeader, cursor);
        cursor += 4;
        LOGGER.finer("[xrouter] Retrieved timestamp: " + timestamp);
        size = (int) Utils.readUint32(rawHeader, cursor);
        cursor += 4;
        LOGGER.finer("[xrouter] Retrieved size: " + size);

        // reserved header fields, unused
        return cursor + 8;
    }

    private int readIdentity(byte[] rawHeader, int cursor) {
        byte[] uuidArr = new byte[36];
        System.arraycopy(rawHeader, cursor, uuidArr, 0, uuidArr.length);
        cursor += 36;
        uuid = new String(uuidArr, StandardCharsets.UTF_8);
        LOGGER.finer("[xrouter] Retrieved UUID: " + uuid);

        byte[] pubkeyArr = new byte[33];
        System.arraycopy(rawHeader, cursor, pubkeyArr, 0, pubkeyArr.length);
        cursor += 33;
        LOGGER.finer(
                "[xrouter] Retrieved pubkey: "
                        + new String(Hex.encode(pubkeyArr), StandardCharsets.UTF_8));
        pubkey = pubkeyArr;

        byte[] sigArr = new byte[64];
        System.arraycopy(rawHeader, cursor, sigArr, 0, sigArr.length);
        cursor += 64;
        LOGGER.finer(
                "[xrouter] Retrieved signature: "
                        + new String(Hex.encode(sigArr), StandardCharsets.UTF_8));
        signature = sigArr;
        return cursor;
    }

    byte[] getCompactSizeBytes() {
        return compactSizeBytes;
    }

    int getVersion() {
        return version;
    }

    public int getCommand() {
        return command;
    }

    int getTimestamp() {
        return timestamp;
    }

    int getExtSize() {
        return size + 157;
    }

    int getSize() {
        return size;
    }

    public String getUUID() {
        return uuid;
    }

    byte[] getPubkey() {
        return pubkey;
    }

    byte[] getSignature() {
        return signature;
    }

    int getHeaderLength() {
        return headerLength;
    }
}
