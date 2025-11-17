package io.cloudchains.app.net.api.http;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.cloudchains.app.net.CoinInstance;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HttpErrorUtils {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /**
     * Creates a standardized error response JSON object
     */
    public static JsonObject createErrorResponse(int code, String message) {
        JsonObject response = new JsonObject();
        JsonObject errorJSON = new JsonObject();
        
        errorJSON.addProperty("code", code);
        errorJSON.addProperty("message", message);
        
        response.add("result", JsonNull.INSTANCE);
        response.add("error", errorJSON);
        
        return response;
    }

    /**
     * Writes an error response to the channel
     */
    public static void writeErrorResponse(ChannelHandlerContext ctx, int code, String message, HttpResponseStatus status) {
        LOGGER.log(Level.FINER, "Writing error response: " + message);
        
        JsonObject response = createErrorResponse(code, message);
        ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, responseContent);
        
        writeResponse(ctx, httpResponse);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        ctx.close();
    }

    /**
     * Writes a standard HTTP response with proper headers
     */
    public static void writeResponse(ChannelHandlerContext ctx, FullHttpResponse httpResponse) {
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
        httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        httpResponse.headers().set(HttpHeaderNames.SERVER, CoinInstance.getVersionString());
        
        ctx.write(httpResponse);
    }

    /**
     * Creates a 100 Continue response
     */
    public static void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        ctx.write(response);
    }

    /**
     * Standard error codes and messages
     */
    public static class ErrorCodes {
        public static final int BAD_PARSE = -1001;
        public static final int ONLY_ROOT = -1002;
        public static final int ONLY_POST = -1003;
        public static final int UNAUTHORIZED = -1111;
        public static final int INSTANCE_NOT_RUNNING = -1112;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int UNEXPECTED_ERROR = -1;
        
        public static final String MESSAGE_BAD_PARSE = "Error parsing JSON.";
        public static final String MESSAGE_ONLY_ROOT = "Only the server root ('/') is being served.";
        public static final String MESSAGE_ONLY_POST = "Only HTTP POST is accepted.";
        public static final String MESSAGE_UNAUTHORIZED = "Unauthorized!";
        public static final String MESSAGE_INSTANCE_NOT_RUNNING = "This coin is temporarily unavailable.";
        public static final String MESSAGE_METHOD_NOT_FOUND = "Method not found.";
        public static final String MESSAGE_UNEXPECTED_ERROR = "Unexpected Error";
    }
}
