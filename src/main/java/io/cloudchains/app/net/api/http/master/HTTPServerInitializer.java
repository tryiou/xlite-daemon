package io.cloudchains.app.net.api.http.master;

import io.cloudchains.app.net.api.http.server.ExceptionHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

public class HTTPServerInitializer extends ChannelInitializer<SocketChannel> {
	public HTTPServerInitializer() {}

	@Override
	protected void initChannel(SocketChannel ch) {
		ChannelPipeline pipeline = ch.pipeline();

		pipeline.addLast(new WriteTimeoutHandler(30));
		pipeline.addLast(new ReadTimeoutHandler(30));
		pipeline.addLast(new HttpRequestDecoder());
		pipeline.addLast(new HttpResponseEncoder());
		pipeline.addLast(new HttpObjectAggregator(100000000));
		pipeline.addLast(new HTTPServerHandler());
		pipeline.addLast(new ExceptionHandler());
	}

}
