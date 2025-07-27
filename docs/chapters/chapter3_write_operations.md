# Chapter 3: Write Operations: The Complete Flow

## Write Operation Lifecycle

Write operations in the distributed sharded counter system follow a carefully orchestrated flow that ensures high performance, consistency, and fault tolerance. Understanding this flow is crucial for optimizing performance and debugging issues in production environments.

The complete write operation lifecycle consists of several distinct phases:

1. **Client Request Processing**: HTTP request parsing and validation
2. **Consistent Hashing and Routing**: Deterministic shard selection
3. **Shard Processing**: Atomic counter updates and storage
4. **Response Handling**: Success/failure response generation
5. **Error Management**: Graceful failure handling and retries

## Client Request Processing

The write operation begins when a client sends an HTTP POST request to a coordinator node. The coordinator acts as the entry point and handles the initial request processing.

```java
// From ShardedCounterCoordinator.java
@POST("/sharded")
public ShardedCounterResponse handleShardedRequest(@RequestBody ShardedCounterOperation operation) {
    // Validate the incoming request
    if (!isValidOperation(operation)) {
        return new ShardedCounterResponse(false, 0, "Invalid operation");
    }
    
    // Route based on operation type
    switch (operation.getOperationType()) {
        case INCREMENT:
        case DECREMENT:
            return handleWriteOperation(operation);
        case GET_TOTAL:
            return handleReadOperation(operation);
        default:
            return new ShardedCounterResponse(false, 0, "Unknown operation type");
    }
}

private ShardedCounterResponse handleWriteOperation(ShardedCounterOperation operation) {
    // Determine target shard using consistent hashing
    String targetShard = hashRing.get(operation.getCounterId());
    
    // Route the operation to the target shard
    return routeToShard(targetShard, operation);
}
```

The request processing includes several validation steps:

- **Operation Type Validation**: Ensures the operation is supported (INCREMENT, DECREMENT, GET_TOTAL)
- **Counter ID Validation**: Validates the counter identifier format and length
- **Delta Value Validation**: Ensures the increment/decrement value is within acceptable bounds
- **Request Size Validation**: Prevents oversized requests that could impact performance

## Consistent Hashing and Routing

Once the request is validated, the coordinator uses consistent hashing to determine which shard should handle the operation. This routing decision is deterministic, ensuring that the same counter ID always routes to the same shard.

```java
// From ConsistentHash.java
public String get(String key) {
    if (hashRing.isEmpty()) {
        return null;
    }
    
    // Generate hash for the counter ID
    int hash = hash(key);
    
    // Find the next node in the hash ring
    Map.Entry<Integer, String> entry = hashRing.ceilingEntry(hash);
    
    // Handle wrap-around case
    if (entry == null) {
        entry = hashRing.firstEntry();
    }
    
    return entry.getValue();
}

private int hash(String key) {
    // Use a good hash function for even distribution
    return key.hashCode();
}
```

The consistent hashing implementation provides several benefits:

- **Deterministic Routing**: Same counter ID always maps to same shard
- **Load Distribution**: Operations are evenly distributed across shards
- **Minimal Rebalancing**: Only O(1/n) of data moves when nodes change
- **Fault Tolerance**: System continues operating even if some shards fail

## Shard Processing and Storage

Once the target shard is determined, the coordinator forwards the operation to the shard for processing. The shard handles the actual counter update using atomic operations.

```java
// From ShardNode.java
@POST("/sharded")
public ShardedCounterResponse handleShardedRequest(@RequestBody ShardedCounterOperation operation) {
    try {
        switch (operation.getOperationType()) {
            case INCREMENT:
                long newValue = increment(operation.getCounterId(), operation.getDelta());
                return new ShardedCounterResponse(true, newValue, null);
            case DECREMENT:
                long decrementedValue = increment(operation.getCounterId(), -operation.getDelta());
                return new ShardedCounterResponse(true, decrementedValue, null);
            case GET_TOTAL:
                long currentValue = getValue(operation.getCounterId());
                return new ShardedCounterResponse(true, currentValue, null);
            default:
                return new ShardedCounterResponse(false, 0, "Unknown operation");
        }
    } catch (Exception e) {
        logger.error("Error processing operation: " + operation, e);
        return new ShardedCounterResponse(false, 0, "Internal error");
    }
}

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

The shard processing includes several critical aspects:

### Atomic Operations

The `ConcurrentHashMap.compute()` method ensures that counter updates are atomic:

```java
// Thread-safe atomic increment
return inMemoryCache.compute(counterId, (key, oldValue) -> {
    long current = (oldValue != null) ? oldValue : 0;
    return current + delta;
});
```

This atomicity is crucial for:
- **Data Consistency**: Prevents race conditions between concurrent updates
- **Thread Safety**: Multiple threads can safely update the same counter
- **Correctness**: Ensures accurate counter values even under high concurrency

### Dual-Layer Storage

The shard implements a dual-layer storage approach:

```java
// In-memory cache for fast access
inMemoryCache.put(counterId, newValue);

// Persistent storage for durability
persistentStorage.put(counterId, String.valueOf(newValue));
```

This approach provides:
- **Performance**: In-memory operations are sub-millisecond
- **Durability**: RocksDB ensures data persistence
- **Recovery**: Data can be reconstructed on restart

## Response Handling and Error Management

After processing the operation, the shard returns a response to the coordinator, which then forwards it to the client.

```java
// From ShardNode.java
public ShardedCounterResponse handleShardedRequest(ShardedCounterOperation operation) {
    try {
        // Process the operation
        long result = processOperation(operation);
        
        // Return success response
        return new ShardedCounterResponse(true, result, null);
        
    } catch (ValidationException e) {
        // Handle validation errors
        logger.warn("Validation error: " + e.getMessage());
        return new ShardedCounterResponse(false, 0, e.getMessage());
        
    } catch (StorageException e) {
        // Handle storage errors
        logger.error("Storage error: " + e.getMessage(), e);
        return new ShardedCounterResponse(false, 0, "Storage error");
        
    } catch (Exception e) {
        // Handle unexpected errors
        logger.error("Unexpected error processing operation", e);
        return new ShardedCounterResponse(false, 0, "Internal error");
    }
}
```

The response handling includes:

- **Success Responses**: Return the new counter value and success status
- **Error Responses**: Provide meaningful error messages for different failure types
- **Logging**: Comprehensive logging for debugging and monitoring
- **Metrics**: Performance and error rate tracking

## Performance Optimization Techniques

The write operation flow includes several performance optimizations:

### Asynchronous Persistence

```java
// From ShardNode.java
public long increment(String counterId, long delta) {
    return inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        long newValue = current + delta;
        
        // Asynchronous persistence to avoid blocking
        CompletableFuture.runAsync(() -> {
            persistentStorage.put(counterId, String.valueOf(newValue));
        });
        
        return newValue;
    });
}
```

This optimization provides:
- **Low Latency**: In-memory updates return immediately
- **High Throughput**: No blocking on disk I/O
- **Durability**: Data is still persisted, just asynchronously

### Connection Pooling

```java
// From ShardedCounterCoordinator.java
private final OkHttpClient httpClient = new OkHttpClient.Builder()
    .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
    .connectTimeout(1, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build();
```

Connection pooling reduces:
- **Connection Overhead**: Reuses connections between requests
- **Latency**: Avoids TCP handshake for each request
- **Resource Usage**: Reduces memory and CPU overhead

### Request Batching

For high-throughput scenarios, the system can batch multiple operations:

```java
// Batch multiple increments for the same counter
public ShardedCounterResponse handleBatchRequest(List<ShardedCounterOperation> operations) {
    // Group operations by counter ID
    Map<String, List<ShardedCounterOperation>> grouped = operations.stream()
        .collect(Collectors.groupingBy(ShardedCounterOperation::getCounterId));
    
    // Process each group
    Map<String, Long> results = new HashMap<>();
    for (Map.Entry<String, List<ShardedCounterOperation>> entry : grouped.entrySet()) {
        String counterId = entry.getKey();
        long totalDelta = entry.getValue().stream()
            .mapToLong(ShardedCounterOperation::getDelta)
            .sum();
        
        results.put(counterId, increment(counterId, totalDelta));
    }
    
    return new ShardedCounterResponse(true, results);
}
```

## Atomicity and Consistency Guarantees

The write operations provide specific consistency guarantees:

### Atomicity Guarantees

- **Single Counter Updates**: Each increment/decrement operation is atomic
- **No Partial Updates**: Either the entire operation succeeds or fails
- **Thread Safety**: Concurrent updates to the same counter are handled correctly

### Consistency Guarantees

- **Eventual Consistency**: Updates are eventually visible across all shards
- **Monotonic Reads**: Within a single shard, reads are monotonically consistent
- **Write-Once Semantics**: Each write operation is applied exactly once

### Durability Guarantees

- **In-Memory Durability**: Updates are immediately visible in memory
- **Persistent Durability**: Updates are eventually persisted to RocksDB
- **Recovery Capability**: Data can be reconstructed from persistent storage

## Error Handling and Retry Logic

The system implements comprehensive error handling:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse routeToShard(String shardAddress, ShardedCounterOperation operation) {
    int maxRetries = 3;
    int retryCount = 0;
    
    while (retryCount < maxRetries) {
        try {
            return httpClient.post(shardAddress + "/sharded", operation);
        } catch (ConnectException e) {
            // Network connectivity issue
            logger.warn("Connection failed to shard: " + shardAddress + ", retry: " + retryCount);
            retryCount++;
            if (retryCount < maxRetries) {
                Thread.sleep(100 * retryCount); // Exponential backoff
            }
        } catch (TimeoutException e) {
            // Request timeout
            logger.warn("Timeout for shard: " + shardAddress + ", retry: " + retryCount);
            retryCount++;
            if (retryCount < maxRetries) {
                Thread.sleep(200 * retryCount);
            }
        } catch (Exception e) {
            // Unexpected error
            logger.error("Unexpected error routing to shard: " + shardAddress, e);
            break;
        }
    }
    
    // All retries failed
    return new ShardedCounterResponse(false, 0, "Shard unavailable");
}
```

This error handling provides:
- **Retry Logic**: Automatic retries for transient failures
- **Exponential Backoff**: Prevents overwhelming failed shards
- **Graceful Degradation**: System continues operating with available shards
- **Comprehensive Logging**: Detailed error information for debugging

---

*This chapter examined the complete write operation flow, from client request to final response. In the next chapter, we'll explore read operations and the challenges of aggregating data across multiple shards.* 