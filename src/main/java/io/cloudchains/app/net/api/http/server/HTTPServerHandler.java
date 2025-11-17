package io.cloudchains.app.net.api.http.server;

import com.google.common.base.Preconditions;
import com.google.gson.*;
import com.subgraph.orchid.encoders.Base64;
import com.subgraph.orchid.encoders.Hex;

import io.cloudchains.app.App;
import io.cloudchains.app.Version;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.net.api.http.server.handlers.RpcHandler;
import io.cloudchains.app.net.api.http.HttpErrorUtils;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.Utility;
import io.cloudchains.app.wallet.WalletHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.bitcoinj.core.*;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private HTTPClient httpClient;
	private CoinInstance coin;
	private ConfigHelper configHelper;
	private RpcHandler rpcHandler;

	HTTPServerHandler(CoinInstance coin) {
		this.coin = coin;
		this.configHelper = coin.getConfigHelper();
		this.httpClient = new HTTPClient(5);
		this.rpcHandler = new RpcHandler(coin);
	}

	@Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
	    super.channelInactive(ctx);
        this.httpClient.close();
    }

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();

		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
		writeResponse(ctx, httpResponse, null);
		ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
		ctx.close();
		this.httpClient.close();
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		LOGGER.log(Level.FINER, "[http-server-handler] DEBUG: Channel read complete. Flushing context.");
		super.channelReadComplete(ctx);
		ctx.flush();
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
		boolean successfulAuth = false;
		HttpResponseStatus status = HttpResponseStatus.OK;
		JsonObject response = new JsonObject();

		if (request != null) {
			HttpHeaders httpHeaders = request.headers();

			if (HttpUtil.is100ContinueExpected(request)) {
				HttpErrorUtils.send100Continue(ctx);
			}

			String headerUser;
			String headerPass;
			if (httpHeaders.contains("Authorization")) {
				String authorization = httpHeaders.get("Authorization");

				if (authorization != null && authorization.toLowerCase().startsWith("basic")) {
					String base64Credentials = authorization.substring("Basic".length()).trim();
					byte[] credDecoded = Base64.decode(base64Credentials);
					String credentials = new String(credDecoded, StandardCharsets.UTF_8);
					final String[] values = credentials.split(":", 2);

					headerUser = values[0];
					headerPass = values[1];

					if (headerUser.equals(configHelper.getRpcUsername()) && headerPass.equals(configHelper.getRpcPassword())) {
						successfulAuth = true;
						LOGGER.log(Level.FINER, "[http-server-handler] Successful Auth");
					}
				}
			}

			if (!request.uri().equals("/")) {
				HttpErrorUtils.writeErrorResponse(ctx, 
					HttpErrorUtils.ErrorCodes.ONLY_ROOT, 
					HttpErrorUtils.ErrorCodes.MESSAGE_ONLY_ROOT, 
					HttpResponseStatus.BAD_REQUEST);
				return;
			}

			if (request.method() != HttpMethod.POST) {
				HttpErrorUtils.writeErrorResponse(ctx, 
					HttpErrorUtils.ErrorCodes.ONLY_POST, 
					HttpErrorUtils.ErrorCodes.MESSAGE_ONLY_POST, 
					HttpResponseStatus.BAD_REQUEST);
				return;
			}

			if (!coin.isInstanceRunning()) {
				HttpErrorUtils.writeErrorResponse(ctx, 
					HttpErrorUtils.ErrorCodes.INSTANCE_NOT_RUNNING, 
					HttpErrorUtils.ErrorCodes.MESSAGE_INSTANCE_NOT_RUNNING, 
					HttpResponseStatus.SERVICE_UNAVAILABLE);
				return;
			}

			if (!successfulAuth) {
				HttpErrorUtils.writeErrorResponse(ctx, 
					HttpErrorUtils.ErrorCodes.UNAUTHORIZED, 
					HttpErrorUtils.ErrorCodes.MESSAGE_UNAUTHORIZED, 
					HttpResponseStatus.FORBIDDEN);
				return;
			}
		}

		if (status != HttpResponseStatus.OK) {
			ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
			FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

			writeResponse(ctx, httpResponse, request);
			ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
			return;
		}

		if (request != null) {
			String content = request.content().toString(CharsetUtil.UTF_8);
			JsonObject jsonReq = null;

			try {
				jsonReq = new JsonParser().parse(content).getAsJsonObject();

				Preconditions.checkNotNull(jsonReq);

				if (!jsonReq.has("method") || !jsonReq.has("params")) {
					ctx.close();
					throw new IllegalArgumentException("Bad JSON-RPC request by client.");
				}
			} catch (Exception e) {
				LOGGER.log(Level.INFO, "Failed Content: " + content);
				e.printStackTrace();
				
				if (e instanceof IllegalArgumentException) {
					LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client sent valid JSON, but did not specify method and/or parameters!");
				} else {
					LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client sent invalid JSON!");
				}
				
				HttpErrorUtils.writeErrorResponse(ctx, 
					HttpErrorUtils.ErrorCodes.BAD_PARSE, 
					HttpErrorUtils.ErrorCodes.MESSAGE_BAD_PARSE, 
					HttpResponseStatus.BAD_REQUEST);
				return;
			}

			if (status == HttpResponseStatus.OK) {
				Preconditions.checkNotNull(jsonReq);

				String method = jsonReq.get("method").getAsString();
				JsonArray params = jsonReq.get("params").getAsJsonArray();

				LOGGER.log(Level.INFO, "[http-server-handler] RPC CALL: " + coin.getTicker()+ " " + method + " PARAMS: " + params.size());
				for (int i = 0; i < params.size(); i++) {
					LOGGER.log(Level.INFO, "[http-server-handler] PARAM " + i + ": " + params.get(i).toString());
				}

				response = getResponse(method, params);
				LOGGER.log(Level.FINER, response.toString());
			} else {
				ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
				FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

				writeResponse(ctx, httpResponse, request);
				ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
				return;
			}

			if (request instanceof LastHttpContent) {
				ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);

				FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

				if (!writeResponse(ctx, httpResponse, request)) {
					ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
				}
			}
		}
	}

	private JsonObject getResponse(String method, JsonArray params) {
		JsonObject response = new JsonObject();

		switch (method.toLowerCase()) {
			case "reloadconfig":
				return rpcHandler.handleReloadConfig();
			case "getinfo":
				return rpcHandler.handleGetInfo();
			case "getblockcount":
				return rpcHandler.handleGetBlockCount();
			case "getnetworkinfo":
				return rpcHandler.handleGetNetworkInfo();
			case "listunspent":
				return rpcHandler.handleListUnspent();
			case "listtransactions":
				return rpcHandler.handleListTransactions(params);
			case "getblockchaininfo":
				return rpcHandler.handleGetBlockchainInfo();
			case "getblockhash":
				return rpcHandler.handleGetBlockHash(params);
			case "sendrawtransaction":
				return rpcHandler.handleSendRawTransaction(params);
			case "getrawtransaction":
				return rpcHandler.handleGetRawTransaction(params);
			case "getrawmempool":
				return rpcHandler.handleGetRawMempool(params);
			case "getblock":
				return rpcHandler.handleGetBlock(params);
			case "gettransaction":
				return rpcHandler.handleGetTransaction(params);
			case "getaddressesbyaccount":
				return rpcHandler.handleGetAddressesByAccount(params);
			case "createrawtransaction":
				return rpcHandler.handleCreateRawTransaction(params);
			case "decoderawtransaction":
				return rpcHandler.handleDecodeRawTransaction(params);
			case "signrawtransaction":
				return rpcHandler.handleSignRawTransaction(params);
			case "gettxout":
				return rpcHandler.handleGetTxOut(params);
			case "getnewaddress":
				return rpcHandler.handleGetNewAddress(new JsonArray());
			case "importprivkey":
				return rpcHandler.handleImportPrivKey(params);
			case "dumpprivkey":
				return rpcHandler.handleDumpPrivKey(params);
			case "signmessage":
				return rpcHandler.handleSignMessage(params);
			case "verifymessage":
				return rpcHandler.handleVerifyMessage(params);
			case "sendtransaction":
				return rpcHandler.handleSendTransaction(params);
			case "version":
				return rpcHandler.handleVersion();
			case "validateaddress":
				return rpcHandler.handleValidateAddress(params);
			case "help":
				return rpcHandler.handleHelp();
			default:
				return rpcHandler.handleDefault();
		}
	}

	private boolean writeResponse(ChannelHandlerContext ctx, FullHttpResponse httpResponse, FullHttpRequest request) {
		boolean keepAlive = false;
		if (request != null)
		    keepAlive = HttpUtil.isKeepAlive(request);

		httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
		httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
		httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		httpResponse.headers().set(HttpHeaderNames.SERVER, CoinInstance.getVersionString());

		LOGGER.log(Level.FINER, "[http-server-handler] Writing response to channel. Keep alive? " + keepAlive);
		LOGGER.log(Level.FINER, "[http-server-handler] Response content: " + httpResponse.content().toString(CharsetUtil.UTF_8));
		ctx.write(httpResponse);

		return keepAlive;
	}
}
