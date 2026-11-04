package io.xlite.daemon.app.net.api.http.server;

import org.bitcoinj.script.Script;

/** Script and address presentation shared by the RPC methods. */
final class BitcoinScripts {

    private BitcoinScripts() {}

    /**
     * The script asm spelling Bitcoin Core uses. bitcoinj prints {@code DUP HASH160 ...}, bitcoinj
     * callers expect {@code OP_DUP}, and {@code PUSHDATA(n)}/brackets are Core's decompiler
     * notation rather than part of the addressable script.
     */
    static String canonicalizeAsm(String asm) {
        return asm.replaceAll("DUP", "OP_DUP")
                .replaceAll("HASH160", "OP_HASH160")
                .replaceAll("EQUALVERIFY", "OP_EQUALVERIFY")
                .replaceAll("CHECKSIG", "OP_CHECKSIG")
                .replaceAll("RETURN", "OP_RETURN")
                .replaceAll("PUSHDATA", "")
                .replaceAll("\\[", "")
                .replaceAll("]", "")
                .replaceAll("\\([0-9]+\\)", "");
    }

    /** The script type name the RPC surface uses. */
    static String typeName(Script.ScriptType type) {
        if (type == Script.ScriptType.P2PKH) {
            return "pubkeyhash";
        }
        if (type == Script.ScriptType.P2SH) {
            return "scripthash";
        }
        return "unknown";
    }
}
