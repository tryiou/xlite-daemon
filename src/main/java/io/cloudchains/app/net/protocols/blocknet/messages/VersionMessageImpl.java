package io.cloudchains.app.net.protocols.blocknet.messages;

import io.cloudchains.app.Version;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.VersionMessage;

public class VersionMessageImpl extends VersionMessage {
    private static final String LIBRARY_SUBVER = Version.SUBVERSION;

    public VersionMessageImpl(NetworkParameters params, int payload) throws ProtocolException {
        super(params, payload);
        subVer = LIBRARY_SUBVER;
    }
}
