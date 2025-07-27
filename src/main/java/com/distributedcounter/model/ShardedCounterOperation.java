package com.distributedcounter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data model for sharded counter operations
 * 
 * This represents operations on a sharded counter where:
 * - A single counter is split across multiple shards
 * - Writes use consistent hashing to route to specific shards
 * - Reads aggregate from all shards to get total count
 */
public class ShardedCounterOperation {
    public enum OperationType {
        INCREMENT,
        DECREMENT,
        GET_TOTAL,
        GET_SHARD_VALUES
    }
    
    private final String counterId;
    private final OperationType operationType;
    private final long delta;
    
    @JsonCreator
    public ShardedCounterOperation(
            @JsonProperty("counterId") String counterId,
            @JsonProperty("operationType") OperationType operationType,
            @JsonProperty("delta") long delta) {
        this.counterId = counterId;
        this.operationType = operationType;
        this.delta = delta;
    }
    
    public ShardedCounterOperation(String counterId, OperationType operationType) {
        this(counterId, operationType, 0);
    }
    
    public String getCounterId() {
        return counterId;
    }
    
    public OperationType getOperationType() {
        return operationType;
    }
    
    public long getDelta() {
        return delta;
    }
    
    @Override
    public String toString() {
        return "ShardedCounterOperation{" +
                "counterId='" + counterId + '\'' +
                ", operationType=" + operationType +
                ", delta=" + delta +
                '}';
    }
} 