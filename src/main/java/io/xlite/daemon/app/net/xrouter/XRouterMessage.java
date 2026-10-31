package io.xlite.daemon.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import io.xlite.daemon.app.util.XRouterConfiguration;

import org.bitcoinj.core.Message;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class XRouterMessage extends Message {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private BlocknetPeer blocknetPeer;

    private XRouterPacketHeader xRouterHeader;
    private byte[] data;

    private HashMap<String, Object> parsedData;

    public XRouterMessage(byte[] data) {
        this.data = data;

        this.parsedData = new HashMap<>();
        parseHeader();
        parse();
    }

    XRouterMessage(
            BlocknetPeer blocknetPeer,
            XRouterPacketHeader xRouterHeader,
            Map<String, Object> parsedData) {
        this.blocknetPeer = blocknetPeer;

        this.xRouterHeader = xRouterHeader;
        this.parsedData = new HashMap<>(parsedData);

        this.data = bitcoinSerialize();
    }

    private void writeCurrency(HashMap<String, Object> body, OutputStream out) throws IOException {
        out.write(((String) body.get("currency")).getBytes(StandardCharsets.UTF_8));
        out.write(0x00);
    }

    private void writeAccountAndNumber(String account, String number, OutputStream out)
            throws IOException {
        out.write(account.getBytes(StandardCharsets.UTF_8));
        out.write(0x00);
        out.write(number.getBytes(StandardCharsets.UTF_8));
        out.write(0x00);
    }

    /**
     * Write Payment TX to unserialized XRouter Packet
     *
     * @param body the parsed XRouter packet body
     * @param out the stream the serialized transaction info is written to
     * @throws IOException If writing fails or another error occurs
     */
    private void writePaymentTx(HashMap<String, Object> body, OutputStream out) throws IOException {
        if (!body.containsKey("paymentTx")) {
            return;
        }

        out.write(((String) body.get("paymentTx")).getBytes(StandardCharsets.UTF_8));
        out.write(0x00);
    }

    public XRouterPacketHeader getXRouterHeader() {
        return xRouterHeader;
    }

    /** A read-only view of the fields parsed from this packet. */
    public Map<String, Object> getParsedData() {
        return Collections.unmodifiableMap(parsedData);
    }

    @Override
    public byte[] bitcoinSerialize() {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try {
            bitcoinSerializeToStream(byteArrayOutputStream);
        } catch (Exception e) {
            LOGGER.warning("[xrouter] Error serializing XRouter packet" + e.getMessage());
            return null;
        }

        return byteArrayOutputStream.toByteArray();
    }

    @Override
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
        writeHeader(stream);
        writeCommandPayload(stream);
    }

    private void writeHeader(OutputStream stream) throws IOException {
        if (xRouterHeader.getExtSize() < 253) {
            stream.write(xRouterHeader.getCompactSizeBytes());
        } else if (xRouterHeader.getExtSize() <= 65535) {
            stream.write((byte) 253);
            stream.write(xRouterHeader.getCompactSizeBytes());
        } else {
            stream.write((byte) 254);
            stream.write(xRouterHeader.getCompactSizeBytes());
        }

        Utils.uint32ToByteStreamLE(xRouterHeader.getVersion(), stream);
        Utils.uint32ToByteStreamLE(xRouterHeader.getCommand(), stream);
        Utils.uint32ToByteStreamLE(xRouterHeader.getTimestamp(), stream);
        Utils.uint32ToByteStreamLE(xRouterHeader.getSize(), stream);
        stream.write(new byte[8]);
        stream.write(xRouterHeader.getUUID().getBytes(StandardCharsets.UTF_8));
        stream.write(xRouterHeader.getPubkey());
        stream.write(xRouterHeader.getSignature());
    }

    private void writeCommandPayload(OutputStream stream) throws IOException {
        String command = XRouterCommandUtils.commandIdToString(xRouterHeader.getCommand());
        if (writeReplyPayload(command, stream)) {
            return;
        }
        if (writeBlockPayload(command, stream)) {
            return;
        }
        if (writeRangePayload(command, stream)) {
            return;
        }
        writeServicePayload(command, stream);
    }

    private boolean writeReplyPayload(String command, OutputStream stream) throws IOException {
        switch (command) {
            case "xrReply", "xrConfigReply" -> writeField(stream, "reply");
            case "xrGetReply" ->
                    LOGGER.finer(
                            "[xrouter-message] DEBUG: Fetching reply for packet "
                                    + xRouterHeader.getUUID());
            case "xrGetConfig" -> writeField(stream, "addr");
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean writeBlockPayload(String command, OutputStream stream) throws IOException {
        switch (command) {
            case "xrGetBlockCount" -> {
                writeCurrency(parsedData, stream);
                writePaymentTx(parsedData, stream);
                Utils.uint32ToByteStreamLE(0, stream); // param count
            }
            case "xrGetBlockHash" -> {
                writeCurrencyAndPaymentTx(stream);
                Utils.uint32ToByteStreamLE(1, stream); // param count
                writeField(stream, "blockId");
            }
            case "xrGetBlock" -> {
                writeCurrencyAndPaymentTx(stream);
                Utils.uint32ToByteStreamLE(1, stream); // param count
                writeField(stream, "blockHash");
            }
            case "xrGetTransaction" -> {
                writeCurrencyAndPaymentTx(stream);
                Utils.uint32ToByteStreamLE(1, stream); // param count
                writeField(stream, "txid");
            }
            case "xrSendTransaction" -> {
                writeCurrencyAndPaymentTx(stream);
                Utils.uint32ToByteStreamLE(1, stream); // param count
                writeField(stream, "transaction");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean writeRangePayload(String command, OutputStream stream) throws IOException {
        switch (command) {
            case "xrGetTxBloomFilter" -> {
                writeCurrency(parsedData, stream);
                writePaymentTx(parsedData, stream);
                writeField(stream, "numberString");
            }
            case "xrGenerateBloomFilter" ->
                    LOGGER.finer(
                            "[xrouter-message] ERROR: Attempted to serialize unsupported command"
                                    + " 41.");
            case "xrGetBlocks" -> {
                writeCurrency(parsedData, stream);
                writePaymentTx(parsedData, stream);
                writeAccountAndNumber(
                        (String) parsedData.get("account"),
                        (String) parsedData.get("number"),
                        stream);
            }
            case "xrGetTransactions", "xrGetBalanceUpdate" -> {
                writeCurrency(parsedData, stream);
                writePaymentTx(parsedData, stream);
                writeAccountAndNumber(
                        (String) parsedData.get("account"),
                        (String) parsedData.get("numberString"),
                        stream);
            }
            case "xrGetBlockAtTime" ->
                    LOGGER.finer(
                            "[xrouter-message] ERROR: Attempted to serialize unsupported command"
                                    + " 52.");
            // OBSOLETE, only implemented for backwards compatibility
            case "xrGetBalance" -> {
                writeCurrency(parsedData, stream);
                writePaymentTx(parsedData, stream);
                writeField(stream, "account");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void writeServicePayload(String command, OutputStream stream) throws IOException {
        if (!"xrService".equals(command)) {
            // xrInvalid: nothing to write.
            return;
        }
        String xrServiceCommand = (String) parsedData.get("command");
        LOGGER.finer("[xrService] Command: " + xrServiceCommand);

        XRouterConfiguration.XRouterPluginConfiguration pluginConfig =
                blocknetPeer.getPluginConfig(xrServiceCommand);
        if (pluginConfig == null) {
            LOGGER.finer("[xrService] ERROR: Unsupported server xrs plugin: " + xrServiceCommand);
            LOGGER.finer("[xrService] ERROR: Aborting transmission.");
            throw new IllegalArgumentException(
                    "Unsupported server xrs plugin: " + xrServiceCommand);
        }

        stream.write(xrServiceCommand.getBytes(StandardCharsets.UTF_8));
        stream.write(0x00);
        writePaymentTx(parsedData, stream);

        List<?> params = (List<?>) parsedData.get("params");
        Utils.uint32ToByteStreamLE(params.size(), stream); // param count
        for (int index = 0; index < params.size(); index++) {
            writeServiceParam(
                    stream, index, pluginConfig.getParamTypes().get(index), params.get(index));
        }
    }

    private void writeServiceParam(OutputStream stream, int index, Class paramClass, Object param)
            throws IOException {
        String classStr = XRouterConfiguration.getStringByClass(paramClass);
        if (!(param instanceof String
                && (((String) param).equalsIgnoreCase("true")
                        || ((String) param).equalsIgnoreCase("false")))) {
            Preconditions.checkState(
                    paramClass.isInstance(param),
                    "Supplied parameter at index "
                            + index
                            + " is not of type '"
                            + classStr
                            + "'. Aborting transmission.");
        }
        LOGGER.finer("[xrService] DEBUG: Parameter " + index + " is of type " + classStr);

        switch (classStr) {
            case "string" -> writeNulTerminated(stream, (String) param);
            case "bool" ->
                    Utils.uint32ToByteStreamLE(
                            ((String) param).equalsIgnoreCase("true") ? 1 : 0, stream);
            case "int" -> Utils.uint32ToByteStreamLE((Integer) param, stream);
            default -> {
                LOGGER.finer(
                        "[xrService] ERROR: Encountered unhandled parameter of type "
                                + classStr
                                + ". Aborting transmission.");
                throw new IllegalStateException(
                        "Bad parameter type at index " + index + ": " + classStr);
            }
        }
    }

    private void writeCurrencyAndPaymentTx(OutputStream stream) throws IOException {
        writeCurrency(parsedData, stream);
        writePaymentTx(parsedData, stream);
    }

    private void writeField(OutputStream stream, String key) throws IOException {
        writeNulTerminated(stream, (String) parsedData.get(key));
    }

    private static void writeNulTerminated(OutputStream stream, String value) throws IOException {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
        stream.write(0x00);
    }

    private String readStringNT(ByteBuffer in) {
        StringBuilder stringBuilder = new StringBuilder();

        byte buf;

        while ((buf = in.get()) != 0x00) {
            stringBuilder.append((char) buf);
        }

        return stringBuilder.toString();
    }

    private void readCurrency(ByteBuffer buf) {
        String currency = readStringNT(buf);
        parsedData.put("currency", currency);
    }

    private void readPaymentTx(ByteBuffer buf) {
        String paymentTx = readStringNT(buf);
        parsedData.put("paymentTx", paymentTx);
    }

    private void readAccountAndNumber(ByteBuffer buf, String secondFieldName) {
        String account = readStringNT(buf);
        parsedData.put("account", account);
        offset += account.length();

        String number = readStringNT(buf);
        parsedData.put(secondFieldName, number);
        offset += number.length();
    }

    private void parseHeader() throws ProtocolException {
        xRouterHeader = new XRouterPacketHeader(ByteBuffer.wrap(data));
    }

    @Override
    protected void parse() throws ProtocolException {
        parsedData.put("header", xRouterHeader);

        LOGGER.finer(
                "Received raw XRouter packet: "
                        + new String(Hex.encode(data), StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.position(xRouterHeader.getHeaderLength());

        String command = XRouterCommandUtils.commandIdToString(xRouterHeader.getCommand());
        if (parseReply(command, buf)) {
            return;
        }
        if (parseBlockRequest(command, buf)) {
            return;
        }
        if (parseRangeRequest(command, buf)) {
            return;
        }
        parseService(command, buf);
    }

    private boolean parseReply(String command, ByteBuffer buf) {
        switch (command) {
            case "xrReply", "xrConfigReply" -> {
                String reply = readStringNT(buf);
                parsedData.put("reply", reply);
                LOGGER.finer(
                        "[xrouter-message] Got reply: '"
                                + reply
                                + "' for packet with UUID '"
                                + xRouterHeader.getUUID()
                                + "'");
            }
            case "xrGetReply" ->
                    LOGGER.finer(
                            "[xrouter-message] WARNING: Server asked to fetch reply, but we aren't"
                                    + " a server.");
            case "xrGetConfig" ->
                    LOGGER.finer(
                            "[xrouter-message] WARNING: Server asked us for config, but we aren't"
                                    + " a servicenode.");
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean parseBlockRequest(String command, ByteBuffer buf) {
        switch (command) {
            case "xrGetBlockCount" -> {
                readCurrency(buf);
                readPaymentTx(buf);
            }
            case "xrGetBlockHash" -> {
                readCurrency(buf);
                parsedData.put("blockId", readStringNT(buf));
                readPaymentTx(buf);
            }
            case "xrGetBlock" -> {
                readCurrency(buf);
                parsedData.put("blockHash", readStringNT(buf));
                readPaymentTx(buf);
            }
            case "xrGetTransaction" -> {
                readCurrency(buf);
                parsedData.put("txid", readStringNT(buf));
                readPaymentTx(buf);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean parseRangeRequest(String command, ByteBuffer buf) {
        switch (command) {
            case "xrGetBlocks" -> {
                readCurrency(buf);
                readPaymentTx(buf);
                readAccountAndNumber(buf, "number");
            }
            case "xrGetTransactions", "xrGetBalanceUpdate" -> {
                readCurrency(buf);
                readPaymentTx(buf);
                readAccountAndNumber(buf, "numberString");
            }
            // OBSOLETE
            case "xrGetBalance" -> {
                readCurrency(buf);
                parsedData.put("account", readStringNT(buf));
                readPaymentTx(buf);
            }
            case "xrGetTxFilter" -> {
                readCurrency(buf);
                readPaymentTx(buf);
                parsedData.put("numberString", readStringNT(buf));
            }
            case "xrSendTransaction" -> {
                readCurrency(buf);
                readPaymentTx(buf);
                parsedData.put("transaction", readStringNT(buf));
            }
            case "xrGetBlockAtTime" -> {
                readCurrency(buf);
                parsedData.put("timestamp", readStringNT(buf));
                readPaymentTx(buf);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean parseService(String command, ByteBuffer buf) {
        if (!"xrService".equals(command)) {
            // xbcInvalid: nothing to read.
            return false;
        }
        parsedData.put("command", readStringNT(buf));

        ArrayList<String> params = new ArrayList<>();
        while (buf.position() < buf.capacity()) {
            params.add(readStringNT(buf));
        }
        parsedData.put("params", params);
        return true;
    }
}
