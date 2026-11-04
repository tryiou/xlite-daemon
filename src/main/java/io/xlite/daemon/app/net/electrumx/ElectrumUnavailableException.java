package io.xlite.daemon.app.net.electrumx;

/** The native connection is down or the request could not be delivered. */
public final class ElectrumUnavailableException extends RuntimeException {

    public ElectrumUnavailableException(String message) {
        super(message);
    }
}
