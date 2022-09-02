package io.cloudchains.app.net.protocols.blocknet.listeners;

import io.cloudchains.app.net.xrouter.XRouterMessage;

public interface BlocknetOnXRouterMessageReceivedListener {

	void onXRouterMessageReceived(XRouterMessage message, XRouterMessage original);

}
