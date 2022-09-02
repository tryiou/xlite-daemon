package io.cloudchains.app.net.protocols.blocknet.messagequeue;

import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;

import java.util.HashMap;

public class QueueItem {
    private BlocknetPeer originalPeer;
    private BlocknetPeer newPeer;
    private String customUUID;
    private String commmand;
    private HashMap<String, Object> params;

    private MessageSource messageSource;

    private long creationTime;
    private int expiryTimeInMinutes = 2;

    public QueueItem(BlocknetPeer blocknetPeer, String command, HashMap<String, Object> params, MessageSource messageSource) {
        this.originalPeer = blocknetPeer;
        this.commmand = command;
        this.params = params;

        this.messageSource = messageSource;

        this.creationTime = System.currentTimeMillis();
    }

    public QueueItem(BlocknetPeer blocknetPeer, String uuid, String command, HashMap<String, Object> params, MessageSource messageSource) {
        this.originalPeer = blocknetPeer;
        this.customUUID = uuid;
        this.commmand = command;
        this.params = params;

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

    public HashMap<String, Object> getParams() {
        return params;
    }

    public MessageSource getMessageSource() {
        return messageSource;
    }

    public boolean isValidItem() {
        long diff = System.currentTimeMillis() - creationTime;

        return diff < (expiryTimeInMinutes * (1000 * 60));
    }
}
