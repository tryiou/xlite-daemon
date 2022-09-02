package io.cloudchains.app.net.protocols.blocknet;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class BlocknetUtils {

	public static void seekPastMagicBytes(ByteBuffer in, BlocknetParameters params) {
		int magicCursor = 3;
		while (true) {
			byte b = in.get();

			byte expectedByte = (byte)(0xFF & params.getPacketMagic() >>> (magicCursor * 8));
			if (b == expectedByte) {
				magicCursor--;
				if (magicCursor < 0) {
					return;
				}
			} else {
				magicCursor = 3;
			}
		}
	}

	public static boolean verifyChecksum(BlocknetPacketHeader header, byte[] payloadBytes) {
		byte[] hash = Sha256Hash.hashTwice(payloadBytes);
		byte[] headerChecksum = new byte[4];
		byte[] checksum = new byte[4];
		System.arraycopy(header.getChecksum(), 0, headerChecksum, 0, 4);
		System.arraycopy(hash, 0, checksum, 0, 4);

		return Arrays.equals(headerChecksum, checksum);
	}

	public static byte[] getHeader(String name, int magic, byte[] payloadBytes) {
		byte[] header = new byte[BlocknetPacketHeader.HEADER_LENGTH + 4];

		Utils.uint32ToByteArrayBE(magic, header, 0);

		for (int i = 0; i < name.length() && i < 12; i++) {
			header[4 + i] = (byte) (name.codePointAt(i) & 0xFF);
		}

		Utils.uint32ToByteArrayLE(payloadBytes.length, header, 4 + 12);
		byte[] hash = Sha256Hash.hashTwice(payloadBytes);
		System.arraycopy(hash, 0, header, 4 + 12 + 4, 4);

		return header;
	}

}
