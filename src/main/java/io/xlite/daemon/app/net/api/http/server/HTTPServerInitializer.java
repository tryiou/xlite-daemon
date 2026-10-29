package io.xlite.daemon.app.net.api.http.server;

import io.xlite.daemon.app.net.CoinInstance;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

public class HTTPServerInitializer extends ChannelInitializer<SocketChannel> {

    private CoinInstance coin;

    public HTTPServerInitializer(CoinInstance coin) {
        this.coin = coin;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new WriteTimeoutHandler(30));
        pipeline.addLast(new ReadTimeoutHandler(30));
        pipeline.addLast(new HttpRequestDecoder());
        pipeline.addLast(new HttpResponseEncoder());
        pipeline.addLast(new HttpObjectAggregator(100000000));
        pipeline.addLast(new HTTPServerHandler(coin));
        pipeline.addLast(new ExceptionHandler());
    }

}
