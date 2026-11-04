package io.xlite.daemon.app.net.electrumx;

/**
 * One entry of an ElectrumX history: the transaction and the height it was mined at (0=mempool).
 */
record TxRef(String txid, int height) {}
