package io.xlite.daemon.app.util;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.ECKey.ECDSASignature;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.SignatureDecodeException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptOpCodes;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Signing for coins whose transactions carry an nTime field.
 *
 * <p>Such a chain serializes {@code version + nTime + vin + vout + locktime}
 * and its legacy signature hash covers nTime: the chain's own
 * {@code CTransactionSignatureSerializer::Serialize} writes nVersion and then
 * nTime ahead of every input. bitcoinj has no nTime field, so
 * {@code addSignedInput} hashes a preimage the chain never computed and the
 * node rejects the signed transaction at script verification.
 *
 * <p>Byte-splicing nTime into the finished transaction (see
 * {@link TxTimeField#insertNTime}) is therefore not sufficient on its own: it
 * produces a correctly framed wire transaction carrying a signature over
 * different bytes. The nTime has to be fixed first and signed, and the same
 * value then spliced in.
 *
 * <p>Layout transcribed from the chain's serializer:
 * <pre>
 *   nVersion | nTime | inputs | outputs | nLockTime | nHashType
 * </pre>
 * with SIGHASH_SINGLE/NONE/ANYONECANPAY applied as the chain applies them,
 * including its constant-1 result for an out-of-range SIGHASH_SINGLE.
 */
public final class TxTimeFieldSigner {

    /** nHashType is an int32 on the wire. */
    private static final int WORD = 4;

    private TxTimeFieldSigner() {
    }

    /**
     * Attach and sign every input of a transaction destined for a time-field
     * chain.
     *
     * <p>All inputs are attached <em>before</em> any signature is computed,
     * which is forced by the chain's rules and not merely tidy: the preimage
     * covers every input, so attaching a later input invalidates every
     * signature already made. Signing one input at a time therefore yields a
     * transaction whose earlier inputs no longer verify.
     *
     * @param keys      one key per input, in input order
     * @param outpoints one outpoint per input, in input order
     * @param scripts   the spent output's script per input, in input order --
     *                  the script the chain substitutes for that input
     * @param sigHash   sighash flags, applied to every input
     * @param nTime     the nTime to sign, and the one the caller must later
     *                  splice into the wire bytes
     */
    public static void addSignedInputs(NetworkParameters params, Transaction tx,
            List<ECKey> keys, List<TransactionOutPoint> outpoints, List<Script> scripts,
            Transaction.SigHash sigHash, long nTime) {
        requireSameSize(keys, outpoints, scripts);
        if (keys.isEmpty())
            throw new IllegalArgumentException("no inputs to sign");
        if (nTime < 0 || nTime > 0xFFFFFFFFL)
            throw new IllegalArgumentException("nTime out of uint32 range: " + nTime);

        int hashType = sigHash.value;

        // Attach every input first, with an empty script, so each preimage is
        // computed against the finished input set.
        for (TransactionOutPoint outpoint : outpoints)
            tx.addInput(new TransactionInput(params, tx, new byte[0], outpoint));

        for (int nIn = 0; nIn < keys.size(); nIn++) {
            Sha256Hash digest = chainSighash(tx, scripts.get(nIn), nIn, hashType, nTime);
            Script scriptSig = buildScriptSig(keys.get(nIn), digest, hashType);
            tx.getInputs().get(nIn).setScriptSig(scriptSig);
        }
    }

    /**
     * Signature plus pubkey, the scriptSig a P2PKH input expects, with the
     * sighash flag byte appended as the chain's checker reads it.
     */
    private static Script buildScriptSig(ECKey key, Sha256Hash digest, int hashType) {
        ECDSASignature signature = key.sign(digest);
        if (signature == null)
            throw new IllegalStateException("signing produced no signature");
        byte[] der = signature.encodeToDER();
        byte[] encoded = new byte[der.length + 1];
        System.arraycopy(der, 0, encoded, 0, der.length);
        encoded[der.length] = (byte) hashType;
        return new ScriptBuilder()
                .data(encoded)
                .data(key.getPubKey())
                .build();
    }

    private static void requireSameSize(List<ECKey> keys, List<TransactionOutPoint> outpoints,
            List<Script> scripts) {
        if (keys.size() != outpoints.size() || keys.size() != scripts.size())
            throw new IllegalArgumentException(
                    "one key, outpoint and script per input required: "
                            + keys.size() + "/" + outpoints.size() + "/" + scripts.size());
    }

    /**
     * The chain's signature hash: double-SHA256 over
     * {@code version + nTime + inputs + outputs + locktime + hashtype}.
     *
     * <p>The out-of-range SIGHASH_SINGLE case is checked before the input
     * index, because the chain evaluates it as a signature bug and hashes the
     * constant one without ever consulting the inputs. Reordering these two
     * checks would turn a chain-defined signature into a thrown error.
     */
    public static Sha256Hash chainSighash(Transaction tx, Script scriptCode, int nIn,
            int hashType, long nTime) {
        boolean anyoneCanPay = (hashType & 0x80) != 0;
        boolean hashSingle = (hashType & 0x1f) == SIGHASH_SINGLE;
        boolean hashNone = (hashType & 0x1f) == SIGHASH_NONE;

        // The chain treats SIGHASH_SINGLE with an out-of-range input as a
        // signature bug and hashes the constant 1 rather than failing. Match it
        // exactly, so a transaction signed here reaches the same verdict the
        // node reaches instead of a different one.
        if (hashSingle && nIn >= tx.getOutputs().size()) {
            // The chain's constant is uint256(1) and the set byte is the FIRST
            // one. base_blob::SetHex (uint256.cpp) walks the hex string backward
            // starting at data[0], so uint256S("...0001") yields 0100...00, and
            // secp256k1 hashes from hash.begin() -- that is, the first byte.
            // bitcoinj independently returns Sha256Hash.wrap("0100...00") for
            // this same case in calculateSignatureHash, confirming the layout.
            // A one in the last byte (2^248-as-little-endian) would hash
            // differently; zero and one both yield a signature the chain
            // rejects, which is the point, but only this ordering is the
            // constant the chain actually hashes.
            byte[] one = new byte[32];
            one[0] = 1;
            return Sha256Hash.wrap(one);
        }
        if (tx.getInputs().isEmpty())
            throw new IllegalArgumentException("transaction has no inputs to sign");
        if (nIn < 0 || nIn >= tx.getInputs().size())
            throw new IllegalArgumentException(
                    "input index " + nIn + " outside transaction of "
                            + tx.getInputs().size() + " inputs");

        return Sha256Hash.wrap(Sha256Hash.hashTwice(preimageBytes(tx, scriptCode, nIn, hashType, nTime)));
    }

    /**
     * The raw preimage bytes that {@link #chainSighash} hashes.
     *
     * <p>Package-private for testing: the sighash is a hash, so a test that
     * only sees the digest cannot say <em>which</em> bytes were hashed. These
     * bytes are the contract -- the chain hashes exactly this sequence -- so
     * the tests pin fragments of it (the null-output value, the stripped
     * script, the nTime position) rather than re-transcribing the layout.
     */
    static byte[] preimageBytes(Transaction tx, Script scriptCode, int nIn,
            int hashType, long nTime) {
        boolean anyoneCanPay = (hashType & 0x80) != 0;
        boolean hashSingle = (hashType & 0x1f) == SIGHASH_SINGLE;
        boolean hashNone = (hashType & 0x1f) == SIGHASH_NONE;

        if (tx.getInputs().isEmpty())
            throw new IllegalArgumentException("transaction has no inputs to sign");
        if (nIn < 0 || nIn >= tx.getInputs().size())
            throw new IllegalArgumentException(
                    "input index " + nIn + " outside transaction of "
                            + tx.getInputs().size() + " inputs");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt32(out, (int) tx.getVersion());
        writeUInt32(out, nTime);

        int inputCount = anyoneCanPay ? 1 : tx.getInputs().size();
        writeCompactSize(out, inputCount);
        for (int i = 0; i < inputCount; i++) {
            int index = anyoneCanPay ? nIn : i;
            TransactionInput in = tx.getInputs().get(index);
            writeOutPoint(out, in.getOutpoint());
            // The chain blanks the other inputs' scripts, and zeroes their
            // sequence only under SINGLE/NONE.
            writeScript(out, index == nIn ? scriptCode : new ScriptBuilder().build());
            writeUInt32(out, (index != nIn && (hashSingle || hashNone)) ? 0
                    : in.getSequenceNumber());
        }

        int outputCount = hashNone ? 0 : (hashSingle ? nIn + 1 : tx.getOutputs().size());
        writeCompactSize(out, outputCount);
        for (int i = 0; i < outputCount; i++) {
            if (hashSingle && i != nIn) {
                // The chain serializes a default-constructed CTxOut for these,
                // and that constructor calls SetNull(), which sets nValue to -1
                // rather than 0. The preimage therefore carries 0xffff...ff
                // here; writing 0 would produce a signature the node rejects.
                // bitcoinj's own sighash does the same via
                // Coin.NEGATIVE_SATOSHI, whose value is -1.
                writeUInt64(out, -1L);
                writeScript(out, new ScriptBuilder().build());
            } else {
                TransactionOutput o = tx.getOutputs().get(i);
                writeUInt64(out, o.getValue().value);
                writeScript(out, o.getScriptPubKey());
            }
        }

        writeUInt32(out, tx.getLockTime());
        writeInt32(out, hashType);
        return out.toByteArray();
    }

    /**
     * Whether a P2PKH scriptSig carries a valid signature for {@code hash}.
     *
     * <p>Any shape other than a signature push followed by a pubkey push is
     * not a signature this signer produced, so it reports false rather than
     * throwing. Used by the signature tests; the send path does not call it.
     */
    public static boolean verify(Script scriptSig, Sha256Hash hash) {
        if (scriptSig == null || hash == null)
            return false;
        List<ScriptChunk> chunks = scriptSig.getChunks();
        if (chunks.size() < 2)
            return false;
        // A non-push chunk (an opcode, say) carries no data at all.
        if (!chunks.get(0).isPushData() || !chunks.get(1).isPushData())
            return false;
        byte[] signature = chunks.get(0).data;
        byte[] pubkey = chunks.get(1).data;
        if (signature == null || pubkey == null || signature.length < 2)
            return false;
        byte[] der = new byte[signature.length - 1];
        System.arraycopy(signature, 0, der, 0, der.length);
        try {
            ECDSASignature parsed = ECDSASignature.decodeFromDER(der);
            if (!parsed.isCanonical())
                return false;
            return ECKey.fromPublicOnly(pubkey).verify(hash, parsed);
        } catch (SignatureDecodeException e) {
            return false;
        }
    }

    private static final int SIGHASH_SINGLE = 3;
    private static final int SIGHASH_NONE = 2;

    private static void writeInt32(ByteArrayOutputStream out, int v) {
        out.writeBytes(ByteBuffer.allocate(WORD).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
    }

    private static void writeUInt32(ByteArrayOutputStream out, long v) {
        out.writeBytes(ByteBuffer.allocate(WORD).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v).array());
    }

    private static void writeUInt64(ByteArrayOutputStream out, long v) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array());
    }

    /** Canonical variable-length size prefix, as the wire format specifies. */
    private static void writeCompactSize(ByteArrayOutputStream out, long v) {
        if (v < 253) {
            out.write((int) v);
        } else if (v <= 0xFFFFL) {
            out.write(253);
            writeUInt16(out, v);
        } else if (v <= 0xFFFFFFFFL) {
            out.write(254);
            writeUInt32(out, v);
        } else {
            // No 0xFF/uint64 arm: input/output counts and script lengths can
            // never reach 2^32 in a real transaction. Emitting truncated bytes
            // here would produce a signature over a preimage the node never
            // computes, so fail loudly instead of guessing.
            throw new IllegalArgumentException(
                    "compact size " + v + " exceeds uint32 range");
        }
    }

    private static void writeUInt16(ByteArrayOutputStream out, long v) {
        // Little-endian, matching the wire format and bitcoinj's
        // Utils.uint16ToByteArrayLE: 300 (0x012C) encodes as 2C 01, not 01 2C.
        out.write((int) (v & 0xff));
        out.write((int) ((v >> 8) & 0xff));
    }

    private static void writeScript(ByteArrayOutputStream out, Script script) {
        // The chain's SerializeScriptCode counts and strips OP_CODESEPARATOR
        // before hashing, and bitcoinj's own sighash does the same
        // (Transaction.calculateSignatureHash). No-op for P2PKH, but a P2SH
        // output carrying the opcode would otherwise hash over bytes the node
        // never hashes, and the signature would be rejected.
        byte[] program = Script.removeAllInstancesOfOp(
                script.getProgram(), ScriptOpCodes.OP_CODESEPARATOR);
        writeCompactSize(out, program.length);
        out.writeBytes(program);
    }

    private static void writeOutPoint(ByteArrayOutputStream out, TransactionOutPoint outPoint) {
        byte[] hash = outPoint.getHash().getBytes();
        for (int i = hash.length - 1; i >= 0; i--)
            out.write(hash[i]);
        writeUInt32(out, outPoint.getIndex());
    }
}
