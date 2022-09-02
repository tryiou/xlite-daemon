package io.cloudchains.app.net.protocols.blocknet.listeners;

import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.FilteredBlock;

public interface BlocknetOnBlocksDownloadedEventListener {

	void onBlocksDownloaded(BlocknetPeer peer, Block block, FilteredBlock filteredBlock, int blocksLeft);

}
