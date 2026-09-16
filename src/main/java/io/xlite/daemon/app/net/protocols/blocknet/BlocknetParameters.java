package io.xlite.daemon.app.net.protocols.blocknet;

import io.xlite.daemon.app.net.xrouter.XRouterMessageSerializer;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;

public abstract class BlocknetParameters extends NetworkParameters {

    public abstract XRouterMessageSerializer getXRouterMessageSerializer(boolean parseRetain);

    public abstract Sha256Hash getGenesisBlockHash();

    @Override
    public abstract BlocknetSerializer getSerializer(boolean parseRetain);
}
