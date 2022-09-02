package io.cloudchains.app.net.protocols.blocknet.listeners;

import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import org.bitcoinj.core.Message;

public interface BlocknetPreMessageReceivedEventListener {

	Message onPreMessageReceived(BlocknetPeer peer, Message message);

}
