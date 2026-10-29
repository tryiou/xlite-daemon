package io.xlite.daemon.app.net.protocols.blocknet.listeners;

import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;

public interface BlocknetPeerDisconnectedEventListener {

    void onPeerDisconnected(BlocknetPeer peer, int peerCount);

}
