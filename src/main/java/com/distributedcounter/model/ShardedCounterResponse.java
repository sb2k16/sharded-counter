package com.distributedcounter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Data model for sharded counter responses
 * 
 * This represents responses from sharded counter operations where:
 * - totalValue is the aggregated value from all shards
 * - shardValues contains the individual values from each shard
 */
public class ShardedCounterResponse {
    private final boolean success;
    private final String message;
    private final long totalValue;
    private final long shardValue;  // Value from specific shard
    private final Map<String, Long> shardValues;  // Values from all shards
    private final int shardCount;
    
    @JsonCreator
    public ShardedCounterResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("message") String message,
            @JsonProperty("totalValue") long totalValue,
            @JsonProperty("shardValue") long shardValue,
            @JsonProperty("shardValues") Map<String, Long> shardValues,
            @JsonProperty("shardCount") int shardCount) {
        this.success = success;
        this.message = message;
        this.totalValue = totalValue;
        this.shardValue = shardValue;
        this.shardValues = shardValues;
        this.shardCount = shardCount;
    }
    
    public static ShardedCounterResponse success(long totalValue) {
        return new ShardedCounterResponse(true, "Success", totalValue, 0, null, 0);
    }
    
    public static ShardedCounterResponse success(long totalValue, Map<String, Long> shardValues) {
        return new ShardedCounterResponse(true, "Success", totalValue, 0, shardValues, shardValues.size());
    }
    
    public static ShardedCounterResponse shardSuccess(long shardValue) {
        return new ShardedCounterResponse(true, "Success", 0, shardValue, null, 1);
    }
    
    public static ShardedCounterResponse error(String message) {
        return new ShardedCounterResponse(false, message, 0, 0, null, 0);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public long getTotalValue() {
        return totalValue;
    }
    
    public long getShardValue() {
        return shardValue;
    }
    
    public Map<String, Long> getShardValues() {
        return shardValues;
    }
    
    public int getShardCount() {
        return shardCount;
    }
    
    @Override
    public String toString() {
        return "ShardedCounterResponse{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", totalValue=" + totalValue +
                ", shardValue=" + shardValue +
                ", shardValues=" + shardValues +
                ", shardCount=" + shardCount +
                '}';
    }
} 