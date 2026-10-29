package io.xlite.daemon.app.net.protocols.blocknet.listeners;

import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;

public interface BlocknetPeerConnectedEventListener {

    void onPeerConnected(BlocknetPeer peer, int peerCount);
}
