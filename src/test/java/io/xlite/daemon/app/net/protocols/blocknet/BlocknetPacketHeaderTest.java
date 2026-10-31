package io.xlite.daemon.app.net.protocols.blocknet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

class BlocknetPacketHeaderTest {

    /** A 20-byte Blocknet header: command(12), length(4 LE), checksum(4), plus payload. */
    private static ByteBuffer header(String command, int length, byte[] checksum) {
        byte[] bytes = new byte[24];
        byte[] commandBytes = command.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(commandBytes, 0, bytes, 0, commandBytes.length);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, length);
        System.arraycopy(checksum, 0, bytes, 16, 4);
        return ByteBuffer.wrap(bytes);
    }

    @Test
    void readsCommandLengthAndChecksum() {
        BlocknetPacketHeader parsed =
                new BlocknetPacketHeader(header("version", 70000, new byte[] {1, 2, 3, 4}));

        assertEquals("version", parsed.getCommand());
        assertEquals(70000, parsed.getLength());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, parsed.getChecksum());
    }

    @Test
    void commandFillingAllTwelveBytesNeedsNoTerminator() {
        BlocknetPacketHeader parsed =
                new BlocknetPacketHeader(header("abcdefghijkl", 1, new byte[] {0, 0, 0, 0}));

        assertEquals("abcdefghijkl", parsed.getCommand());
    }

    @Test
    void checksumCannotBeMutatedThroughTheAccessor() {
        byte[] source = {9, 8, 7, 6};
        BlocknetPacketHeader parsed = new BlocknetPacketHeader(header("ping", 0, source));

        byte[] read = parsed.getChecksum();
        read[0] = 0;

        assertArrayEquals(source, parsed.getChecksum());
    }

    @Test
    void setChecksumCopiesTheCallersArray() {
        BlocknetPacketHeader parsed =
                new BlocknetPacketHeader(header("ping", 0, new byte[] {0, 0, 0, 0}));
        byte[] checksum = {4, 3, 2, 1};

        parsed.setChecksum(checksum);
        checksum[0] = 0;

        assertArrayEquals(new byte[] {4, 3, 2, 1}, parsed.getChecksum());
    }

    @Test
    void rejectsChecksumOfTheWrongLength() {
        BlocknetPacketHeader parsed =
                new BlocknetPacketHeader(header("ping", 0, new byte[] {0, 0, 0, 0}));

        assertThrows(
                IllegalArgumentException.class, () -> parsed.setChecksum(new byte[] {1, 2, 3}));
    }

    @Test
    void rejectsPayloadLengthBeyondTheProtocolLimit() {
        assertThrows(
                RuntimeException.class,
                () -> new BlocknetPacketHeader(header("block", Integer.MAX_VALUE, new byte[4])));
    }
}
