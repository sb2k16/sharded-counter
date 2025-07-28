# Chapter 3: Write Operations: The Complete Flow

## Write Operation Lifecycle

Write operations in the distributed sharded counter system follow a well-defined lifecycle that ensures consistency, performance, and fault tolerance. Each write operation (increment or decrement) goes through several stages from client request to final response.

### Client Request Processing

The coordinator receives HTTP POST requests containing counter operations. The request processing is handled by the `ShardedCounterHandler`:

HTTP requests are processed through the coordinator:

```java
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

For the complete HTTP request processing implementation with error handling, see **Listing 3.1** in the appendix.

The POST request handler parses the operation and routes it to the appropriate handler:

Operations are routed based on their type:

```java
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

For the complete operation routing implementation with validation, see **Listing 3.2** in the appendix.

### Consistent Hashing for Shard Selection

Once the operation is parsed, the coordinator uses consistent hashing to determine which shard should handle the operation:

Consistent hashing determines the target shard:

```java
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

For the complete consistent hashing routing implementation with load balancing, see **Listing 3.3** in the appendix.

The consistent hashing ensures that:
- **Deterministic Routing**: The same counter ID always routes to the same shard
- **Load Distribution**: Operations are evenly distributed across available shards
- **Minimal Rebalancing**: Only O(1/n) of data moves when nodes change

### Shard Processing

The target shard receives the operation and processes it atomically. The shard implements the operation in its `ShardNodeHandler`:

Shard nodes process operations locally:

```java
private FullHttpResponse handleShardedOperation(ShardedCounterOperation operation) throws Exception {
    ShardedCounterResponse response;
    
    switch (operation.getOperationType()) {
        case INCREMENT:
            long newValue = storage.increment(operation.getCounterId(), operation.getDelta());
            response = ShardedCounterResponse.shardSuccess(newValue);
            break;
        case DECREMENT:
            long decrementedValue = storage.decrement(operation.getCounterId(), operation.getDelta());
            response = ShardedCounterResponse.shardSuccess(decrementedValue);
            break;
        case GET_TOTAL:
            long totalValue = storage.get(operation.getCounterId());
            response = ShardedCounterResponse.shardSuccess(totalValue);
            break;
        case GET_SHARD_VALUES:
            long shardValue = storage.get(operation.getCounterId());
            response = ShardedCounterResponse.shardSuccess(shardValue);
            break;
        default:
            response = ShardedCounterResponse.error("Unknown operation type");
    }
    
    return createShardedResponse(HttpResponseStatus.OK, response);
}
```

For the complete shard operation processing implementation with error handling, see **Listing 3.4** in the appendix.

The shard processing provides:
- **Atomic Operations**: Each increment/decrement is atomic
- **Immediate Response**: Operations complete synchronously
- **Error Handling**: Proper error responses for failed operations
- **State Persistence**: Changes are immediately persisted to storage

### Atomic Operations and Consistency

The atomicity of counter operations is critical for data consistency. The implementation uses multiple layers to ensure atomicity:

Atomic operations ensure data consistency:

```java
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

    return newValue;
}
```

For the complete atomic operations implementation with comprehensive error handling, see **Listing 3.5** in the appendix.

The atomicity is guaranteed through several mechanisms:

1. **ConcurrentHashMap.compute()**: Provides atomic read-modify-write operations on the in-memory cache
2. **RocksDB Thread Safety**: RocksDB handles concurrent access safely
3. **Synchronous Writes**: The `setSync(true)` option ensures data is persisted before returning
4. **Exception Handling**: Any failure in the atomic operation is properly handled

### Dual-Layer Storage Architecture

The storage layer uses a dual-layer approach combining in-memory caching with persistent storage:

Dual-layer storage combines memory and persistence:

```java
public class RocksDBStorage implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RocksDBStorage.class);

    private final RocksDB db;
    private final Map<String, Long> inMemoryCache;
    private final String dbPath;

    public RocksDBStorage(String dbPath) throws RocksDBException {
        this.dbPath = dbPath;
        this.inMemoryCache = new ConcurrentHashMap<>();

        // Create directory if it doesn't exist
        File directory = new File(dbPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // Configure RocksDB options
        Options options = new Options();
        options.setCreateIfMissing(true);
        options.setMaxBackgroundJobs(4);
        options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
        options.setMaxWriteBufferNumber(3);
        options.setTargetFileSizeBase(64 * 1024 * 1024); // 64MB

        // Open the database
        this.db = RocksDB.open(options, dbPath);

        // Load existing data into memory
        loadDataIntoMemory();

        logger.info("RocksDB storage initialized at: {}", dbPath);
    }
}
```

</details>

This dual-layer architecture provides:
- **Fast Access**: In-memory cache provides sub-millisecond access
- **Durability**: RocksDB ensures data persistence
- **Recovery**: Data is automatically loaded on startup
- **Performance**: Optimized for high-throughput operations

### Response Handling

The coordinator receives the shard's response and formats it for the client:

<details>
<summary><strong>Response Handling Implementation</strong></summary>

```java
// From ShardedCounterCoordinator.java
private FullHttpResponse createResponse(HttpResponseStatus status, ShardedCounterResponse counterResponse) {
    try {
        String jsonResponse = objectMapper.writeValueAsString(counterResponse);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                io.netty.buffer.Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8));
        
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        
        return response;
    } catch (Exception e) {
        logger.error("Error creating response", e);
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
}
```

</details>

The response handling ensures:
- **Consistent Format**: All responses follow the same JSON format
- **Error Handling**: Proper HTTP status codes for different error conditions
- **Content Type**: Correct MIME type for JSON responses
- **Logging**: Comprehensive logging for debugging and monitoring

## Performance Optimization Strategies

The write operation flow includes several performance optimizations:

### Asynchronous Persistence

For high-throughput scenarios, the system can use asynchronous persistence:

<details>
<summary><strong>Asynchronous Persistence Implementation</strong></summary>

```java
// Asynchronous write-behind with durability guarantees
public class AsyncWriteBehindStorage {
    private final Map<String, Long> inMemoryCache = new ConcurrentHashMap<>();
    private final RocksDBStorage rocksDB;
    private final BlockingQueue<WriteOperation> writeQueue = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<Void>> pendingWrites = new ConcurrentHashMap<>();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong pendingWrites = new AtomicLong(0);
    private final AtomicLong failedWrites = new AtomicLong(0);
    
    // Durability configuration
    private final boolean enableSyncWrites = true;
    private final int maxRetries = 3;
    private final long flushIntervalMs = 1000; // 1 second
    
    public AsyncWriteBehindStorage(String dbPath) throws Exception {
        this.rocksDB = new RocksDBStorage(dbPath);
        startBackgroundWriter();
        startPeriodicFlush();
    }
}
```

</details>

### Connection Pooling

HTTP connections to shards are pooled for better performance:

<details>
<summary><strong>Connection Pooling Implementation</strong></summary>

```java
// HTTP Connection Pool for high-performance HTTP communication
public class HttpConnectionPool {
    private final Map<String, HttpClient> clientPools = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    
    // Configuration
    private final int maxConnectionsPerShard;
    private final Duration connectionTimeout;
    private final Duration requestTimeout;
    private final boolean enableKeepAlive;
    
    public HttpConnectionPool() {
        this(10, Duration.ofSeconds(5), Duration.ofSeconds(30), true);
    }
}
```

</details>

## Error Handling and Retry Logic

The system implements comprehensive error handling and retry logic:

<details>
<summary><strong>Error Handling and Retry Implementation</strong></summary>

```java
// Comprehensive error handling with retry logic
public class WriteOperationRetryHandler {
    private final int maxRetries;
    private final long retryDelayMs;
    private final ExponentialBackoff backoff;
    
    public WriteOperationRetryHandler(int maxRetries, long retryDelayMs) {
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.backoff = new ExponentialBackoff(retryDelayMs, maxRetries);
    }
    
    public CompletableFuture<ShardedCounterResponse> executeWithRetry(
            Supplier<CompletableFuture<ShardedCounterResponse>> operation) {
        
        return operation.get().handle((response, throwable) -> {
            if (throwable != null) {
                return retryOperation(operation, 1);
            }
            return response;
        });
    }
    
    private CompletableFuture<ShardedCounterResponse> retryOperation(
            Supplier<CompletableFuture<ShardedCounterResponse>> operation, 
            int attempt) {
        
        if (attempt > maxRetries) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Max retries exceeded"));
        }
        
        return CompletableFuture.delayedExecutor(
            backoff.getDelay(attempt), TimeUnit.MILLISECONDS)
            .execute(() -> operation.get())
            .handle((response, throwable) -> {
                if (throwable != null) {
                    return retryOperation(operation, attempt + 1);
                }
                return response;
            });
    }
}
```

</details>

## Consistency and Durability Guarantees

The write operations provide several consistency and durability guarantees:

### Atomicity

Each write operation is atomic, ensuring that:
- **All-or-Nothing**: Either the entire operation succeeds or fails
- **No Partial Updates**: No intermediate states are visible
- **Consistent State**: The system remains in a consistent state

### Durability

Write operations are durable through:
- **Synchronous Writes**: Critical operations use synchronous persistence
- **Periodic Flushing**: Regular flush operations ensure data reaches disk
- **Graceful Shutdown**: Remaining operations are processed during shutdown

### Consistency

The system provides:
- **Eventual Consistency**: All shards eventually converge to the same state
- **Read-Your-Writes**: Clients see their own writes immediately
- **Causal Consistency**: Related operations maintain causal ordering

## Conclusion

The write operation flow in the distributed sharded counter system provides a robust, scalable solution for high-throughput counting operations. Through careful design of the request processing, shard routing, atomic operations, and response handling, the system achieves both performance and reliability.

The key design principles—consistent hashing for routing, atomic operations for consistency, and comprehensive error handling for reliability—work together to create a system that can handle the demands of modern, high-scale applications.

In the next chapter, we'll examine the read operations in detail, exploring how the system aggregates data from multiple shards to provide a unified view of distributed counters. 