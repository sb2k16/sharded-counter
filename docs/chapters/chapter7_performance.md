# Chapter 7: Performance Analysis and Optimization

## Throughput Analysis

The distributed sharded counter system achieves remarkable performance improvements over traditional database approaches. Understanding the performance characteristics is crucial for capacity planning and optimization.

### Mathematical Performance Model

**Purpose**: This mathematical model helps predict system performance and determine optimal shard counts for different workloads. It provides a theoretical foundation for understanding how the distributed architecture scales compared to traditional single-database approaches.

**Key Concepts**:
- **Traditional Database Limitation**: Single-threaded operations with lock contention
- **Distributed Scaling**: Parallel processing across multiple shards
- **Write vs Read Scaling**: Writes scale linearly, reads are limited by aggregation overhead
- **Optimal Shard Calculation**: Mathematical formula to determine required shard count

**Why This Matters**: Understanding these performance characteristics helps in capacity planning, cost optimization, and system design decisions. The model provides quantitative insights into when to scale and how much performance improvement to expect.

```java
// Performance model for distributed sharded counter
public class PerformanceModel {
    private final int numShards;
    private final double inMemoryUpdateTime; // ~0.001ms
    private final double networkLatency; // ~1-10ms
    private final double diskWriteTime; // ~1-5ms
    
    public PerformanceModel(int numShards, double networkLatency, double diskWriteTime) {
        this.numShards = numShards;
        this.inMemoryUpdateTime = 0.001; // 1 microsecond
        this.networkLatency = networkLatency;
        this.diskWriteTime = diskWriteTime;
    }
    
    // Traditional database throughput (single-threaded)
    public double calculateTraditionalThroughput() {
        double totalTime = 0.001 + diskWriteTime + networkLatency;
        return 1.0 / totalTime; // Operations per second
    }
    
    // Distributed sharded counter throughput (parallel)
    public double calculateDistributedWriteThroughput() {
        // Writes can be parallelized across shards
        return numShards * (1.0 / (inMemoryUpdateTime + diskWriteTime));
    }
    
    // Read throughput (limited by slowest shard)
    public double calculateDistributedReadThroughput() {
        // Reads must aggregate from all shards
        double slowestShardTime = inMemoryUpdateTime + networkLatency;
        return 1.0 / (slowestShardTime * numShards);
    }
    
    // Optimal shard count calculation
    public int calculateOptimalShardCount(double targetThroughput) {
        return (int) Math.ceil(targetThroughput / (1.0 / (inMemoryUpdateTime + diskWriteTime)));
    }
}
```

### Real-World Performance Characteristics

- **Write Throughput**: Scales linearly with the number of shards (10,000-100,000 ops/sec per shard)
- **Read Throughput**: Limited by the slowest shard and network aggregation (1,000-10,000 ops/sec total)
- **Latency**: In-memory operations are sub-millisecond; network and aggregation add 1-10ms overhead

## Advanced Latency Optimization Strategies

### 1. Connection Pooling and HTTP Optimization

**Purpose**: HTTP connection pooling significantly reduces the overhead of establishing new connections for each request. This optimization is crucial for high-throughput scenarios where the cost of TCP handshakes and SSL negotiations can become a major bottleneck.

**Key Benefits**:
- **Reduced Latency**: Eliminates TCP handshake overhead (typically 1-3 RTTs)
- **Higher Throughput**: Reuses connections, allowing more requests per second
- **Resource Efficiency**: Reduces CPU and memory overhead of connection management
- **Better Reliability**: Maintains persistent connections with keep-alive

**Implementation Strategy**: The optimized HTTP client uses connection pooling with configurable pool sizes, connection timeouts, and automatic connection reuse. It also implements proper error handling and retry logic for failed connections.

**When to Use**: This optimization is essential for any production deployment where you expect more than a few hundred requests per second. The benefits become more pronounced as the request rate increases.

```java
// Enhanced HTTP client with connection pooling
public class OptimizedHttpClient {
    private final HttpClient httpClient;
    private final Map<String, ConnectionPool> connectionPools;
    
    public OptimizedHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .connectionPool(ConnectionPool.of(100, Duration.ofMinutes(5)))
                .executor(Executors.newFixedThreadPool(50))
                .build();
        
        this.connectionPools = new ConcurrentHashMap<>();
    }
    
    public CompletableFuture<ShardedCounterResponse> sendAsync(
            String shardAddress, ShardedCounterOperation operation) {
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + shardAddress + "/sharded"))
                .header("Content-Type", "application/json")
                .header("Connection", "keep-alive")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(operation)))
                .build();
        
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return objectMapper.readValue(response.body(), 
                                ShardedCounterResponse.class);
                    } else {
                        throw new RuntimeException("HTTP error: " + response.statusCode());
                    }
                });
    }
}
```

### 2. Request Batching and Aggregation

**Purpose**: Request batching combines multiple operations into a single network request, dramatically improving throughput by reducing network overhead and allowing the system to process operations more efficiently. However, this optimization introduces significant data loss risks that must be carefully managed.

**Key Benefits**:
- **Higher Throughput**: Reduces network round trips by 10-100x
- **Better Resource Utilization**: More efficient use of network bandwidth and CPU
- **Reduced Latency**: Fewer network calls mean lower overall latency
- **Cost Efficiency**: Lower network costs in cloud environments

**Critical Data Loss Risks**:
- **Batch Failure**: If a batch fails, all operations in that batch could be lost
- **Network Partitions**: Temporary network issues can cause entire batches to fail
- **Shard Failures**: A shard failure during batch processing can lose multiple operations
- **Memory Pressure**: High memory usage can cause batch processing to fail

**Risk Mitigation Strategy**: The implementation uses a multi-layered approach to prevent data loss:
1. **Acknowledgment Tracking**: Each operation gets a unique ID and acknowledgment future
2. **Retry Logic**: Failed batches are retried with exponential backoff
3. **Individual Fallback**: If batch fails, operations are processed individually
4. **Synchronous Persistence**: Critical operations use synchronous writes for durability
5. **Comprehensive Monitoring**: Real-time tracking of batch success rates and failures

**When to Use**: Batching is most effective for high-throughput scenarios where you can tolerate some latency in exchange for much higher throughput. It's particularly useful for bulk operations or when you have many small operations that can be grouped together.

**Configuration Considerations**:
- **Batch Size**: Larger batches = higher throughput but higher risk
- **Batch Timeout**: How long to wait before processing a partial batch
- **Retry Strategy**: Number of retries and backoff timing
- **Durability Level**: Whether to use synchronous or asynchronous persistence

```java
// Batch processing for high-throughput scenarios with durability guarantees
public class BatchProcessor {
    private final Map<String, List<ShardedCounterOperation>> batchQueue = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingBatches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final int maxBatchSize = 100;
    private final long maxBatchDelay = 10; // milliseconds
    private final AtomicLong failedBatches = new AtomicLong(0);
    private final AtomicLong successfulBatches = new AtomicLong(0);
    
    // Durability configuration
    private final boolean enableDurability = true;
    private final int maxRetries = 3;
    private final long retryDelayMs = 100;
    
    public void submitOperation(String counterId, ShardedCounterOperation operation) {
        // Create a future for this operation to track completion
        CompletableFuture<Void> operationFuture = new CompletableFuture<>();
        
        batchQueue.computeIfAbsent(counterId, k -> new ArrayList<>()).add(operation);
        
        // Store the future for acknowledgment
        String batchKey = counterId + "_" + System.currentTimeMillis();
        pendingBatches.put(batchKey, operationFuture);
        
        // Trigger batch processing if queue is full
        List<ShardedCounterOperation> batch = batchQueue.get(counterId);
        if (batch.size() >= maxBatchSize) {
            processBatchWithDurability(counterId, batch, batchKey);
        }
    }
    
    private void processBatchWithDurability(String counterId, List<ShardedCounterOperation> operations, String batchKey) {
        CompletableFuture<Void> batchFuture = pendingBatches.get(batchKey);
        
        // Calculate total delta
        long totalDelta = operations.stream()
                .mapToLong(ShardedCounterOperation::getDelta)
                .sum();
        
        // Create single batched operation
        ShardedCounterOperation batchedOp = new ShardedCounterOperation();
        batchedOp.setCounterId(counterId);
        batchedOp.setOperationType("INCREMENT");
        batchedOp.setDelta(totalDelta);
        
        // Send to appropriate shard with retry logic
        String targetShard = hashRing.get(counterId);
        
        CompletableFuture<ShardedCounterResponse> responseFuture = 
            sendBatchWithRetry(targetShard, batchedOp, maxRetries);
        
        responseFuture.thenAccept(response -> {
            if (response.isSuccess()) {
                // Batch successful - acknowledge all operations
                batchFuture.complete(null);
                successfulBatches.incrementAndGet();
                logger.info("Batch processed successfully: {} operations, total delta: {}", 
                    operations.size(), totalDelta);
            } else {
                // Batch failed - handle failure
                handleBatchFailure(counterId, operations, batchKey, batchFuture);
            }
        }).exceptionally(throwable -> {
            // Exception occurred - handle failure
            handleBatchFailure(counterId, operations, batchKey, batchFuture);
            return null;
        });
        
        // Clear the batch from queue
        batchQueue.remove(counterId);
    }
    
    private CompletableFuture<ShardedCounterResponse> sendBatchWithRetry(
            String targetShard, ShardedCounterOperation operation, int remainingRetries) {
        
        return sendBatchToShard(targetShard, operation)
            .exceptionally(throwable -> {
                if (remainingRetries > 0) {
                    logger.warn("Batch failed, retrying... ({} retries left)", remainingRetries);
                    
                    // Exponential backoff
                    try {
                        Thread.sleep(retryDelayMs * (maxRetries - remainingRetries + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    return sendBatchWithRetry(targetShard, operation, remainingRetries - 1).join();
                } else {
                    logger.error("Batch failed after all retries", throwable);
                    throw new RuntimeException("Batch processing failed", throwable);
                }
            });
    }
    
    private void handleBatchFailure(String counterId, List<ShardedCounterOperation> operations, 
                                   String batchKey, CompletableFuture<Void> batchFuture) {
        failedBatches.incrementAndGet();
        logger.error("Batch failed for counter: {}, operations: {}", counterId, operations.size());
        
        if (enableDurability) {
            // Re-process operations individually for durability
            processOperationsIndividually(counterId, operations);
        }
        
        // Complete the future with failure
        batchFuture.completeExceptionally(new RuntimeException("Batch processing failed"));
        pendingBatches.remove(batchKey);
    }
    
    private void processOperationsIndividually(String counterId, List<ShardedCounterOperation> operations) {
        logger.info("Processing {} operations individually for durability", operations.size());
        
        List<CompletableFuture<ShardedCounterResponse>> futures = new ArrayList<>();
        
        for (ShardedCounterOperation operation : operations) {
            CompletableFuture<ShardedCounterResponse> future = 
                sendOperationIndividually(counterId, operation);
            futures.add(future);
        }
        
        // Wait for all individual operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> logger.info("All individual operations completed"))
            .exceptionally(throwable -> {
                logger.error("Some individual operations failed", throwable);
                return null;
            });
    }
    
    private CompletableFuture<ShardedCounterResponse> sendOperationIndividually(
            String counterId, ShardedCounterOperation operation) {
        
        String targetShard = hashRing.get(counterId);
        
        return sendBatchToShard(targetShard, operation)
            .exceptionally(throwable -> {
                logger.error("Individual operation failed: {}", operation, throwable);
                // Return error response
                return ShardedCounterResponse.error("Operation failed: " + throwable.getMessage());
            });
    }
    
    public void startBatchScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            // Process all pending batches that have exceeded max delay
            long currentTime = System.currentTimeMillis();
            
            batchQueue.forEach((counterId, operations) -> {
                if (!operations.isEmpty()) {
                    // Check if batch has been waiting too long
                    String batchKey = counterId + "_" + currentTime;
                    processBatchWithDurability(counterId, new ArrayList<>(operations), batchKey);
                }
            });
        }, maxBatchDelay, maxBatchDelay, TimeUnit.MILLISECONDS);
    }
    
    // Monitoring and metrics
    public BatchMetrics getBatchMetrics() {
        return new BatchMetrics(
            successfulBatches.get(),
            failedBatches.get(),
            batchQueue.values().stream().mapToInt(List::size).sum(),
            pendingBatches.size()
        );
    }
    
    public static class BatchMetrics {
        private final long successfulBatches;
        private final long failedBatches;
        private final int queuedOperations;
        private final int pendingBatches;
        
        public BatchMetrics(long successfulBatches, long failedBatches, 
                          int queuedOperations, int pendingBatches) {
            this.successfulBatches = successfulBatches;
            this.failedBatches = failedBatches;
            this.queuedOperations = queuedOperations;
            this.pendingBatches = pendingBatches;
        }
        
        public double getSuccessRate() {
            long total = successfulBatches + failedBatches;
            return total > 0 ? (double) successfulBatches / total : 1.0;
        }
        
        // Getters...
    }
}
```

### 3. Asynchronous Processing with Durability Guarantees

**Purpose**: Asynchronous write-behind processing allows the system to return immediately to clients while persisting data in the background. This dramatically improves perceived latency and throughput, but requires careful management of durability guarantees to prevent data loss.

**Key Benefits**:
- **Low Latency**: Operations return immediately without waiting for disk I/O
- **High Throughput**: Can process many more operations per second
- **Better User Experience**: Responsive system even under high load
- **Resource Efficiency**: Better CPU and memory utilization

**Critical Durability Challenges**:
- **Data Loss Risk**: Operations in memory can be lost if the system crashes
- **Consistency Issues**: In-memory state may not match persisted state
- **Recovery Complexity**: System must recover properly after failures
- **Memory Pressure**: High memory usage can cause data loss

**Durability Strategy**: The implementation uses a multi-layered approach to ensure data safety:
1. **Immediate Acknowledgment**: Return success to client immediately for responsiveness
2. **Background Persistence**: Write data to disk asynchronously for durability
3. **Retry Logic**: Failed writes are retried with exponential backoff
4. **Synchronous Fallback**: Critical operations can use synchronous writes
5. **Periodic Flushing**: Regular flush operations ensure data reaches disk
6. **Graceful Shutdown**: Process remaining operations during shutdown

**When to Use**: Asynchronous processing is ideal for high-throughput scenarios where you can tolerate eventual consistency in exchange for much better performance. It's particularly effective for write-heavy workloads where most operations are independent.

**Configuration Trade-offs**:
- **Performance vs Durability**: Asynchronous = better performance, synchronous = better durability
- **Memory Usage**: More memory needed to hold pending operations
- **Recovery Time**: Longer recovery time after failures
- **Complexity**: More complex failure handling and recovery logic

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
    
    public CompletableFuture<Long> increment(String counterId, long delta) {
        CompletableFuture<Long> resultFuture = new CompletableFuture<>();
        
        // Immediate in-memory update
        long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            return current + delta;
        });
        
        // Create write operation with acknowledgment
        WriteOperation writeOp = new WriteOperation(counterId, newValue, resultFuture);
        
        // Queue for background persistence
        writeQueue.offer(writeOp);
        pendingWrites.incrementAndGet();
        
        // Return the new value immediately
        resultFuture.complete(newValue);
        
        return resultFuture;
    }
    
    private void startBackgroundWriter() {
        writeExecutor.submit(() -> {
            List<WriteOperation> batch = new ArrayList<>();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Collect batch of operations
                    WriteOperation op = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (op != null) {
                        batch.add(op);
                        
                        // Collect more operations for batching
                        writeQueue.drainTo(batch, 99); // Up to 100 total
                        
                        // Persist batch with durability
                        persistBatchWithDurability(batch);
                        pendingWrites.addAndGet(-batch.size());
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    private void persistBatchWithDurability(List<WriteOperation> operations) {
        try (WriteBatch batch = new WriteBatch()) {
            for (WriteOperation op : operations) {
                batch.put(op.getKey().getBytes(), 
                         String.valueOf(op.getValue()).getBytes());
            }
            
            WriteOptions writeOptions = new WriteOptions();
            writeOptions.setSync(enableSyncWrites); // Synchronous for durability
            
            // Retry logic for durability
            boolean persisted = false;
            Exception lastException = null;
            
            for (int attempt = 0; attempt < maxRetries && !persisted; attempt++) {
                try {
                    rocksDB.getDb().write(writeOptions, batch);
                    persisted = true;
                    
                    // Acknowledge all operations in batch
                    for (WriteOperation op : operations) {
                        op.getResultFuture().complete(null);
                    }
                    
                    logger.debug("Persisted batch of {} operations", operations.size());
                    
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Batch persistence failed, attempt {}/{}", attempt + 1, maxRetries, e);
                    
                    if (attempt < maxRetries - 1) {
                        try {
                            Thread.sleep(100 * (attempt + 1)); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            
            if (!persisted) {
                failedWrites.addAndGet(operations.size());
                logger.error("Failed to persist batch after {} attempts", maxRetries, lastException);
                
                // Complete futures with failure
                for (WriteOperation op : operations) {
                    op.getResultFuture().completeExceptionally(lastException);
                }
            }
        } catch (Exception e) {
            logger.error("Error creating write batch", e);
            failedWrites.addAndGet(operations.size());
            
            // Complete futures with failure
            for (WriteOperation op : operations) {
                op.getResultFuture().completeExceptionally(e);
            }
        }
    }
    
    private void startPeriodicFlush() {
        ScheduledExecutorService flushExecutor = Executors.newSingleThreadScheduledExecutor();
        flushExecutor.scheduleAtFixedRate(() -> {
            try {
                rocksDB.flush();
                logger.debug("Periodic flush completed");
            } catch (Exception e) {
                logger.error("Periodic flush failed", e);
            }
        }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    // Graceful shutdown with durability
    public CompletableFuture<Void> shutdown() {
        CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();
        
        writeExecutor.submit(() -> {
            try {
                // Process remaining operations
                List<WriteOperation> remainingOps = new ArrayList<>();
                writeQueue.drainTo(remainingOps);
                
                if (!remainingOps.isEmpty()) {
                    logger.info("Processing {} remaining operations during shutdown", remainingOps.size());
                    persistBatchWithDurability(remainingOps);
                }
                
                // Final flush
                rocksDB.flush();
                
                writeExecutor.shutdown();
                shutdownFuture.complete(null);
                
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
                shutdownFuture.completeExceptionally(e);
            }
        });
        
        return shutdownFuture;
    }
    
    private static class WriteOperation {
        private final String key;
        private final long value;
        private final CompletableFuture<Void> resultFuture;
        
        public WriteOperation(String key, long value, CompletableFuture<Void> resultFuture) {
            this.key = key;
            this.value = value;
            this.resultFuture = resultFuture;
        }
        
        // Getters...
        public String getKey() { return key; }
        public long getValue() { return value; }
        public CompletableFuture<Void> getResultFuture() { return resultFuture; }
    }
}
```

### 4. Read Optimization with Caching

**Purpose**: Multi-level caching dramatically improves read performance by keeping frequently accessed data in fast memory layers while maintaining data consistency and managing memory usage efficiently. This is crucial for read-heavy workloads where the cost of querying all shards can become a major bottleneck.

**Key Benefits**:
- **Reduced Latency**: Hot data available in sub-millisecond access time
- **Lower Network Overhead**: Fewer queries to shards for frequently accessed data
- **Better Scalability**: Reduces load on shards during read operations
- **Cost Efficiency**: Lower network costs and reduced shard resource usage

**Cache Architecture Strategy**:
- **L1 Cache (Hot Data)**: Small, fast cache for very frequently accessed data (1 second TTL)
- **L2 Cache (Warm Data)**: Larger cache for moderately accessed data (10 second TTL)
- **Adaptive Promotion**: Data moves between cache levels based on access patterns
- **Intelligent Eviction**: LRU-based eviction with access frequency weighting

**Cache Consistency Challenges**:
- **Stale Data**: Cached data may become outdated
- **Memory Pressure**: Too much cached data can cause memory issues
- **Cache Invalidation**: Need to invalidate cache when data changes
- **Cache Warming**: Cold start performance can be poor

**When to Use**: Multi-level caching is essential for any system with read-heavy workloads or where the same counters are accessed frequently. It's particularly effective for systems with predictable access patterns.

**Configuration Considerations**:
- **Cache Sizes**: Balance between memory usage and hit rates
- **TTL Settings**: How long to keep data in each cache level
- **Eviction Policies**: How to handle cache full scenarios
- **Warming Strategies**: How to populate cache on startup

```java
// Multi-level caching for read optimization
public class OptimizedReadCache {
    private final LoadingCache<String, Long> l1Cache; // Hot data (1 second TTL)
    private final LoadingCache<String, Long> l2Cache; // Warm data (10 second TTL)
    private final Map<String, AtomicLong> accessCounters = new ConcurrentHashMap<>();
    
    public OptimizedReadCache() {
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .recordStats()
                .build();
        
        this.l2Cache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
    
    public long getValue(String counterId) {
        // Try L1 cache first
        Long l1Value = l1Cache.getIfPresent(counterId);
        if (l1Value != null) {
            recordAccess(counterId);
            return l1Value;
        }
        
        // Try L2 cache
        Long l2Value = l2Cache.getIfPresent(counterId);
        if (l2Value != null) {
            // Promote to L1 cache
            l1Cache.put(counterId, l2Value);
            recordAccess(counterId);
            return l2Value;
        }
        
        // Load from storage
        long storageValue = loadFromStorage(counterId);
        
        // Cache in both levels
        l1Cache.put(counterId, storageValue);
        l2Cache.put(counterId, storageValue);
        recordAccess(counterId);
        
        return storageValue;
    }
    
    private void recordAccess(String counterId) {
        accessCounters.computeIfAbsent(counterId, k -> new AtomicLong()).incrementAndGet();
    }
    
    // Adaptive cache sizing based on access patterns
    public void optimizeCacheSizes() {
        accessCounters.forEach((counterId, accessCount) -> {
            long count = accessCount.get();
            if (count > 1000) {
                // Very hot data - keep in L1 longer
                l1Cache.policy().expireAfterWrite().ifPresent(policy -> 
                    policy.setExpiresAfter(counterId, 5, TimeUnit.SECONDS));
            } else if (count < 10) {
                // Cold data - evict from L2
                l2Cache.invalidate(counterId);
            }
        });
    }
}
```

## Advanced Bottleneck Identification and Resolution

### 1. Real-Time Performance Monitoring

**Purpose**: Comprehensive performance monitoring provides real-time visibility into system behavior, enabling proactive identification of bottlenecks and performance issues before they impact users. This monitoring system is essential for maintaining high availability and performance in production environments.

**Key Monitoring Areas**:
- **Operation Latency**: Track response times for different operation types
- **Throughput Metrics**: Monitor operations per second across the system
- **Error Rates**: Track failure rates and error types
- **Resource Utilization**: Monitor CPU, memory, disk, and network usage
- **Shard Health**: Track individual shard performance and availability

**Critical Metrics to Track**:
- **P50, P95, P99 Latency**: Understand latency distribution
- **Success/Failure Rates**: Monitor system reliability
- **Queue Depths**: Track pending operations
- **Cache Hit Rates**: Monitor caching effectiveness
- **Network Delays**: Track inter-node communication performance

**Alerting Strategy**: The monitoring system provides configurable alerts for:
- **High Latency**: When P95 latency exceeds thresholds
- **High Error Rates**: When failure rate exceeds acceptable levels
- **Resource Exhaustion**: When CPU, memory, or disk usage is high
- **Shard Failures**: When individual shards become unavailable
- **Network Issues**: When network delays impact performance

**When to Use**: Real-time monitoring is essential for any production system. It's particularly important for distributed systems where issues can cascade across multiple components.

**Implementation Benefits**:
- **Proactive Problem Detection**: Identify issues before they impact users
- **Performance Optimization**: Use metrics to optimize system configuration
- **Capacity Planning**: Use historical data for capacity planning
- **Debugging**: Detailed metrics help debug performance issues

```java
// Comprehensive performance monitoring
public class PerformanceMonitor {
    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> operationTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> resourceGauges = new ConcurrentHashMap<>();
    
    public PerformanceMonitor() {
        this.meterRegistry = new SimpleMeterRegistry();
        initializeMetrics();
    }
    
    private void initializeMetrics() {
        // Operation timers
        Timer.builder("sharded_counter.operation.duration")
                .tag("operation", "increment")
                .register(meterRegistry);
        
        // Error counters
        Counter.builder("sharded_counter.errors")
                .tag("type", "shard_failure")
                .register(meterRegistry);
        
        // Resource gauges
        Gauge.builder("sharded_counter.memory.usage")
                .register(meterRegistry, this, PerformanceMonitor::getMemoryUsage);
    }
    
    public void recordOperation(String operation, String shardId, long durationMs) {
        Timer timer = operationTimers.computeIfAbsent(operation + "_" + shardId,
                k -> Timer.builder("sharded_counter.operation.duration")
                        .tag("operation", operation)
                        .tag("shard", shardId)
                        .register(meterRegistry));
        
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }
    
    public void recordError(String errorType, String shardId) {
        String key = errorType + "_" + shardId;
        Counter counter = errorCounters.computeIfAbsent(key,
                k -> Counter.builder("sharded_counter.errors")
                        .tag("type", errorType)
                        .tag("shard", shardId)
                        .register(meterRegistry));
        
        counter.increment();
    }
    
    public double getErrorRate(String shardId) {
        // Calculate error rate for specific shard
        return errorCounters.entrySet().stream()
                .filter(entry -> entry.getKey().contains(shardId))
                .mapToDouble(entry -> entry.getValue().count())
                .sum();
    }
    
    public List<String> identifyHotspots() {
        // Identify shards with high error rates or latency
        return operationTimers.entrySet().stream()
                .filter(entry -> {
                    Timer timer = entry.getValue();
                    return timer.mean(TimeUnit.MILLISECONDS) > 100; // > 100ms
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
```

### 2. Dynamic Load Balancing

**Purpose**: Dynamic load balancing automatically adjusts routing decisions based on real-time performance metrics and shard health, ensuring optimal resource utilization and maintaining high availability even when some shards are underperforming or failing.

**Key Benefits**:
- **Automatic Failover**: Route around failing or slow shards automatically
- **Load Distribution**: Distribute load based on shard capacity and performance
- **Performance Optimization**: Route to the fastest available shards
- **High Availability**: Maintain service even when some shards are down
- **Resource Efficiency**: Better utilization of available shard capacity

**Load Balancing Strategy**:
- **Health-Based Routing**: Route to healthy shards, avoid unhealthy ones
- **Performance-Based Routing**: Route to fastest shards for better latency
- **Weighted Distribution**: Adjust routing weights based on shard capacity
- **Automatic Recovery**: Re-include shards when they recover
- **Graceful Degradation**: Continue operating with reduced capacity

**Health Monitoring Criteria**:
- **Response Time**: Track shard response times
- **Error Rates**: Monitor shard failure rates
- **Availability**: Track shard uptime and connectivity
- **Resource Usage**: Monitor CPU, memory, and disk usage
- **Network Latency**: Track network delays to shards

**When to Use**: Dynamic load balancing is essential for any production distributed system. It's particularly important for systems with variable load patterns or where shard performance can vary significantly.

**Configuration Considerations**:
- **Health Check Frequency**: How often to check shard health
- **Failure Thresholds**: When to consider a shard unhealthy
- **Recovery Criteria**: When to re-include a previously failed shard
- **Routing Weights**: How to adjust routing based on performance

```java
// Adaptive load balancing based on performance metrics
public class AdaptiveLoadBalancer {
    private final Map<String, ShardHealth> shardHealth = new ConcurrentHashMap<>();
    private final PerformanceMonitor performanceMonitor;
    private final ConsistentHash<String> hashRing;
    
    public AdaptiveLoadBalancer(PerformanceMonitor performanceMonitor, 
                               ConsistentHash<String> hashRing) {
        this.performanceMonitor = performanceMonitor;
        this.hashRing = hashRing;
    }
    
    public String selectShard(String counterId) {
        // Get primary shard
        String primaryShard = hashRing.get(counterId);
        
        // Check if primary shard is healthy
        ShardHealth health = shardHealth.get(primaryShard);
        if (health != null && health.isHealthy()) {
            return primaryShard;
        }
        
        // Find alternative healthy shard
        return findHealthyAlternative(counterId, primaryShard);
    }
    
    private String findHealthyAlternative(String counterId, String primaryShard) {
        return shardHealth.entrySet().stream()
                .filter(entry -> entry.getValue().isHealthy())
                .filter(entry -> !entry.getKey().equals(primaryShard))
                .min(Comparator.comparing(entry -> 
                    entry.getValue().getAverageLatency()))
                .map(Map.Entry::getKey)
                .orElse(primaryShard); // Fallback to primary
    }
    
    public void updateShardHealth(String shardId, double latency, double errorRate) {
        ShardHealth health = shardHealth.computeIfAbsent(shardId, 
                k -> new ShardHealth());
        
        health.updateMetrics(latency, errorRate);
        
        // Adjust hash ring weights based on health
        if (health.isUnhealthy()) {
            hashRing.adjustWeight(shardId, 0.5); // Reduce weight
        } else if (health.isHealthy()) {
            hashRing.adjustWeight(shardId, 1.0); // Normal weight
        }
    }
    
    private static class ShardHealth {
        private final Queue<Double> latencyHistory = new LinkedList<>();
        private final Queue<Double> errorRateHistory = new LinkedList<>();
        private static final int HISTORY_SIZE = 100;
        
        public void updateMetrics(double latency, double errorRate) {
            latencyHistory.offer(latency);
            errorRateHistory.offer(errorRate);
            
            if (latencyHistory.size() > HISTORY_SIZE) {
                latencyHistory.poll();
                errorRateHistory.poll();
            }
        }
        
        public boolean isHealthy() {
            double avgLatency = latencyHistory.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            double avgErrorRate = errorRateHistory.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            
            return avgLatency < 50 && avgErrorRate < 0.01; // < 50ms, < 1% errors
        }
        
        public boolean isUnhealthy() {
            return !isHealthy();
        }
        
        public double getAverageLatency() {
            return latencyHistory.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
        }
    }
}
```

## Horizontal Scaling Strategies

### 1. Auto-Scaling Implementation

```java
// Dynamic auto-scaling based on load
public class AutoScaler {
    private final PerformanceMonitor performanceMonitor;
    private final List<String> shardAddresses;
    private final AtomicInteger targetShardCount;
    private final ScheduledExecutorService scheduler;
    
    public AutoScaler(PerformanceMonitor performanceMonitor, 
                      List<String> initialShards) {
        this.performanceMonitor = performanceMonitor;
        this.shardAddresses = new CopyOnWriteArrayList<>(initialShards);
        this.targetShardCount = new AtomicInteger(initialShards.size());
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        startScalingMonitor();
    }
    
    private void startScalingMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                evaluateScaling();
            } catch (Exception e) {
                logger.error("Error in scaling evaluation", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    private void evaluateScaling() {
        // Get current performance metrics
        double currentThroughput = performanceMonitor.getCurrentThroughput();
        double targetThroughput = getTargetThroughput();
        double currentLatency = performanceMonitor.getAverageLatency();
        double targetLatency = 50; // 50ms target
        
        int currentShards = shardAddresses.size();
        int newTargetShards = currentShards;
        
        // Scale up if throughput is insufficient
        if (currentThroughput < targetThroughput * 0.8) {
            newTargetShards = (int) Math.ceil(currentShards * 1.5);
        }
        
        // Scale up if latency is too high
        if (currentLatency > targetLatency * 1.5) {
            newTargetShards = Math.max(newTargetShards, 
                    (int) Math.ceil(currentShards * 1.2));
        }
        
        // Scale down if over-provisioned
        if (currentThroughput > targetThroughput * 1.5 && 
            currentLatency < targetLatency * 0.8) {
            newTargetShards = Math.max(2, (int) Math.floor(currentShards * 0.8));
        }
        
        // Apply scaling if needed
        if (newTargetShards != currentShards) {
            performScaling(newTargetShards);
        }
    }
    
    private void performScaling(int newShardCount) {
        if (newShardCount > shardAddresses.size()) {
            // Scale up - add new shards
            int shardsToAdd = newShardCount - shardAddresses.size();
            for (int i = 0; i < shardsToAdd; i++) {
                String newShard = createNewShard();
                shardAddresses.add(newShard);
                logger.info("Added new shard: {}", newShard);
            }
        } else if (newShardCount < shardAddresses.size()) {
            // Scale down - remove least used shards
            int shardsToRemove = shardAddresses.size() - newShardCount;
            List<String> shardsToRemove = identifyLeastUsedShards(shardsToRemove);
            
            for (String shard : shardsToRemove) {
                shardAddresses.remove(shard);
                decommissionShard(shard);
                logger.info("Removed shard: {}", shard);
            }
        }
        
        targetShardCount.set(newShardCount);
    }
    
    private String createNewShard() {
        // Implementation for creating new shard instances
        // This would typically involve cloud provider APIs
        return "shard-" + System.currentTimeMillis();
    }
    
    private List<String> identifyLeastUsedShards(int count) {
        // Return shards with lowest activity
        return shardAddresses.stream()
                .sorted(Comparator.comparing(shard -> 
                    performanceMonitor.getShardActivity(shard)))
                .limit(count)
                .collect(Collectors.toList());
    }
}
```

### 2. Read Replica Scaling

```java
// Read replica management for read-heavy workloads
public class ReadReplicaManager {
    private final Map<String, List<String>> shardReplicas = new ConcurrentHashMap<>();
    private final PerformanceMonitor performanceMonitor;
    private final LoadBalancer readLoadBalancer;
    
    public ReadReplicaManager(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
        this.readLoadBalancer = new RoundRobinLoadBalancer();
    }
    
    public ShardedCounterResponse getTotalWithReplicas(String counterId) {
        Map<String, Long> shardValues = new HashMap<>();
        long totalValue = 0;
        
        // Query all shards including replicas
        for (Map.Entry<String, List<String>> entry : shardReplicas.entrySet()) {
            String primaryShard = entry.getKey();
            List<String> replicas = entry.getValue();
            
            // Try primary first, then replicas
            String targetShard = selectBestShard(primaryShard, replicas);
            
            try {
                ShardedCounterResponse response = queryShard(targetShard, counterId);
                if (response.isSuccess()) {
                    long shardValue = response.getShardValue();
                    shardValues.put(targetShard, shardValue);
                    totalValue += shardValue;
                }
            } catch (Exception e) {
                logger.error("Failed to query shard: " + targetShard, e);
            }
        }
        
        return ShardedCounterResponse.success(totalValue, shardValues);
    }
    
    private String selectBestShard(String primary, List<String> replicas) {
        // Check primary first
        if (isShardHealthy(primary)) {
            return primary;
        }
        
        // Find healthy replica
        for (String replica : replicas) {
            if (isShardHealthy(replica)) {
                return replica;
            }
        }
        
        // Fallback to primary
        return primary;
    }
    
    private boolean isShardHealthy(String shardId) {
        double latency = performanceMonitor.getShardLatency(shardId);
        double errorRate = performanceMonitor.getShardErrorRate(shardId);
        
        return latency < 100 && errorRate < 0.05; // < 100ms, < 5% errors
    }
    
    public void addReadReplica(String primaryShard, String replicaShard) {
        shardReplicas.computeIfAbsent(primaryShard, k -> new ArrayList<>())
                .add(replicaShard);
        
        logger.info("Added read replica {} for primary shard {}", 
                replicaShard, primaryShard);
    }
    
    public void removeReadReplica(String primaryShard, String replicaShard) {
        List<String> replicas = shardReplicas.get(primaryShard);
        if (replicas != null) {
            replicas.remove(replicaShard);
            logger.info("Removed read replica {} from primary shard {}", 
                    replicaShard, primaryShard);
        }
    }
}
```

## Advanced Capacity Planning

### 1. Predictive Scaling

```java
// Predictive scaling based on historical patterns
public class PredictiveScaler {
    private final Map<String, List<Double>> historicalLoad = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> historicalLatency = new ConcurrentHashMap<>();
    private final PerformanceMonitor performanceMonitor;
    
    public PredictiveScaler(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }
    
    public void recordMetrics(String shardId, double load, double latency) {
        historicalLoad.computeIfAbsent(shardId, k -> new ArrayList<>()).add(load);
        historicalLatency.computeIfAbsent(shardId, k -> new ArrayList<>()).add(latency);
        
        // Keep only last 24 hours of data (assuming 1-minute intervals)
        if (historicalLoad.get(shardId).size() > 1440) {
            historicalLoad.get(shardId).remove(0);
            historicalLatency.get(shardId).remove(0);
        }
    }
    
    public ScalingPrediction predictScalingNeeds() {
        // Analyze historical patterns
        double currentHour = getCurrentHour();
        double predictedLoad = predictLoad(currentHour);
        double predictedLatency = predictLatency(currentHour);
        
        // Calculate required resources
        int requiredShards = calculateRequiredShards(predictedLoad, predictedLatency);
        int currentShards = getCurrentShardCount();
        
        return new ScalingPrediction(
            requiredShards,
            currentShards,
            requiredShards > currentShards ? ScalingAction.SCALE_UP :
            requiredShards < currentShards * 0.7 ? ScalingAction.SCALE_DOWN :
            ScalingAction.MAINTAIN
        );
    }
    
    private double predictLoad(double hour) {
        // Simple linear regression for load prediction
        // In practice, use more sophisticated ML models
        return 1000 + 500 * Math.sin(2 * Math.PI * hour / 24); // Daily pattern
    }
    
    private double predictLatency(double hour) {
        // Predict latency based on load
        double predictedLoad = predictLoad(hour);
        return 10 + (predictedLoad / 1000) * 50; // Base + load factor
    }
    
    private int calculateRequiredShards(double load, double targetLatency) {
        // Calculate shards needed for target performance
        double shardsForLoad = Math.ceil(load / 10000); // 10k ops/sec per shard
        double shardsForLatency = Math.ceil(load / (1000 / targetLatency));
        
        return (int) Math.max(shardsForLoad, shardsForLatency);
    }
    
    public static class ScalingPrediction {
        private final int requiredShards;
        private final int currentShards;
        private final ScalingAction action;
        
        public ScalingPrediction(int requiredShards, int currentShards, ScalingAction action) {
            this.requiredShards = requiredShards;
            this.currentShards = currentShards;
            this.action = action;
        }
        
        // Getters...
    }
    
    public enum ScalingAction {
        SCALE_UP, SCALE_DOWN, MAINTAIN
    }
}
```

### 2. Resource Optimization

```java
// Resource optimization and cost management
public class ResourceOptimizer {
    private final Map<String, ResourceUsage> shardResources = new ConcurrentHashMap<>();
    private final PerformanceMonitor performanceMonitor;
    
    public ResourceOptimizer(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }
    
    public void optimizeResources() {
        // Analyze resource usage patterns
        Map<String, ResourceUsage> usage = collectResourceUsage();
        
        // Identify underutilized resources
        List<String> underutilizedShards = identifyUnderutilizedShards(usage);
        
        // Consolidate underutilized shards
        if (underutilizedShards.size() > 1) {
            consolidateShards(underutilizedShards);
        }
        
        // Optimize memory usage
        optimizeMemoryUsage();
        
        // Optimize storage usage
        optimizeStorageUsage();
    }
    
    private Map<String, ResourceUsage> collectResourceUsage() {
        Map<String, ResourceUsage> usage = new HashMap<>();
        
        for (String shardId : getShardIds()) {
            ResourceUsage resourceUsage = new ResourceUsage(
                getCpuUsage(shardId),
                getMemoryUsage(shardId),
                getStorageUsage(shardId),
                getNetworkUsage(shardId)
            );
            usage.put(shardId, resourceUsage);
        }
        
        return usage;
    }
    
    private List<String> identifyUnderutilizedShards(Map<String, ResourceUsage> usage) {
        return usage.entrySet().stream()
                .filter(entry -> {
                    ResourceUsage usage1 = entry.getValue();
                    return usage1.getCpuUsage() < 0.3 && // < 30% CPU
                           usage1.getMemoryUsage() < 0.4; // < 40% Memory
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    private void consolidateShards(List<String> shardsToConsolidate) {
        // Implement shard consolidation logic
        // This would involve data migration between shards
        logger.info("Consolidating {} underutilized shards", shardsToConsolidate.size());
    }
    
    private void optimizeMemoryUsage() {
        // Implement memory optimization strategies
        // - Adjust cache sizes based on usage patterns
        // - Implement memory pressure handling
        // - Optimize object allocation
    }
    
    private void optimizeStorageUsage() {
        // Implement storage optimization strategies
        // - Compress old data
        // - Implement data lifecycle management
        // - Optimize RocksDB configuration
    }
    
    private static class ResourceUsage {
        private final double cpuUsage;
        private final double memoryUsage;
        private final double storageUsage;
        private final double networkUsage;
        
        public ResourceUsage(double cpuUsage, double memoryUsage, 
                           double storageUsage, double networkUsage) {
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.storageUsage = storageUsage;
            this.networkUsage = networkUsage;
        }
        
        // Getters...
    }
}
```

## Load Testing and Benchmarking

### 1. Comprehensive Load Testing Framework

```java
// Advanced load testing framework
public class LoadTestFramework {
    private final ShardedCounterClient client;
    private final PerformanceMonitor performanceMonitor;
    private final ExecutorService testExecutor;
    
    public LoadTestFramework(ShardedCounterClient client, 
                            PerformanceMonitor performanceMonitor) {
        this.client = client;
        this.performanceMonitor = performanceMonitor;
        this.testExecutor = Executors.newFixedThreadPool(100);
    }
    
    public LoadTestResult runLoadTest(LoadTestScenario scenario) {
        List<CompletableFuture<OperationResult>> futures = new ArrayList<>();
        
        // Submit operations based on scenario
        for (int i = 0; i < scenario.getTotalOperations(); i++) {
            CompletableFuture<OperationResult> future = submitOperation(scenario, i);
            futures.add(future);
        }
        
        // Wait for completion
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        List<OperationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        
        return analyzeResults(results, scenario);
    }
    
    private CompletableFuture<OperationResult> submitOperation(LoadTestScenario scenario, int index) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // Generate operation based on scenario
                ShardedCounterOperation operation = generateOperation(scenario, index);
                
                // Execute operation
                ShardedCounterResponse response = client.executeOperation(operation);
                
                long duration = System.currentTimeMillis() - startTime;
                
                return new OperationResult(true, duration, response.isSuccess());
                
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                return new OperationResult(false, duration, false);
            }
        }, testExecutor);
    }
    
    private ShardedCounterOperation generateOperation(LoadTestScenario scenario, int index) {
        ShardedCounterOperation operation = new ShardedCounterOperation();
        
        // Generate counter ID based on distribution
        String counterId = generateCounterId(scenario.getCounterDistribution(), index);
        operation.setCounterId(counterId);
        
        // Set operation type based on mix
        String operationType = selectOperationType(scenario.getOperationMix());
        operation.setOperationType(operationType);
        
        // Set delta value
        long delta = generateDelta(scenario.getDeltaDistribution());
        operation.setDelta(delta);
        
        return operation;
    }
    
    private LoadTestResult analyzeResults(List<OperationResult> results, 
                                        LoadTestScenario scenario) {
        // Calculate statistics
        long totalOperations = results.size();
        long successfulOperations = results.stream()
                .filter(OperationResult::isSuccess).count();
        long failedOperations = totalOperations - successfulOperations;
        
        // Calculate latency statistics
        DoubleSummaryStatistics latencyStats = results.stream()
                .mapToDouble(OperationResult::getDuration)
                .summaryStatistics();
        
        // Calculate throughput
        double totalDuration = results.stream()
                .mapToDouble(OperationResult::getDuration)
                .max().orElse(0);
        double throughput = totalOperations / (totalDuration / 1000); // ops/sec
        
        return new LoadTestResult(
            totalOperations,
            successfulOperations,
            failedOperations,
            throughput,
            latencyStats.getAverage(),
            latencyStats.getMin(),
            latencyStats.getMax(),
            latencyStats.getPercentile(95),
            latencyStats.getPercentile(99)
        );
    }
    
    public static class LoadTestScenario {
        private final int totalOperations;
        private final CounterDistribution counterDistribution;
        private final OperationMix operationMix;
        private final DeltaDistribution deltaDistribution;
        
        // Constructor and getters...
    }
    
    public static class LoadTestResult {
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final double throughput;
        private final double averageLatency;
        private final double minLatency;
        private final double maxLatency;
        private final double p95Latency;
        private final double p99Latency;
        
        // Constructor and getters...
    }
}
```

## Performance Tuning Guidelines

### 1. System-Level Optimizations

```java
// System-level performance tuning
public class SystemOptimizer {
    
    public void optimizeJVM() {
        // JVM tuning recommendations
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "8");
        System.setProperty("netty.leakDetection.level", "disabled");
        System.setProperty("io.netty.allocator.numDirectArenas", "2");
        System.setProperty("io.netty.allocator.numHeapArenas", "2");
    }
    
    public void optimizeRocksDB() {
        // RocksDB optimization
        Options options = new Options();
        options.setMaxBackgroundJobs(4);
        options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
        options.setMaxWriteBufferNumber(3);
        options.setTargetFileSizeBase(64 * 1024 * 1024); // 64MB
        options.setCompressionType(CompressionType.LZ4_COMPRESSION);
        options.setUseBloomFilter(true);
        options.setBloomFilterBitsPerKey(10);
    }
    
    public void optimizeNetwork() {
        // Network optimization
        System.setProperty("sun.net.inetaddr.ttl", "0");
        System.setProperty("sun.net.inetaddr.negative.ttl", "0");
    }
}
```

### 2. Application-Level Optimizations

```java
// Application-level performance tuning
public class ApplicationOptimizer {
    
    public void optimizeThreadPools() {
        // Optimize thread pool sizes based on workload
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int ioThreads = cpuCores * 2;
        int computeThreads = cpuCores;
        
        // Configure thread pools
        System.setProperty("netty.ioThreads", String.valueOf(ioThreads));
        System.setProperty("netty.computeThreads", String.valueOf(computeThreads));
    }
    
    public void optimizeMemoryAllocation() {
        // Memory allocation optimization
        System.setProperty("io.netty.allocator.pageSize", "8192");
        System.setProperty("io.netty.allocator.maxOrder", "9");
        System.setProperty("io.netty.allocator.numDirectArenas", "2");
    }
    
    public void optimizeCaching() {
        // Cache optimization
        System.setProperty("jdk.httpclient.keepalive.timeout", "300");
        System.setProperty("jdk.httpclient.connectionPoolSize", "100");
    }
}
```

## Data Loss Risk Mitigation Strategies

### 1. **Acknowledgment Mechanisms**

```java
// Acknowledgment-based batching with durability
public class AcknowledgedBatchProcessor {
    private final Map<String, BatchAcknowledgment> pendingAcks = new ConcurrentHashMap<>();
    private final AtomicLong unacknowledgedOperations = new AtomicLong(0);
    
    public CompletableFuture<Void> submitOperationWithAck(String counterId, 
            ShardedCounterOperation operation) {
        
        CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        String operationId = generateOperationId();
        
        // Store acknowledgment future
        BatchAcknowledgment ack = new BatchAcknowledgment(operationId, operation, ackFuture);
        pendingAcks.put(operationId, ack);
        unacknowledgedOperations.incrementAndGet();
        
        // Submit to batch
        submitOperation(counterId, operation);
        
        return ackFuture;
    }
    
    private void acknowledgeBatch(String batchId, List<String> operationIds) {
        for (String operationId : operationIds) {
            BatchAcknowledgment ack = pendingAcks.remove(operationId);
            if (ack != null) {
                ack.getFuture().complete(null);
                unacknowledgedOperations.decrementAndGet();
            }
        }
    }
    
    private void failBatch(String batchId, List<String> operationIds, Exception error) {
        for (String operationId : operationIds) {
            BatchAcknowledgment ack = pendingAcks.remove(operationId);
            if (ack != null) {
                ack.getFuture().completeExceptionally(error);
                unacknowledgedOperations.decrementAndGet();
            }
        }
    }
    
    private static class BatchAcknowledgment {
        private final String operationId;
        private final ShardedCounterOperation operation;
        private final CompletableFuture<Void> future;
        
        public BatchAcknowledgment(String operationId, ShardedCounterOperation operation, 
                                 CompletableFuture<Void> future) {
            this.operationId = operationId;
            this.operation = operation;
            this.future = future;
        }
        
        // Getters...
    }
}
```

### 2. **Failure Recovery and Replay**

```java
// Failure recovery with operation replay
public class BatchRecoveryManager {
    private final Map<String, List<ShardedCounterOperation>> failedBatches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService recoveryExecutor = Executors.newScheduledThreadPool(1);
    
    public void recordBatchFailure(String batchId, List<ShardedCounterOperation> operations) {
        failedBatches.put(batchId, new ArrayList<>(operations));
        logger.error("Batch {} failed, {} operations queued for recovery", batchId, operations.size());
        
        // Schedule recovery attempt
        scheduleRecovery(batchId, operations);
    }
    
    private void scheduleRecovery(String batchId, List<ShardedCounterOperation> operations) {
        recoveryExecutor.schedule(() -> {
            try {
                recoverBatch(batchId, operations);
            } catch (Exception e) {
                logger.error("Recovery failed for batch {}", batchId, e);
                // Could implement exponential backoff or alerting here
            }
        }, 1, TimeUnit.SECONDS);
    }
    
    private void recoverBatch(String batchId, List<ShardedCounterOperation> operations) {
        logger.info("Attempting to recover batch {} with {} operations", batchId, operations.size());
        
        // Process operations individually for maximum durability
        List<CompletableFuture<ShardedCounterResponse>> futures = new ArrayList<>();
        
        for (ShardedCounterOperation operation : operations) {
            CompletableFuture<ShardedCounterResponse> future = 
                sendOperationWithRetry(operation, 5); // More retries for recovery
            futures.add(future);
        }
        
        // Wait for all operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                failedBatches.remove(batchId);
                logger.info("Successfully recovered batch {}", batchId);
            })
            .exceptionally(throwable -> {
                logger.error("Recovery failed for batch {}", batchId, throwable);
                return null;
            });
    }
}
```

### 3. **Monitoring and Alerting**

```java
// Comprehensive monitoring for data loss prevention
public class BatchMonitoring {
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong acknowledgedOperations = new AtomicLong(0);
    private final AtomicLong failedOperations = new AtomicLong(0);
    private final AtomicLong unacknowledgedOperations = new AtomicLong(0);
    
    public void recordOperationSubmitted() {
        totalOperations.incrementAndGet();
        unacknowledgedOperations.incrementAndGet();
    }
    
    public void recordOperationAcknowledged() {
        acknowledgedOperations.incrementAndGet();
        unacknowledgedOperations.decrementAndGet();
    }
    
    public void recordOperationFailed() {
        failedOperations.incrementAndGet();
        unacknowledgedOperations.decrementAndGet();
    }
    
    public BatchHealthMetrics getHealthMetrics() {
        long total = totalOperations.get();
        long acked = acknowledgedOperations.get();
        long failed = failedOperations.get();
        long unacked = unacknowledgedOperations.get();
        
        double successRate = total > 0 ? (double) acked / total : 1.0;
        double failureRate = total > 0 ? (double) failed / total : 0.0;
        
        return new BatchHealthMetrics(total, acked, failed, unacked, successRate, failureRate);
    }
    
    public boolean isHealthy() {
        BatchHealthMetrics metrics = getHealthMetrics();
        
        // Alert if failure rate is too high
        if (metrics.getFailureRate() > 0.01) { // > 1% failure rate
            logger.warn("High batch failure rate: {}%", metrics.getFailureRate() * 100);
            return false;
        }
        
        // Alert if too many unacknowledged operations
        if (metrics.getUnacknowledgedOperations() > 1000) {
            logger.warn("Too many unacknowledged operations: {}", metrics.getUnacknowledgedOperations());
            return false;
        }
        
        return true;
    }
    
    public static class BatchHealthMetrics {
        private final long totalOperations;
        private final long acknowledgedOperations;
        private final long failedOperations;
        private final long unacknowledgedOperations;
        private final double successRate;
        private final double failureRate;
        
        // Constructor and getters...
    }
}
```

---

*This chapter provided comprehensive performance optimization strategies and implementations that can be applied to the distributed sharded counter system. The next chapter will explore fault tolerance and high availability mechanisms to ensure system reliability under various failure scenarios.* 