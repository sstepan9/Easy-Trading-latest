package com.easytrading.paper.trade;

import java.util.UUID;

/**
 * Simple record for a pending trade request.
 */
public class TradeRequest {
    private final UUID sender;
    private final UUID target;
    private final long timestamp;

    public TradeRequest(UUID sender, UUID target, long timestamp) {
        this.sender = sender;
        this.target = target;
        this.timestamp = timestamp;
    }

    public UUID getSender() { return sender; }
    public UUID getTarget() { return target; }
    public long getTimestamp() { return timestamp; }
}
