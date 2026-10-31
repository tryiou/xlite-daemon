package io.xlite.daemon.app.net.protocols.blocknet;

import org.bitcoinj.core.BitcoinSerializer;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.Utils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The 20-byte Blocknet wire header: magic, command, payload length and checksum. */
public final class BlocknetPacketHeader extends BitcoinSerializer.BitcoinPacketHeader {

    public static final int HEADER_LENGTH = 20;

    private final String command;
    private final byte[] checksum;
    private int length;

    public BlocknetPacketHeader(ByteBuffer in) throws ProtocolException, BufferUnderflowException {
        super(in);
        in.position(in.position() - BitcoinSerializer.BitcoinPacketHeader.HEADER_LENGTH);
        byte[] header = new byte[HEADER_LENGTH];
        in.get(header, 0, header.length);

        int cursor = 0;
        // The command field is zero-padded within 12 bytes. Bounds first: a
        // command that fills all 12 bytes has no terminator to look for.
        while (cursor < 12 && header[cursor] != 0x00) {
            cursor++;
        }

        command = new String(header, 0, cursor, StandardCharsets.US_ASCII).trim();
        cursor = 12;

        length = (int) Utils.readUint32(header, cursor);
        cursor += 4;

        if (length > Message.MAX_SIZE || length < 0) {
            throw new ProtocolException("Message too large or negative length: " + length);
        }

        checksum = Arrays.copyOfRange(header, cursor, cursor + 4);
    }

    public String getCommand() {
        return command;
    }

    /** Copies the four checksum bytes in; the caller keeps ownership of its array. */
    public void setChecksum(byte[] checksum) {
        if (checksum.length != 4) {
            throw new IllegalArgumentException("checksum must be 4 bytes");
        }
        System.arraycopy(checksum, 0, this.checksum, 0, 4);
    }

    /** A copy: the header's checksum must not be mutable through this accessor. */
    public byte[] getChecksum() {
        return checksum.clone();
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getLength() {
        return length;
    }
}
