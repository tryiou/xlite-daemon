package io.cloudchains.app.net.protocols.blocknet;

import com.google.common.util.concurrent.SettableFuture;
import org.bitcoinj.core.Context;
import org.bitcoinj.net.MessageWriteTarget;
import org.bitcoinj.net.StreamConnection;

import javax.annotation.Nullable;
import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class BlocknetBlockingClient implements MessageWriteTarget {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private static final int BUFFER_SIZE_LOWER_BOUND = 4096;
	private static final int BUFFER_SIZE_UPPER_BOUND = 65536;

	private Socket socket;
	private volatile boolean closeRequested = false;
	private SettableFuture<SocketAddress> connectFuture;

	public BlocknetBlockingClient(SocketAddress serverAddress, StreamConnection connection, int connectTimeoutMillis, SocketFactory socketFactory, @Nullable Set<BlocknetBlockingClient> clientSet) throws IOException {
		connectFuture = SettableFuture.create();

		connection.setWriteTarget(this);
		socket = socketFactory.createSocket();
		final Context context = Context.get();
		Thread t = new Thread(() -> {
			Context.propagate(context);
			if (clientSet != null)
				clientSet.add(BlocknetBlockingClient.this);
			try {
				socket.connect(serverAddress, connectTimeoutMillis);
				connection.connectionOpened();
				connectFuture.set(serverAddress);
				InputStream stream = socket.getInputStream();
				runReadLoop(stream, connection);
			} catch (Exception e) {
				if (!closeRequested) {
					LOGGER.log(Level.FINER, "[blocknet-blocking-client] Error trying to open/read from connection with " + serverAddress.toString() + ".");
					e.printStackTrace();
					connectFuture.setException(e);
				}
			} finally {
				try {
					if (!socket.isClosed())
						socket.close();
				} catch (IOException e1) {
					// At this point there isn't much we can do, and we can probably assume the channel is closed
				}
				if (clientSet != null)
					clientSet.remove(BlocknetBlockingClient.this);
				connection.connectionClosed();
			}
		});
		t.setName("Blocknet network thread - " + serverAddress);
		t.setDaemon(true);
		t.start();
	}

	private static void runReadLoop(InputStream inputStream, StreamConnection connection) throws Exception {
		ByteBuffer dbuf = ByteBuffer.allocateDirect(Math.min(Math.max(connection.getMaxMessageSize(), BUFFER_SIZE_LOWER_BOUND), BUFFER_SIZE_UPPER_BOUND));
		byte[] readBuff = new byte[dbuf.capacity()];

		while (true) {
			// TODO Kill the message duplication here
			if (!(dbuf.remaining() > 0 && dbuf.remaining() <= readBuff.length)) {
				throw new IllegalStateException();
			}
			int read = inputStream.read(readBuff, 0, Math.max(1, Math.min(dbuf.remaining(), inputStream.available())));
			if (read == -1)
				return;
			dbuf.put(readBuff, 0, read);

			dbuf.flip();

			int bytesConsumed = connection.receiveBytes(dbuf);
			if (dbuf.position() != bytesConsumed) {
				throw new IllegalStateException("Buffer did not stop reading at the correct location.");
			}

			dbuf.compact();
		}
	}

	@Override
	public void closeConnection() {
		try {
			closeRequested = true;
			socket.close();
		} catch (IOException e) {
			LOGGER.log(Level.FINER, "[blocknet-blocking-client] Error while closing socket!");
			e.printStackTrace();
		}
	}

	@Override
	public synchronized void writeBytes(byte[] bytes) throws IOException {
		try {
			OutputStream out = socket.getOutputStream();
			out.write(bytes);
			out.flush();
		} catch (IOException e) {
			LOGGER.log(Level.FINER, "[blocknet-blocking-client] Error while writing bytes to socket!");
			e.printStackTrace();
			closeConnection();
			throw e;
		}
	}

	public SettableFuture<SocketAddress> getConnectFuture() {
		return connectFuture;
	}
}
