package io.cloudchains.app.net.protocols.blocknet.listeners;

import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;

public interface BlocknetPeerDisconnectedEventListener {

	void onPeerDisconnected(BlocknetPeer peer, int peerCount);

}
