package io.xlite.daemon.app.net.protocols.blocknet.messagequeue;

import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class QueueItem {
    private BlocknetPeer originalPeer;
    private BlocknetPeer newPeer;
    private String customUUID;
    private String commmand;
    private final Map<String, Object> params;

    private MessageSource messageSource;

    private long creationTime;
    private int expiryTimeInMinutes = 2;

    public QueueItem(
            BlocknetPeer blocknetPeer,
            String command,
            Map<String, Object> params,
            MessageSource messageSource) {
        this.originalPeer = blocknetPeer;
        this.commmand = command;
        this.params = Collections.unmodifiableMap(new HashMap<>(params));

        this.messageSource = messageSource;

        this.creationTime = System.currentTimeMillis();
    }

    public QueueItem(
            BlocknetPeer blocknetPeer,
            String uuid,
            String command,
            Map<String, Object> params,
            MessageSource messageSource) {
        this.originalPeer = blocknetPeer;
        this.customUUID = uuid;
        this.commmand = command;
        this.params = Collections.unmodifiableMap(new HashMap<>(params));

        this.messageSource = messageSource;

        this.creationTime = System.currentTimeMillis();
    }

    public void setNewPeer(BlocknetPeer blocknetPeer) {
        this.newPeer = blocknetPeer;
    }

    public BlocknetPeer getOriginalPeer() {
        return originalPeer;
    }

    public BlocknetPeer getNewPeer() {
        return newPeer;
    }

    public String getCustomUUID() {
        return customUUID;
    }

    public String getCommmand() {
        return commmand;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public MessageSource getMessageSource() {
        return messageSource;
    }

    public boolean isValidItem() {
        long diff = System.currentTimeMillis() - creationTime;

        return diff < (expiryTimeInMinutes * 60_000L);
    }
}
