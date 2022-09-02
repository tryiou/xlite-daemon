package io.cloudchains.app.net.protocols.blocknet.listeners;

import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;

public interface BlocknetPeerConnectedEventListener {

	void onPeerConnected(BlocknetPeer peer, int peerCount);
}
