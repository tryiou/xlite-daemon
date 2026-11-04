package io.xlite.daemon.app.net.electrumx;

/** The indexer answered with a JSON-RPC error. */
public final class ElectrumException extends RuntimeException {

    private final int code;

    public ElectrumException(int code, String message) {
        super("electrum error " + code + ": " + message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
