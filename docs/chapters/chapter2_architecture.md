# Chapter 2: System Architecture and Component Design

## Architectural Overview

The Distributed Sharded Counter system is built around a layered architecture that separates concerns and enables horizontal scaling. At its core, the system consists of two primary types of nodes: coordinators and shards. This separation allows each component to specialize in its specific responsibilities while maintaining loose coupling between components.

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Coordinator   │    │   Coordinator   │    │   Coordinator   │
│   (Routing &    │    │   (Routing &    │    │   (Routing &    │
│   Aggregation)  │    │   Aggregation)  │    │   Aggregation)  │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          ▼                      ▼                      ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Shard Node    │    │   Shard Node    │    │   Shard Node    │
│   (Storage &    │    │   (Storage &    │    │   (Storage &    │
│   Processing)   │    │   Processing)   │    │   Processing)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

## Coordinator Nodes: The Brain of the System

Coordinator nodes serve as the entry point for all client requests and handle the complex logic of routing, aggregation, and response management. They implement the `ShardedCounterCoordinator` class, which acts as the orchestrator for the entire distributed system.

### Request Routing and Consistent Hashing

The coordinator uses consistent hashing to deterministically route counter operations to specific shards. This ensures that the same counter ID always maps to the same shard, providing predictable behavior and enabling efficient caching.

```java
// From ShardedCounterCoordinator.java
public class ShardedCounterCoordinator {
    private final ConsistentHash hashRing;
    private final List<String> shardAddresses;
    
    public ShardedCounterResponse handleRequest(ShardedCounterOperation operation) {
        // Determine which shard should handle this counter
        String targetShard = hashRing.get(operation.getCounterId());
        
        // Route the operation to the appropriate shard
        return routeToShard(targetShard, operation);
    }
}
```

The consistent hashing implementation ensures that:
- **Deterministic Routing**: The same counter ID always routes to the same shard
- **Load Distribution**: Operations are evenly distributed across available shards
- **Minimal Rebalancing**: When shards are added or removed, only a small portion of data needs to be redistributed

### Operation Aggregation for Reads

For read operations, the coordinator must query all shards and aggregate their individual values to provide the total count. This is implemented in the `getTotalValue` method:

```java
// From ShardedCounterCoordinator.java
public ShardedCounterResponse getTotalValue(String counterId) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    
    // Query all shards for their individual values
    for (String shardAddress : shardAddresses) {
        try {
            ShardedCounterResponse response = queryShard(shardAddress, counterId);
            if (response.isSuccess()) {
                long shardValue = response.getShardValue();
                shardValues.put(shardAddress, shardValue);
                totalValue += shardValue;
            }
        } catch (Exception e) {
            // Handle shard failure gracefully
            logger.error("Failed to query shard: " + shardAddress, e);
        }
    }
    
    return new ShardedCounterResponse(true, totalValue, shardValues);
}
```

This aggregation process is crucial for providing a unified view of the distributed counter, but it introduces some latency as the coordinator must wait for responses from all shards.

## Shard Nodes: The Workhorses of Storage

Shard nodes are responsible for the actual storage and processing of counter values. Each shard operates independently, maintaining its own storage layer and processing counter operations in isolation. This independence is key to the system's scalability and fault tolerance.

### Storage Layer Architecture

Each shard implements a dual-layer storage approach combining in-memory caching with persistent storage:

```java
// From ShardNode.java
public class ShardNode {
    private final Map<String, Long> inMemoryCache;
    private final RocksDBStorage persistentStorage;
    
    public ShardNode() {
        this.inMemoryCache = new ConcurrentHashMap<>();
        this.persistentStorage = new RocksDBStorage();
        loadDataFromPersistentStorage();
    }
}
```

The storage architecture provides:
- **Fast Access**: In-memory cache for hot data
- **Durability**: RocksDB for persistent storage
- **Recovery**: Automatic data reconstruction on startup

### Atomic Counter Operations

Shard nodes implement atomic counter operations using thread-safe data structures:

```java
// From ShardNode.java
public long increment(String counterId, long delta) {
    return inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        long newValue = current + delta;
        
        // Persist to RocksDB asynchronously
        persistentStorage.put(counterId, String.valueOf(newValue));
        
        return newValue;
    });
}
```

This implementation ensures:
- **Thread Safety**: ConcurrentHashMap provides thread-safe operations
- **Atomicity**: Each increment operation is atomic
- **Persistence**: Values are automatically persisted to RocksDB
- **Performance**: In-memory operations provide sub-millisecond response times

## Consistent Hashing: The Routing Engine

The consistent hashing implementation is the heart of the routing system, ensuring deterministic and efficient distribution of counter operations across shards.

```java
// From ConsistentHash.java
public class ConsistentHash {
    private final TreeMap<Integer, String> hashRing;
    private final int virtualNodes;
    
    public String get(String key) {
        if (hashRing.isEmpty()) {
            return null;
        }
        
        int hash = hash(key);
        Map.Entry<Integer, String> entry = hashRing.ceilingEntry(hash);
        
        if (entry == null) {
            entry = hashRing.firstEntry();
        }
        
        return entry.getValue();
    }
}
```

The consistent hashing algorithm provides:
- **Deterministic Routing**: Same key always maps to same shard
- **Load Balancing**: Even distribution across available shards
- **Minimal Rebalancing**: Only O(1/n) of data moves when nodes change
- **Virtual Nodes**: Improved load distribution through virtual node replication

## Data Flow Patterns

The system implements two distinct data flow patterns for different operation types:

### Write Operations (Increment/Decrement)

Write operations follow a direct routing pattern where the coordinator routes the operation to a specific shard:

```
Client → Coordinator → Consistent Hashing → Target Shard → Storage Update → Response
```

This pattern provides:
- **Low Latency**: Single-hop routing to target shard
- **High Throughput**: No coordination between shards required
- **Fault Isolation**: Failure of one shard doesn't affect others

### Read Operations (Get Total)

Read operations require aggregation across all shards:

```
Client → Coordinator → Query All Shards → Aggregate Results → Response
```

This pattern provides:
- **Complete View**: Aggregated view of all shard values
- **Fault Tolerance**: Continues operating even if some shards are down
- **Consistency**: Eventual consistency across all shards

## Fault Tolerance and Recovery

The system implements several mechanisms for fault tolerance:

### Shard Failure Handling

When a shard fails, the coordinator gracefully handles the failure:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse routeToShard(String shardAddress, ShardedCounterOperation operation) {
    try {
        // Attempt to route to the target shard
        return httpClient.post(shardAddress + "/sharded", operation);
    } catch (Exception e) {
        // Handle shard failure
        logger.error("Shard failure: " + shardAddress, e);
        
        // For writes, we could implement retry logic or failover
        // For reads, we continue with available shards
        return new ShardedCounterResponse(false, 0, null);
    }
}
```

### Data Recovery

Shards automatically recover their state on startup by loading data from persistent storage:

```java
// From ShardNode.java
private void loadDataFromPersistentStorage() {
    try {
        // Load all data from RocksDB into memory
        for (RocksIterator iterator = persistentStorage.getIterator()) {
            String key = new String(iterator.key());
            long value = Long.parseLong(new String(iterator.value()));
            inMemoryCache.put(key, value);
        }
    } catch (Exception e) {
        logger.error("Failed to load data from persistent storage", e);
    }
}
```

## Performance Characteristics

The architecture provides several performance benefits:

### Write Performance

- **Parallel Processing**: Writes are distributed across multiple shards
- **No Lock Contention**: Each shard operates independently
- **In-Memory Operations**: Fast in-memory updates with async persistence
- **Horizontal Scaling**: Add more shards to increase write capacity

### Read Performance

- **Parallel Queries**: Coordinator queries all shards in parallel
- **Caching**: In-memory cache provides fast access to hot data
- **Fault Tolerance**: Continues operating even with shard failures
- **Eventual Consistency**: Accepts some latency for complete view

## Scalability Considerations

The architecture supports horizontal scaling through several mechanisms:

### Adding Shards

When new shards are added:
1. **Consistent Hashing**: Only a portion of existing data needs redistribution
2. **Gradual Migration**: Data can be migrated gradually without downtime
3. **Load Rebalancing**: New shards automatically receive their share of new operations

### Removing Shards

When shards are removed:
1. **Data Redistribution**: Affected data is redistributed to remaining shards
2. **Service Continuity**: System continues operating with remaining shards
3. **Recovery**: Failed shards can be replaced without data loss

---

*This chapter explored the detailed architecture and component interactions of the distributed sharded counter system. In the next chapter, we'll examine the write operations flow in detail, including the complete request lifecycle and implementation specifics.* 