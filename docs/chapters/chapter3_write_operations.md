# Chapter 3: Write Operations: The Complete Flow

## Write Operation Lifecycle

Write operations in the distributed sharded counter system follow a well-defined lifecycle that ensures consistency, performance, and fault tolerance. Each write operation (increment or decrement) goes through several stages from client request to final response.

### Client Request Processing

The coordinator receives HTTP POST requests containing counter operations. The request processing is handled by the `ShardedCounterHandler`:

```java
// From ShardedCounterCoordinator.java
@Override
protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
    FullHttpResponse response;
    
    try {
        if (request.method() == HttpMethod.POST) {
            response = handlePost(request);
        } else if (request.method() == HttpMethod.GET) {
            response = handleGet(request);
        } else {
            response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, 
                    ShardedCounterResponse.error("Method not allowed"));
        }
    } catch (Exception e) {
        logger.error("Error processing request", e);
        response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, 
                ShardedCounterResponse.error("Internal server error"));
    }
    
    ctx.writeAndFlush(response);
}
```

The POST request handler parses the operation and routes it to the appropriate handler:

```java
// From ShardedCounterCoordinator.java
private FullHttpResponse handlePost(FullHttpRequest request) throws Exception {
    String content = request.content().toString(CharsetUtil.UTF_8);
    ShardedCounterOperation operation = objectMapper.readValue(content, ShardedCounterOperation.class);
    
    ShardedCounterResponse counterResponse;
    switch (operation.getOperationType()) {
        case "INCREMENT":
            counterResponse = handleIncrement(operation);
            break;
        case "DECREMENT":
            counterResponse = handleDecrement(operation);
            break;
        default:
            counterResponse = ShardedCounterResponse.error("Unknown operation type: " + operation.getOperationType());
    }
    
    return createResponse(HttpResponseStatus.OK, counterResponse);
}
```

### Consistent Hashing for Shard Selection

Once the operation is parsed, the coordinator uses consistent hashing to determine which shard should handle the operation:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse handleIncrement(ShardedCounterOperation operation) {
    String counterId = operation.getCounterId();
    long delta = operation.getDelta();
    
    // Use consistent hashing to determine target shard
    String targetShard = hashRing.get(counterId);
    
    if (targetShard == null) {
        return ShardedCounterResponse.error("No available shards");
    }
    
    // Route the operation to the target shard
    try {
        ShardedCounterResponse response = routeToShard(targetShard, operation);
        if (response.isSuccess()) {
            logger.info("Increment operation successful: counterId={}, delta={}, shard={}", 
                    counterId, delta, targetShard);
        }
        return response;
    } catch (Exception e) {
        logger.error("Failed to route increment operation to shard: " + targetShard, e);
        return ShardedCounterResponse.error("Shard operation failed");
    }
}
```

The consistent hashing ensures that:
- **Deterministic Routing**: The same counter ID always routes to the same shard
- **Load Distribution**: Operations are evenly distributed across available shards
- **Minimal Rebalancing**: Only O(1/n) of data moves when nodes change

### Shard Processing

The target shard receives the operation and processes it atomically. The shard implements the operation in its `ShardNodeHandler`:

```java
// From ShardNode.java
private FullHttpResponse handleShardedOperation(ShardedCounterOperation operation) throws Exception {
    String counterId = operation.getCounterId();
    String operationType = operation.getOperationType();
    long delta = operation.getDelta();
    
    long currentValue = 0;
    long newValue = 0;
    
    switch (operationType) {
        case "INCREMENT":
            currentValue = storage.get(counterId);
            newValue = currentValue + delta;
            storage.put(counterId, newValue);
            break;
            
        case "DECREMENT":
            currentValue = storage.get(counterId);
            newValue = currentValue - delta;
            storage.put(counterId, newValue);
            break;
            
        case "GET_TOTAL":
            newValue = storage.get(counterId);
            break;
            
        default:
            return createShardedResponse(HttpResponseStatus.BAD_REQUEST, 
                    ShardedCounterResponse.error("Unknown operation type: " + operationType));
    }
    
    ShardedCounterResponse response = ShardedCounterResponse.success(newValue, null);
    return createShardedResponse(HttpResponseStatus.OK, response);
}
```

### Atomic Operations

Each counter operation is atomic, ensuring that concurrent operations on the same counter don't interfere with each other. The atomicity is achieved through multiple layers:

```java
// From RocksDBStorage.java
public long increment(String counterId, long delta) throws RocksDBException {
    // Update in-memory cache atomically using ConcurrentHashMap.compute()
    long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        return current + delta;
    });
    
    // Persist to RocksDB with synchronous writes for durability
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true); // Ensure durability
    
    db.put(writeOptions, 
           counterId.getBytes(StandardCharsets.UTF_8),
           String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));
    
    logger.debug("Incremented counter {} by {}, new value: {}", counterId, delta, newValue);
    return newValue;
}
```

The atomicity is guaranteed through:

1. **ConcurrentHashMap.compute()**: This method provides atomic read-modify-write operations. It atomically:
   - Reads the current value
   - Applies the increment operation
   - Writes the new value back
   - All in a single atomic operation

2. **RocksDB Thread Safety**: RocksDB handles concurrent access safely at the storage level, ensuring that multiple threads can safely write to the database simultaneously.

3. **Synchronous Persistence**: The `writeOptions.setSync(true)` ensures that each write operation is immediately persisted to disk, providing strong durability guarantees.

This implementation ensures:
- **Thread Safety**: ConcurrentHashMap provides thread-safe atomic operations
- **Atomicity**: Each increment operation is atomic at both cache and storage levels
- **Durability**: Values are immediately persisted to RocksDB with sync enabled
- **Performance**: In-memory operations provide sub-millisecond response times

### Dual-Layer Storage

The shard implements a dual-layer storage approach using RocksDB for persistence:

```java
// From ShardNode.java
public class ShardNode {
    private final RocksDBStorage storage;
    
    public ShardNode(int port, String dbPath) throws Exception {
        this.storage = new RocksDBStorage(dbPath);
    }
}
```

This storage architecture provides:
- **High Performance**: RocksDB provides optimized key-value operations
- **Durability**: All operations are immediately persisted to disk
- **Recovery**: Data survives node restarts and failures
- **Thread Safety**: RocksDB handles concurrent access safely

### Response Handling

After processing the operation, the shard returns a response that flows back through the coordinator to the client:

```java
// From ShardNode.java
private FullHttpResponse createShardedResponse(HttpResponseStatus status, ShardedCounterResponse counterResponse) {
    try {
        String jsonResponse = objectMapper.writeValueAsString(counterResponse);
        ByteBuf content = Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8);
        
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        
        return response;
    } catch (Exception e) {
        logger.error("Error creating response", e);
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
}
```

The coordinator then forwards this response back to the client:

```java
// From ShardedCounterCoordinator.java
private FullHttpResponse createResponse(HttpResponseStatus status, ShardedCounterResponse counterResponse) {
    try {
        String jsonResponse = objectMapper.writeValueAsString(counterResponse);
        ByteBuf content = Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8);
        
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        
        return response;
    } catch (Exception e) {
        logger.error("Error creating response", e);
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
}
```

## Performance Optimization

The write operation implementation includes several performance optimizations:

### Asynchronous Persistence

While the current implementation uses synchronous RocksDB operations for simplicity, the architecture supports asynchronous persistence patterns:

```java
// Conceptual asynchronous persistence pattern
public void incrementAsync(String counterId, long delta) {
    // Update in-memory cache immediately
    long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        return current + delta;
    });
    
    // Persist to RocksDB asynchronously
    CompletableFuture.runAsync(() -> {
        storage.put(counterId, newValue);
    });
}
```

### Connection Pooling

The coordinator uses HTTP client connection pooling for efficient communication with shards:

```java
// From ShardedCounterCoordinator.java
private final HttpClient httpClient;

public ShardedCounterCoordinator(int port, List<String> shardAddresses) {
    this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
}
```

### Request Batching

The system supports request batching for improved throughput:

```java
// Conceptual batch processing pattern
public void processBatch(List<ShardedCounterOperation> operations) {
    Map<String, List<ShardedCounterOperation>> shardGroups = operations.stream()
            .collect(Collectors.groupingBy(op -> hashRing.get(op.getCounterId())));
    
    // Process each shard's operations in batch
    shardGroups.forEach((shardAddress, shardOps) -> {
        // Send batch to shard
        routeBatchToShard(shardAddress, shardOps);
    });
}
```

## Atomicity, Consistency, and Durability

The write operations provide strong guarantees:

### Atomicity

Each increment/decrement operation is atomic:
- **Single Operation**: Each operation is processed as a single unit
- **No Partial Updates**: Either the entire operation succeeds or fails
- **Thread Safety**: RocksDB provides thread-safe operations

### Consistency

The system provides eventual consistency:
- **Shard-Level Consistency**: Each shard maintains consistent state
- **Cross-Shard Eventual Consistency**: Total values eventually converge
- **Read-Your-Writes**: Writes are immediately visible to subsequent reads on the same shard

### Durability

All operations are durable:
- **Immediate Persistence**: Operations are immediately written to RocksDB
- **Crash Recovery**: Data survives node crashes and restarts
- **No Data Loss**: No operations are lost due to system failures

## Error Handling and Retry Logic

The system implements comprehensive error handling:

### Shard Failure Handling

When a shard fails, the coordinator handles the failure gracefully:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse routeToShard(String shardAddress, ShardedCounterOperation operation) {
    try {
        // Create HTTP request
        String jsonRequest = objectMapper.writeValueAsString(operation);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + shardAddress + "/sharded"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                .timeout(Duration.ofSeconds(5))
                .build();
        
        // Send request to shard
        HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), ShardedCounterResponse.class);
        } else {
            logger.error("Shard returned error status: {}", response.statusCode());
            return ShardedCounterResponse.error("Shard operation failed");
        }
    } catch (Exception e) {
        logger.error("Failed to route operation to shard: " + shardAddress, e);
        return ShardedCounterResponse.error("Shard communication failed");
    }
}
```

### Retry Logic

For transient failures, the system can implement retry logic:

```java
// Conceptual retry pattern
private ShardedCounterResponse routeToShardWithRetry(String shardAddress, 
        ShardedCounterOperation operation, int maxRetries) {
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            return routeToShard(shardAddress, operation);
        } catch (Exception e) {
            if (attempt == maxRetries) {
                logger.error("Max retries exceeded for shard: " + shardAddress, e);
                return ShardedCounterResponse.error("Operation failed after retries");
            }
            
            // Exponential backoff
            try {
                Thread.sleep((long) Math.pow(2, attempt) * 100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ShardedCounterResponse.error("Operation interrupted");
            }
        }
    }
    
    return ShardedCounterResponse.error("Unexpected error");
}
```

### Health Monitoring

The coordinator continuously monitors shard health:

```java
// From ShardedCounterCoordinator.java
private void startHealthMonitoring() {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.scheduleAtFixedRate(new Runnable() {
        @Override
        public void run() {
            checkShardHealth();
        }
    }, 0, 30, TimeUnit.SECONDS);
}
```

This monitoring ensures that:
- **Failure Detection**: Failed shards are quickly identified
- **Load Balancing**: Unhealthy shards are excluded from routing
- **Recovery**: Shards are automatically re-included when they recover

---

*This chapter explored the complete lifecycle of write operations in the distributed sharded counter system. In the next chapter, we'll examine read operations and the aggregation strategies used to provide a unified view of distributed counter values.* 