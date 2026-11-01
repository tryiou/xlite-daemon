package io.xlite.daemon.app.util;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Signing for chains that require SIGHASH_FORKID (Bitcoin Cash).
 *
 * <p>Bitcoin Cash replaced the legacy signature hash with a BIP143-shaped preimage and requires the
 * signature's hash-type byte to carry {@code SIGHASH_FORKID} (0x40). A legacy signature is rejected
 * at script verification with {@code MUST_USE_FORKID} (BCHN {@code src/script/sigencoding.cpp}).
 *
 * <p>The preimage, for one input of a transaction signed with {@code SIGHASH_ALL|SIGHASH_FORKID},
 * is, little-endian where numeric:
 *
 * <pre>
 *   nVersion | hashPrevouts | hashSequence | outpoint | scriptCode | amount
 *            | nSequence | hashOutputs | nLockTime | nHashType
 * </pre>
 *
 * where {@code hashPrevouts}, {@code hashSequence} and {@code hashOutputs} are double-SHA256 over
 * every input's outpoint, every input's sequence and every output respectively. Unlike the legacy
 * hash, the spent output's value is part of the signed bytes, so callers must supply it.
 *
 * <p>Only {@code SIGHASH_ALL} without {@code ANYONECANPAY} is implemented: that is the only policy
 * the wallet signs with, and the other combinations have different preimages (SINGLE hashes one
 * output and has a constant-1 result for an out-of-range input; NONE zeroes the sequence and output
 * hashes). An unsupported type is refused rather than signed with the wrong bytes.
 */
public final class ForkIdSigner {

    /** The signature hash flag Bitcoin Cash requires (BCHN src/script/sighashtype.h). */
    public static final int SIGHASH_FORKID = 0x40;

    private static final int SIGHASH_ALL = 1;
    private static final int ANYONECANPAY = 0x80;
    private static final int WORD = 4;

    /**
     * One input's position, spent value and hash type, as the preimage is computed for it.
     *
     * @param inputIndex index into the transaction's inputs
     * @param amount the value of the output this input spends
     * @param hashType the full 32-bit hash type, including {@code SIGHASH_FORKID}
     */
    public record SighashRequest(int inputIndex, Coin amount, int hashType) {}

    private ForkIdSigner() {}

    /**
     * One key, outpoint, spent script and spent value per input, in input order.
     *
     * @param scripts the spent output's script per input
     * @param amounts the spent output's value per input, which the preimage commits to
     */
    public record InputsToSign(
            List<ECKey> keys,
            List<TransactionOutPoint> outpoints,
            List<Script> scripts,
            List<Coin> amounts) {

        public InputsToSign {
            keys = List.copyOf(keys);
            outpoints = List.copyOf(outpoints);
            scripts = List.copyOf(scripts);
            amounts = List.copyOf(amounts);
        }
    }

    /**
     * Attach and sign every input of a transaction destined for a ForkID chain.
     *
     * <p>All inputs are attached before any signature is computed. The preimage covers the complete
     * outpoint/sequence/output sets, so signing an incomplete transaction would commit to a
     * different set than the one broadcast.
     *
     * @param params network parameters for the attached inputs
     * @param transaction the transaction to sign
     * @param inputs one key, outpoint, script and value per input
     */
    public static void addSignedInputs(
            NetworkParameters params, Transaction transaction, InputsToSign inputs) {
        requireSameSize(inputs);
        if (inputs.keys().isEmpty()) {
            throw new IllegalArgumentException("no inputs to sign");
        }
        int hashType = SIGHASH_ALL | SIGHASH_FORKID;
        for (TransactionOutPoint outpoint : inputs.outpoints()) {
            transaction.addInput(new TransactionInput(params, transaction, new byte[0], outpoint));
        }
        for (int index = 0; index < inputs.keys().size(); index++) {
            Sha256Hash digest =
                    sighash(
                            transaction,
                            inputs.scripts().get(index),
                            new SighashRequest(index, inputs.amounts().get(index), hashType));
            transaction
                    .getInputs()
                    .get(index)
                    .setScriptSig(buildScriptSig(inputs.keys().get(index), digest, hashType));
        }
    }

    /**
     * The Bitcoin Cash signature hash for one input.
     *
     * @param hashType the full 32-bit hash type, including {@code SIGHASH_FORKID}; it is serialized
     *     into the preimage exactly as given
     * @throws IllegalArgumentException for any type other than {@code SIGHASH_ALL} without {@code
     *     ANYONECANPAY}
     */
    public static Sha256Hash sighash(
            Transaction transaction, Script scriptCode, SighashRequest request) {
        int hashType = request.hashType();
        if ((hashType & SIGHASH_FORKID) == 0
                || (hashType & 0x1f) != SIGHASH_ALL
                || (hashType & ANYONECANPAY) != 0) {
            throw new IllegalArgumentException(
                    "only SIGHASH_ALL|SIGHASH_FORKID without ANYONECANPAY is supported, got "
                            + hashType);
        }
        checkInputExists(transaction, request.inputIndex());
        return Sha256Hash.wrap(
                Sha256Hash.hashTwice(preimageBytes(transaction, scriptCode, request)));
    }

    /**
     * The raw preimage bytes that {@link #sighash} hashes.
     *
     * <p>Package-private for testing: the sighash is a hash, so a test that only sees the digest
     * cannot say which bytes were hashed. The pinned vectors compare the digest against BCHN's own
     * recorded values, which is the stronger check.
     */
    static byte[] preimageBytes(
            Transaction transaction, Script scriptCode, SighashRequest request) {
        int inputIndex = request.inputIndex();
        checkInputExists(transaction, inputIndex);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt32(out, (int) transaction.getVersion());
        out.writeBytes(hashPrevouts(transaction));
        out.writeBytes(hashSequences(transaction));
        out.writeBytes(transaction.getInputs().get(inputIndex).getOutpoint().bitcoinSerialize());
        writeScriptCode(out, scriptCode);
        writeUInt64(out, request.amount().getValue());
        writeUInt32(out, transaction.getInputs().get(inputIndex).getSequenceNumber());
        out.writeBytes(hashOutputs(transaction));
        writeUInt32(out, transaction.getLockTime());
        writeUInt32(out, request.hashType());
        return out.toByteArray();
    }

    private static byte[] hashPrevouts(Transaction transaction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TransactionInput input : transaction.getInputs()) {
            out.writeBytes(input.getOutpoint().bitcoinSerialize());
        }
        return Sha256Hash.hashTwice(out.toByteArray());
    }

    private static byte[] hashSequences(Transaction transaction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TransactionInput input : transaction.getInputs()) {
            writeUInt32(out, input.getSequenceNumber());
        }
        return Sha256Hash.hashTwice(out.toByteArray());
    }

    private static byte[] hashOutputs(Transaction transaction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TransactionOutput output : transaction.getOutputs()) {
            out.writeBytes(output.bitcoinSerialize());
        }
        return Sha256Hash.hashTwice(out.toByteArray());
    }

    /** Signature plus pubkey, with the fork flag byte appended as the checker reads it. */
    private static Script buildScriptSig(ECKey key, Sha256Hash digest, int hashType) {
        ECKey.ECDSASignature signature = key.sign(digest);
        if (signature == null) {
            throw new IllegalStateException("signing produced no signature");
        }
        byte[] der = signature.encodeToDER();
        byte[] encoded = new byte[der.length + 1];
        System.arraycopy(der, 0, encoded, 0, der.length);
        encoded[der.length] = (byte) hashType;
        return new ScriptBuilder().data(encoded).data(key.getPubKey()).build();
    }

    private static void requireSameSize(InputsToSign inputs) {
        if (inputs.keys().size() != inputs.outpoints().size()
                || inputs.keys().size() != inputs.scripts().size()
                || inputs.keys().size() != inputs.amounts().size()) {
            throw new IllegalArgumentException(
                    "one key, outpoint, script and amount per input required: "
                            + inputs.keys().size()
                            + "/"
                            + inputs.outpoints().size()
                            + "/"
                            + inputs.scripts().size()
                            + "/"
                            + inputs.amounts().size());
        }
    }

    private static void checkInputExists(Transaction transaction, int inputIndex) {
        if (transaction.getInputs().isEmpty()) {
            throw new IllegalArgumentException("transaction has no inputs to sign");
        }
        if (inputIndex < 0 || inputIndex >= transaction.getInputs().size()) {
            throw new IllegalArgumentException(
                    "input index "
                            + inputIndex
                            + " outside transaction of "
                            + transaction.getInputs().size()
                            + " inputs");
        }
    }

    private static void writeScriptCode(ByteArrayOutputStream out, Script script) {
        // The ForkID preimage commits to scriptCode verbatim: BCHN's SignatureHash writes
        // WriteCompactSize(scriptCode.size()) followed by the bytes, with no OP_CODESEPARATOR
        // stripping. The legacy hash does strip it, so copying that behaviour here would sign a
        // preimage the node never computes.
        byte[] program = script.getProgram();
        writeCompactSize(out, program.length);
        out.writeBytes(program);
    }

    private static void writeInt32(ByteArrayOutputStream out, int v) {
        out.writeBytes(ByteBuffer.allocate(WORD).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
    }

    private static void writeUInt32(ByteArrayOutputStream out, long v) {
        out.writeBytes(
                ByteBuffer.allocate(WORD).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v).array());
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
            out.write((int) (v & 0xff));
            out.write((int) ((v >> 8) & 0xff));
        } else if (v <= 0xFFFFFFFFL) {
            out.write(254);
            writeUInt32(out, v);
        } else {
            throw new IllegalArgumentException("compact size " + v + " exceeds uint32 range");
        }
    }
}
