package io.cloudchains.app.net.xrouter;

import com.google.common.collect.HashBiMap;

public class XRouterCommandUtils {

	private static final HashBiMap<String, Integer> commands;

	static {
		commands = HashBiMap.create(19);

		commands.put("xrInvalid", 0);
		commands.put("xrReply", 1);
		commands.put("xrGetReply", 2);
		commands.put("xrGetConfig", 3);
		commands.put("xrConfigReply", 4);
		commands.put("xrGetBlockCount", 20);
		commands.put("xrGetBlockHash", 21);
		commands.put("xrGetBlock", 22);
		commands.put("xrGetTransaction", 23);
		commands.put("xrSendTransaction", 24);
		commands.put("xrGetTxBloomFilter", 40);
		commands.put("xrGenerateBloomFilter", 41);
		commands.put("xrGetBlocks", 50);
		commands.put("xrGetTransactions", 51);
		commands.put("xrGetBlockAtTime", 52);
		commands.put("xrDecodeRawTransaction", 53);
		commands.put("xrGetBalance", 60);
		commands.put("xrGetBalanceUpdate", 61);
		commands.put("xrService", 1000);
	}

	public static int commandStringToInt(String command) {
		Integer commandId = commands.get(command);
		return commandId == null ? 0 : commandId;
	}

	public static String commandIdToString(int commandId) {
		String command = commands.inverse().get(commandId);
		return command == null ? "xrInvalid" : command;
	}

}
