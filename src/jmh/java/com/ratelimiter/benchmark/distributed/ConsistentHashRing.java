package com.ratelimiter.benchmark.distributed;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ConsistentHashRing {

    private final NavigableMap<Long, RateLimiterNode> ring = new TreeMap<>();
    private final int virtualNodes;

    public ConsistentHashRing(Collection<RateLimiterNode> nodes, int virtualNodes) {
        this.virtualNodes = virtualNodes;
        for (RateLimiterNode node : nodes) {
            addNode(node);
        }
    }

    public void addNode(RateLimiterNode node) {
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(hash(node.id() + ":" + i), node);
        }
    }

    public void removeNode(RateLimiterNode node) {
        for (int i = 0; i < virtualNodes; i++) {
            ring.remove(hash(node.id() + ":" + i));
        }
    }

    public RateLimiterNode route(String shardKey) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("No nodes registered");
        }
        long keyHash = hash(shardKey);
        var entry = ring.ceilingEntry(keyHash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    private long hash(String input) {
        return Hashing.murmur3_128().hashString(input, StandardCharsets.UTF_8).asLong() & Long.MAX_VALUE;
    }
}
