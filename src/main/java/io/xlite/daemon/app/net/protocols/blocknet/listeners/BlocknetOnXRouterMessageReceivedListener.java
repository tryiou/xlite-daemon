package io.xlite.daemon.app.net.protocols.blocknet.listeners;

import io.xlite.daemon.app.net.xrouter.XRouterMessage;

public interface BlocknetOnXRouterMessageReceivedListener {

    void onXRouterMessageReceived(XRouterMessage message, XRouterMessage original);

}
