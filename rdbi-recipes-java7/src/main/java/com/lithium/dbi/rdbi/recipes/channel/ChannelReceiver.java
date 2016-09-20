package com.lithium.dbi.rdbi.recipes.channel;

public interface ChannelReceiver {
    GetResult get(String channel, Long lastSeenId);
    GetResult get(String channel, Long lastSeenId, String copyDepthToKey);
    Long getDepth(String channel);
    Long getDepth(String channel, String copyDepthToKey);
}