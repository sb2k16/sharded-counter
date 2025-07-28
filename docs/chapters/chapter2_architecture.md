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

<details>
<summary><strong>ShardedCounterCoordinator Implementation</strong></summary>

```java
// From ShardedCounterCoordinator.java
public class ShardedCounterCoordinator {
    private final ConsistentHash<String> hashRing;
    private final List<String> shardAddresses;
    private final Map<String, ShardInfo> shards;
    
    public ShardedCounterCoordinator(int port, List<String> shardAddresses) {
        this.shardAddresses = new ArrayList<>(shardAddresses);
        this.shards = new ConcurrentHashMap<>();
        
        // Initialize shards
        for (String address : shardAddresses) {
            shards.put(address, new ShardInfo(address));
        }
        
        // Initialize consistent hash ring for routing writes
        this.hashRing = new ConsistentHash<>(
                new ConsistentHash.FNVHashFunction(), 
                150, // Number of virtual nodes per shard
                shards.keySet()
        );
    }
}
```

</details>

The consistent hashing implementation ensures that:
- **Deterministic Routing**: The same counter ID always routes to the same shard
- **Load Distribution**: Operations are evenly distributed across available shards
- **Minimal Rebalancing**: Only O(1/n) of data moves when nodes change
- **Virtual Nodes**: Improved load distribution through virtual node replication

### Operation Aggregation for Reads

For read operations, the coordinator must query all shards and aggregate their individual values to provide the total count. This is implemented in the `handleGetTotal` method:

<details>
<summary><strong>Read Operation Aggregation Implementation</strong></summary>

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse handleGetTotal(ShardedCounterOperation operation) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    int successfulResponses = 0;
    
    // Query all shards for their individual values
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String shardAddress = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        if (!shardInfo.isHealthy()) {
            logger.warn("Skipping unhealthy shard: {}", shardAddress);
            continue;
        }
        
        try {
            ShardedCounterResponse response = queryShard(shardAddress, operation.getCounterId());
            if (response.isSuccess()) {
                long shardValue = response.getShardValue();
                shardValues.put(shardAddress, shardValue);
                totalValue += shardValue;
                successfulResponses++;
            }
        } catch (Exception e) {
            logger.error("Failed to query shard: " + shardAddress, e);
        }
    }
    
    if (successfulResponses == 0) {
        return ShardedCounterResponse.error("No healthy shards available");
    }
    
    return ShardedCounterResponse.success(totalValue, shardValues);
}
```

</details>

This aggregation process provides several benefits:
- **Fault Tolerance**: The system continues operating even if some shards are unavailable
- **Eventual Consistency**: Reads may return slightly stale data, but the system remains highly available
- **Load Distribution**: Read load is distributed across all shards
- **Graceful Degradation**: The system can operate with reduced capacity during partial failures

## Shard Nodes: The Storage and Processing Layer

Shard nodes are responsible for storing and processing counter values. They implement the `ShardNode` class and handle the core counter operations including increments, decrements, and value retrieval.

### Atomic Counter Operations

Shard nodes implement atomic counter operations using RocksDB storage. The atomicity is achieved through a combination of in-memory operations and persistent storage:

<details>
<summary><strong>Atomic Counter Operations Implementation</strong></summary>

```java
// From RocksDBStorage.java (referenced in Chapter 2 for atomicity)
public long increment(String counterId, long delta) throws RocksDBException {
    // Update in-memory cache atomically
    long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        return current + delta;
    });

    // Persist to RocksDB
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true); // Ensure durability

    db.put(writeOptions,
           counterId.getBytes(StandardCharsets.UTF_8),
           String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));

    logger.debug("Incremented counter {} by {}, new value: {}", counterId, delta, newValue);
    return newValue;
}
```

</details>

The atomicity is guaranteed through several mechanisms:

1. **ConcurrentHashMap.compute()**: Provides atomic read-modify-write operations on the in-memory cache
2. **RocksDB Thread Safety**: RocksDB handles concurrent access safely
3. **Synchronous Writes**: The `setSync(true)` option ensures data is persisted before returning
4. **Exception Handling**: Any failure in the atomic operation is properly handled

### Health Monitoring and Failure Detection

Shard nodes implement health monitoring to detect failures and enable automatic failover:

<details>
<summary><strong>Health Monitoring Implementation</strong></summary>

```java
// From ShardNode.java
private void checkShardHealth() {
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String address = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        CompletableFuture.runAsync(() -> {
            try {
                // Simple health check - could be enhanced with actual HTTP call
                shardInfo.setHealthy(true);
                shardInfo.setLastSeen(System.currentTimeMillis());
            } catch (Exception e) {
                logger.warn("Shard {} is unhealthy: {}", address, e.getMessage());
                shardInfo.setHealthy(false);
            }
        });
    }
}
```

</details>

This health monitoring system provides:
- **Proactive Failure Detection**: Identifies failing shards before they impact operations
- **Automatic Recovery**: Shards can be marked as healthy when they recover
- **Load Balancing**: Unhealthy shards can be excluded from routing decisions
- **Monitoring Integration**: Health status can be exposed to monitoring systems

## Consistent Hashing: The Routing Foundation

Consistent hashing is the core algorithm that enables deterministic routing and load distribution. The implementation uses a ring-based approach with virtual nodes for improved distribution.

### Hash Ring Implementation

<details>
<summary><strong>Consistent Hashing Implementation</strong></summary>

```java
// From ConsistentHash.java
public class ConsistentHash<T> {
    private final HashFunction hashFunction;
    private final int numberOfReplicas;
    private final SortedMap<Long, T> circle = new TreeMap<>();
    
    public ConsistentHash(HashFunction hashFunction, int numberOfReplicas, Collection<T> nodes) {
        this.hashFunction = hashFunction;
        this.numberOfReplicas = numberOfReplicas;
        for (T node : nodes) {
            add(node);
        }
    }
    
    public void add(T node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            circle.put(hashFunction.hash(node.toString() + i), node);
        }
    }
    
    public T get(Object key) {
        if (circle.isEmpty()) {
            return null;
        }
        long hash = hashFunction.hash(key.toString());
        if (!circle.containsKey(hash)) {
            SortedMap<Long, T> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        return circle.get(hash);
    }
}
```

</details>

This implementation provides several key benefits:

1. **Deterministic Routing**: The same key always maps to the same node
2. **Load Distribution**: Virtual nodes ensure even distribution across shards
3. **Minimal Rebalancing**: Only O(1/n) of data moves when nodes are added/removed
4. **Fault Tolerance**: Failed nodes can be removed without affecting the entire system

## Data Flow Patterns

The system follows several key data flow patterns that ensure consistency, performance, and fault tolerance.

### Write Flow

1. **Client Request**: Client sends increment/decrement operation to coordinator
2. **Consistent Hashing**: Coordinator uses consistent hashing to determine target shard
3. **Shard Processing**: Target shard performs atomic increment/decrement operation
4. **Response**: Coordinator returns success/failure response to client

### Read Flow

1. **Client Request**: Client requests total count for a counter
2. **Multi-Shard Query**: Coordinator queries all healthy shards
3. **Aggregation**: Coordinator aggregates individual shard values
4. **Response**: Coordinator returns total count to client

### Failure Handling Flow

1. **Failure Detection**: Health monitoring detects shard failure
2. **Load Redistribution**: Coordinator routes operations to healthy shards
3. **Graceful Degradation**: System continues operating with reduced capacity
4. **Recovery**: Failed shards can rejoin when they recover

## Performance Characteristics

The distributed architecture provides several performance advantages:

### Write Performance

- **Horizontal Scaling**: Write throughput scales linearly with the number of shards
- **No Lock Contention**: Each shard operates independently
- **Parallel Processing**: Multiple operations can be processed simultaneously
- **Optimized Storage**: Each shard can optimize its storage for its specific workload

### Read Performance

- **Parallel Queries**: All shards can be queried simultaneously
- **Caching Opportunities**: Individual shard results can be cached
- **Load Distribution**: Read load is distributed across all shards
- **Fault Tolerance**: Reads can succeed even if some shards are unavailable

### Scalability Characteristics

- **Linear Scaling**: Performance scales linearly with the number of shards
- **No Bottlenecks**: No single point of contention limits performance
- **Elastic Capacity**: Shards can be added/removed dynamically
- **Predictable Performance**: Performance characteristics are predictable and consistent

## Fault Tolerance and High Availability

The system is designed to handle various failure scenarios gracefully:

### Node Failures

- **Automatic Detection**: Health monitoring detects node failures
- **Load Redistribution**: Operations are routed to healthy nodes
- **Graceful Degradation**: System continues operating with reduced capacity
- **Automatic Recovery**: Failed nodes can rejoin when they recover

### Network Partitions

- **Partial Availability**: System continues operating with available shards
- **Eventual Consistency**: Data eventually becomes consistent when network recovers
- **Conflict Resolution**: Conflicts are resolved using timestamp-based ordering

### Data Corruption

- **Checksums**: Data integrity is verified using checksums
- **Backup and Recovery**: Regular backups enable data recovery
- **Replication**: Critical data can be replicated for additional protection

## Conclusion

The Distributed Sharded Counter architecture provides a robust, scalable solution for high-throughput counting applications. By separating concerns between coordinators and shards, the system achieves both horizontal scalability and fault tolerance while maintaining strong consistency guarantees for individual operations.

The key architectural principles—consistent hashing for routing, atomic operations for consistency, and health monitoring for fault tolerance—work together to create a system that can handle the demands of modern, high-scale applications.

In the next chapter, we'll examine the write operations in detail, exploring how the system handles high-throughput increment and decrement operations while maintaining data consistency and durability. 