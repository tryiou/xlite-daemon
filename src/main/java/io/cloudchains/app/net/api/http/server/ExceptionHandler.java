package io.cloudchains.app.net.api.http.server;

import io.cloudchains.app.net.api.http.HttpErrorUtils;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ExceptionHandler extends ChannelDuplexHandler {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.FINER, cause.getMessage());
        HttpErrorUtils.writeErrorResponse(ctx, 
            HttpErrorUtils.ErrorCodes.UNEXPECTED_ERROR, 
            HttpErrorUtils.ErrorCodes.MESSAGE_UNEXPECTED_ERROR, 
            io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST);
    }
}
