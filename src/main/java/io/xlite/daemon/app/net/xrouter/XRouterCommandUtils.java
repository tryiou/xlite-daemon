package io.xlite.daemon.app.net.xrouter;

import com.google.common.collect.HashBiMap;

public final class XRouterCommandUtils {
    private XRouterCommandUtils() {}

    private static final HashBiMap<String, Integer> COMMANDS;

    static {
        COMMANDS = HashBiMap.create(19);

        COMMANDS.put("xrInvalid", 0);
        COMMANDS.put("xrReply", 1);
        COMMANDS.put("xrGetReply", 2);
        COMMANDS.put("xrGetConfig", 3);
        COMMANDS.put("xrConfigReply", 4);
        COMMANDS.put("xrGetBlockCount", 20);
        COMMANDS.put("xrGetBlockHash", 21);
        COMMANDS.put("xrGetBlock", 22);
        COMMANDS.put("xrGetTransaction", 23);
        COMMANDS.put("xrSendTransaction", 24);
        COMMANDS.put("xrGetTxBloomFilter", 40);
        COMMANDS.put("xrGenerateBloomFilter", 41);
        COMMANDS.put("xrGetBlocks", 50);
        COMMANDS.put("xrGetTransactions", 51);
        COMMANDS.put("xrGetBlockAtTime", 52);
        COMMANDS.put("xrDecodeRawTransaction", 53);
        COMMANDS.put("xrGetBalance", 60);
        COMMANDS.put("xrGetBalanceUpdate", 61);
        COMMANDS.put("xrService", 1000);
    }

    public static int commandStringToInt(String command) {
        Integer commandId = COMMANDS.get(command);
        return commandId == null ? 0 : commandId;
    }

    public static String commandIdToString(int commandId) {
        String command = COMMANDS.inverse().get(commandId);
        return command == null ? "xrInvalid" : command;
    }
}
