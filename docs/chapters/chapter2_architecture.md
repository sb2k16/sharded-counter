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

The consistent hashing implementation ensures that:
- **Deterministic Routing**: The same counter ID always routes to the same shard
- **Load Distribution**: Operations are evenly distributed across available shards
- **Minimal Rebalancing**: Only O(1/n) of data moves when nodes change
- **Virtual Nodes**: Improved load distribution through virtual node replication

### Operation Aggregation for Reads

For read operations, the coordinator must query all shards and aggregate their individual values to provide the total count. This is implemented in the `handleGetTotal` method:

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

This aggregation process is crucial for providing a unified view of the distributed counter, but it introduces some latency as the coordinator must wait for responses from all shards.

## Shard Nodes: The Workhorses of Storage

Shard nodes are responsible for the actual storage and processing of counter values. Each shard operates independently, maintaining its own storage layer and processing counter operations in isolation. This independence is key to the system's scalability and fault tolerance.

### Storage Layer Architecture

Each shard implements a dual-layer storage approach combining in-memory caching with persistent storage:

```java
// From ShardNode.java
public class ShardNode {
    private final int port;
    private final RocksDBStorage storage;
    private final ObjectMapper objectMapper;
    
    public ShardNode(int port, String dbPath) throws Exception {
        this.port = port;
        this.storage = new RocksDBStorage(dbPath);
        this.objectMapper = new ObjectMapper();
    }
}
```

The storage architecture provides:
- **Fast Access**: RocksDB for high-performance key-value storage
- **Durability**: Persistent storage for data reliability
- **Recovery**: Automatic data reconstruction on startup

### Atomic Counter Operations

Shard nodes implement atomic counter operations using a combination of thread-safe in-memory caching and RocksDB's atomic operations:

```java
// From RocksDBStorage.java
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

The atomicity is achieved through multiple layers:

1. **ConcurrentHashMap.compute()**: Provides atomic read-modify-write operations on the in-memory cache
2. **RocksDB Thread Safety**: RocksDB handles concurrent access safely at the storage level
3. **Synchronous Persistence**: `writeOptions.setSync(true)` ensures immediate disk persistence

This implementation ensures:
- **Thread Safety**: ConcurrentHashMap provides thread-safe atomic operations
- **Atomicity**: Each increment operation is atomic at both cache and storage levels
- **Durability**: Values are immediately persisted to RocksDB with sync enabled
- **Performance**: In-memory operations provide sub-millisecond response times

## Consistent Hashing: The Routing Engine

The consistent hashing implementation is the heart of the routing system, ensuring deterministic and efficient distribution of counter operations across shards.

```java
// From ConsistentHash.java
public class ConsistentHash<T> {
    private final HashFunction hashFunction;
    private final int numberOfReplicas;
    private final SortedMap<Long, T> circle = new TreeMap<>();
    private final ConcurrentMap<T, Boolean> nodes = new ConcurrentHashMap<>();
    
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
private void checkShardHealth() {
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String address = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        try {
            // Send health check request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + address + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                shardInfo.setHealthy(true);
                shardInfo.setLastSeen(System.currentTimeMillis());
            } else {
                shardInfo.setHealthy(false);
            }
        } catch (Exception e) {
            logger.warn("Health check failed for shard: {}", address);
            shardInfo.setHealthy(false);
        }
    }
}
```

### Data Recovery

Shards automatically recover their state on startup by loading data from persistent storage:

```java
// From RocksDBStorage.java
public class RocksDBStorage {
    private final RocksDB db;
    
    public RocksDBStorage(String dbPath) throws Exception {
        Options options = new Options();
        options.setCreateIfMissing(true);
        this.db = RocksDB.open(options, dbPath);
    }
    
    public long get(String key) {
        try {
            byte[] value = db.get(key.getBytes());
            return value != null ? Long.parseLong(new String(value)) : 0;
        } catch (Exception e) {
            logger.error("Error reading from storage", e);
            return 0;
        }
    }
    
    public void put(String key, long value) {
        try {
            db.put(key.getBytes(), String.valueOf(value).getBytes());
        } catch (Exception e) {
            logger.error("Error writing to storage", e);
        }
    }
}
```

## Performance Characteristics

The architecture provides several performance benefits:

### Write Performance

- **Parallel Processing**: Writes are distributed across multiple shards
- **No Lock Contention**: Each shard operates independently
- **Optimized Storage**: RocksDB provides high-performance operations
- **Horizontal Scaling**: Add more shards to increase write capacity

### Read Performance

- **Parallel Queries**: Coordinator queries all shards in parallel
- **Caching**: RocksDB block cache provides fast access to hot data
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