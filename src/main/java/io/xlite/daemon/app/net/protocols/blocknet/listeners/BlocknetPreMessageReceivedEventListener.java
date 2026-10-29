package io.xlite.daemon.app.net.protocols.blocknet.listeners;

import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import org.bitcoinj.core.Message;

public interface BlocknetPreMessageReceivedEventListener {

    Message onPreMessageReceived(BlocknetPeer peer, Message message);

}
