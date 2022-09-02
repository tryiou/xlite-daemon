package io.cloudchains.app.net.protocols.blocknet;

import com.subgraph.orchid.encoders.Hex;
import org.bitcoinj.core.BitcoinSerializer;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.Utils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class BlocknetPacketHeader extends BitcoinSerializer.BitcoinPacketHeader {

	public static final int HEADER_LENGTH = 20;

	private String command;
	private byte[] checksum;
	private int length;

	public BlocknetPacketHeader(ByteBuffer in) throws ProtocolException, BufferUnderflowException {
		super(in);
		in.position(in.position() - BitcoinSerializer.BitcoinPacketHeader.HEADER_LENGTH);
		byte[] header = new byte[HEADER_LENGTH];
		in.get(header, 0, header.length);

		int cursor = 0;

		for (; header[cursor] != 0x00 && cursor < 12; cursor++);

		byte[] commandBytes = new byte[cursor];
		System.arraycopy(header, 0, commandBytes, 0, cursor);
		cursor = 12;

		command = new String(commandBytes).trim();
//		LOGGER.log(Level.FINER, "[blocknet-header] Retrieved command: " + command);

		length = (int) Utils.readUint32(header, cursor);
//		LOGGER.log(Level.FINER, "[blocknet-header] Retrieved length: " + length);
		cursor += 4;

		if (length > Message.MAX_SIZE || length < 0) {
			throw new ProtocolException("Message too large or negative length: " + length);
		}

		checksum = new byte[4];
		System.arraycopy(header, cursor, checksum, 0, 4);
//		LOGGER.log(Level.FINER, "[blocknet-header] Retrieved checksum: " + new String(Hex.encode(checksum)));
	}

	public String getCommand() {
		return command;
	}

	public void setChecksum(byte[] checksum) {
		this.checksum = checksum;
	}

	public byte[] getChecksum() {
		return checksum;
	}

	public void setLength(int length) {
		this.length = length;
	}

	public int getLength() {
		return length;
	}
}
