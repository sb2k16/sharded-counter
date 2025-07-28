# Chapter 4: Read Operations and Aggregation Strategies

## Read Operation Lifecycle

Read operations in the distributed sharded counter system follow a distinct lifecycle that involves querying multiple shards and aggregating their results. Unlike write operations that target a specific shard, read operations must gather data from all shards to provide a complete view of the distributed counter.

### Multi-Shard Query Strategy

The coordinator implements a multi-shard query strategy that queries all available shards and aggregates their individual values:

Multi-shard queries aggregate data from all shards:

```java
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

For the complete multi-shard query implementation with fault tolerance, see **Listing 4.1** in the appendix.

This multi-shard approach provides several benefits:
- **Complete View**: Aggregates data from all shards to provide the total count
- **Fault Tolerance**: Continues operating even if some shards are unavailable
- **Load Distribution**: Read load is distributed across all shards
- **Eventual Consistency**: Accepts some latency for complete data view

### Individual Shard Querying

Each shard is queried independently using HTTP requests:

Individual shard queries use HTTP requests:

```java
private ShardedCounterResponse queryShard(String shardAddress, String counterId) {
    try {
        // Create query operation
        ShardedCounterOperation queryOp = new ShardedCounterOperation(
            counterId, ShardedCounterOperation.OperationType.GET_SHARD_VALUES);
        
        // Serialize request
        String jsonRequest = objectMapper.writeValueAsString(queryOp);
        
        // Create HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + shardAddress + "/sharded"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                .timeout(Duration.ofSeconds(3))
                .build();
        
        // Send request and parse response
        HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), ShardedCounterResponse.class);
        } else {
            logger.error("Shard returned error status: {}", response.statusCode());
            return ShardedCounterResponse.error("Shard query failed");
        }
    } catch (Exception e) {
        logger.error("Failed to query shard: " + shardAddress, e);
        return ShardedCounterResponse.error("Shard communication failed");
    }
}
```

For the complete individual shard query implementation with retry logic, see **Listing 4.2** in the appendix.

The individual shard querying provides:
- **Timeout Handling**: Configurable timeouts prevent hanging requests
- **Error Isolation**: Individual shard failures don't affect others
- **Response Parsing**: Proper JSON parsing and error handling
- **Logging**: Comprehensive logging for debugging and monitoring

## Aggregation Algorithms

The system supports multiple aggregation algorithms depending on the consistency requirements and performance needs.

### Simple Sum Aggregation

The default aggregation algorithm simply sums the values from all available shards:

Simple sum aggregation combines all shard values:

```java
public class SimpleSumAggregator implements AggregationAlgorithm {
    @Override
    public AggregationResult aggregate(Map<String, ShardResponse> shardResponses) {
        long totalValue = 0;
        int successfulShards = 0;
        Map<String, Long> shardValues = new HashMap<>();
        
        for (Map.Entry<String, ShardResponse> entry : shardResponses.entrySet()) {
            String shardAddress = entry.getKey();
            ShardResponse response = entry.getValue();
            
            if (response.isSuccess()) {
                long shardValue = response.getValue();
                shardValues.put(shardAddress, shardValue);
                totalValue += shardValue;
                successfulShards++;
            }
        }
        
        if (successfulShards == 0) {
            return AggregationResult.error("No successful shard responses");
        }
        
        return AggregationResult.success(totalValue, shardValues, successfulShards);
    }
}
```

For the complete simple sum aggregation implementation with error handling, see **Listing 4.3** in the appendix.

This simple aggregation provides:
- **Fast Response**: Minimal processing overhead
- **Fault Tolerance**: Continues with partial data
- **Predictable Behavior**: Simple and easy to understand
- **Low Latency**: Quick aggregation of shard responses

### Weighted Aggregation

For systems with varying shard reliability, weighted aggregation can be used:

Weighted aggregation considers shard reliability:

```java
public class WeightedAggregator implements AggregationAlgorithm {
    private final Map<String, Double> shardWeights;
    
    public WeightedAggregator(Map<String, Double> shardWeights) {
        this.shardWeights = new HashMap<>(shardWeights);
    }
    
    @Override
    public AggregationResult aggregate(Map<String, ShardResponse> shardResponses) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        Map<String, Long> shardValues = new HashMap<>();
        int successfulShards = 0;
        
        for (Map.Entry<String, ShardResponse> entry : shardResponses.entrySet()) {
            String shardAddress = entry.getKey();
            ShardResponse response = entry.getValue();
            
            if (response.isSuccess()) {
                double weight = shardWeights.getOrDefault(shardAddress, 1.0);
                long shardValue = response.getValue();
                
                shardValues.put(shardAddress, shardValue);
                weightedSum += shardValue * weight;
                totalWeight += weight;
                successfulShards++;
            }
        }
        
        if (successfulShards == 0 || totalWeight == 0) {
            return AggregationResult.error("No successful shard responses");
        }
        
        long totalValue = Math.round(weightedSum / totalWeight);
        return AggregationResult.success(totalValue, shardValues, successfulShards);
    }
}
```

For the complete weighted aggregation implementation with dynamic weight adjustment, see **Listing 4.4** in the appendix.

Weighted aggregation provides:
- **Reliability-Based Weighting**: More reliable shards have higher weight
- **Adaptive Behavior**: Weights can be adjusted based on shard performance
- **Improved Accuracy**: Better estimates when some shards are unreliable
- **Configurable**: Weights can be tuned for specific use cases

### Consensus-Based Aggregation

For high-consistency requirements, consensus-based aggregation can be used:

Consensus-based aggregation requires majority agreement:

```java
public class ConsensusAggregator implements AggregationAlgorithm {
    private final double consensusThreshold; // e.g., 0.5 for majority
    
    public ConsensusAggregator(double consensusThreshold) {
        this.consensusThreshold = consensusThreshold;
    }
    
    @Override
    public AggregationResult aggregate(Map<String, ShardResponse> shardResponses) {
        Map<Long, Integer> valueCounts = new HashMap<>();
        Map<String, Long> shardValues = new HashMap<>();
        int totalResponses = 0;
        
        // Count occurrences of each value
        for (Map.Entry<String, ShardResponse> entry : shardResponses.entrySet()) {
            String shardAddress = entry.getKey();
            ShardResponse response = entry.getValue();
            
            if (response.isSuccess()) {
                long value = response.getValue();
                shardValues.put(shardAddress, value);
                valueCounts.put(value, valueCounts.getOrDefault(value, 0) + 1);
                totalResponses++;
            }
        }
        
        if (totalResponses == 0) {
            return AggregationResult.error("No successful shard responses");
        }
        
        // Find the most common value
        long consensusValue = 0;
        int maxCount = 0;
        
        for (Map.Entry<Long, Integer> entry : valueCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                consensusValue = entry.getKey();
            }
        }
        
        // Check if consensus threshold is met
        double consensusRatio = (double) maxCount / totalResponses;
        if (consensusRatio >= consensusThreshold) {
            return AggregationResult.success(consensusValue, shardValues, totalResponses);
        } else {
            return AggregationResult.error("Consensus threshold not met: " + consensusRatio);
        }
    }
}
```

For the complete consensus-based aggregation implementation with advanced error detection, see **Listing 4.5** in the appendix.

Consensus-based aggregation provides:
- **High Consistency**: Requires agreement among multiple shards
- **Error Detection**: Can detect when shards are inconsistent
- **Configurable Threshold**: Adjustable consensus requirements
- **Data Integrity**: Helps identify data corruption or inconsistencies

## Eventual Consistency Model

The distributed sharded counter system operates under an eventual consistency model, which provides several important characteristics:

### Consistency Levels

The system supports different consistency levels for read operations:

Consistency levels provide different guarantees:

```java
public enum ConsistencyLevel {
    EVENTUAL,    // Return immediately with available data
    QUORUM,      // Wait for majority of shards
    STRONG       // Wait for all shards
}

public class ConsistencyAwareAggregator {
    private final ConsistencyLevel consistencyLevel;
    private final int totalShards;
    
    public ConsistencyAwareAggregator(ConsistencyLevel consistencyLevel, int totalShards) {
        this.consistencyLevel = consistencyLevel;
        this.totalShards = totalShards;
    }
    public AggregationResult aggregate(Map<String, ShardResponse> shardResponses) {
        int successfulShards = (int) shardResponses.values().stream()
                .filter(ShardResponse::isSuccess)
                .count();
        
        // Check consistency requirements
        switch (consistencyLevel) {
            case EVENTUAL:
                return aggregateEventual(shardResponses, successfulShards);
            case QUORUM:
                if (successfulShards < (totalShards / 2) + 1) {
                    return AggregationResult.error("Quorum not available");
                }
                return aggregateQuorum(shardResponses, successfulShards);
            case STRONG:
                if (successfulShards < totalShards) {
                    return AggregationResult.error("Not all shards available");
                }
                return aggregateStrong(shardResponses, successfulShards);
            default:
                return AggregationResult.error("Unknown consistency level");
        }
    }
}
```

</details>

### Eventual Consistency Characteristics

The eventual consistency model provides:

1. **High Availability**: System continues operating even with partial failures
2. **Low Latency**: Reads can complete quickly with available data
3. **Scalability**: Performance scales with the number of shards
4. **Fault Tolerance**: Individual shard failures don't affect overall system

### Consistency Trade-offs

Different consistency levels provide different trade-offs:

- **EVENTUAL**: Fastest response, may return stale data
- **QUORUM**: Balanced consistency and availability
- **STRONG**: Strongest consistency, may block on failures

## Fault Tolerance in Reads

The read operation system implements comprehensive fault tolerance mechanisms:

### Shard Failure Handling

When individual shards fail, the system continues operating:

Shard failure handling ensures fault tolerance:

```java
public class FaultTolerantReadHandler {
    private final Map<String, ShardHealth> shardHealth;
    private final int maxRetries;
    private final long timeoutMs;
    
    public FaultTolerantReadHandler(int maxRetries, long timeoutMs) {
        this.shardHealth = new ConcurrentHashMap<>();
        this.maxRetries = maxRetries;
        this.timeoutMs = timeoutMs;
    }
    
    public CompletableFuture<ShardResponse> queryShardWithRetry(String shardAddress, 
            String counterId) {
        return queryShardWithRetryInternal(shardAddress, counterId, 0);
    }
    
    private CompletableFuture<ShardResponse> queryShardWithRetryInternal(
            String shardAddress, String counterId, int attempt) {
        
        return queryShard(shardAddress, counterId)
                .handle((response, throwable) -> {
                    if (throwable != null && attempt < maxRetries) {
                        // Exponential backoff
                        long delay = (long) Math.pow(2, attempt) * 100;
                        return CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                                .execute(() -> queryShardWithRetryInternal(shardAddress, counterId, attempt + 1))
                                .join();
                    }
                    return response;
                });
    }
}
```

For the complete shard failure handling implementation with health monitoring, see **Listing 4.6** in the appendix.

### Timeout and Retry Logic

The system implements configurable timeout and retry mechanisms:

Timeout and retry logic handles failures gracefully:

```java
public class ReadOperationConfig {
    private final Duration shardTimeout;
    private final Duration totalTimeout;
    private final int maxRetries;
    private final boolean failFast;
    
    public ReadOperationConfig(Duration shardTimeout, Duration totalTimeout, 
                             int maxRetries, boolean failFast) {
        this.shardTimeout = shardTimeout;
        this.totalTimeout = totalTimeout;
        this.maxRetries = maxRetries;
        this.failFast = failFast;
    }
    
    public CompletableFuture<AggregationResult> executeWithTimeout(
            Supplier<CompletableFuture<AggregationResult>> operation) {
        
        CompletableFuture<AggregationResult> result = operation.get();
        
        // Apply total timeout
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            if (!result.isDone()) {
                result.completeExceptionally(new TimeoutException("Total timeout exceeded"));
            }
        }, totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
        
        return result;
    }
}
```

For the complete timeout and retry implementation with configurable strategies, see **Listing 4.7** in the appendix.

## Performance Trade-offs

Read operations involve several performance trade-offs that must be carefully considered:

### Latency vs. Completeness

The system must balance response latency with data completeness:

Latency vs. completeness trade-offs are configurable:

```java
public class AdaptiveReadHandler {
    private final Map<String, Long> shardLatencies = new ConcurrentHashMap<>();
    private final double completenessThreshold;
    private final Duration maxLatency;
    
    public AdaptiveReadHandler(double completenessThreshold, Duration maxLatency) {
        this.completenessThreshold = completenessThreshold;
        this.maxLatency = maxLatency;
    }
    
    public CompletableFuture<AggregationResult> adaptiveRead(
            Map<String, CompletableFuture<ShardResponse>> shardQueries) {
        
        List<CompletableFuture<ShardResponse>> futures = 
                new ArrayList<>(shardQueries.values());
        
        // Wait for minimum required responses
        int minResponses = (int) Math.ceil(shardQueries.size() * completenessThreshold);
        
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                .thenCompose(result -> {
                    // Check if we have enough responses
                    long completedCount = futures.stream()
                            .filter(CompletableFuture::isDone)
                            .count();
                    
                    if (completedCount >= minResponses) {
                        return aggregateAvailableResponses(shardQueries);
                    } else {
                        // Wait for more responses or timeout
                        return waitForMoreResponses(shardQueries, minResponses);
                    }
                });
    }
}
```

For the complete adaptive read handler implementation with configurable thresholds, see **Listing 4.8** in the appendix.

### Parallel vs. Sequential Queries

The system can choose between parallel and sequential query strategies:

Parallel and sequential query strategies provide different trade-offs:

```java
// Parallel query strategy
public class ParallelQueryStrategy implements QueryStrategy {
    @Override
    public CompletableFuture<AggregationResult> queryShards(
            Map<String, String> shardAddresses, String counterId) {
        
        Map<String, CompletableFuture<ShardResponse>> futures = new HashMap<>();
        
        // Start all queries in parallel
        for (Map.Entry<String, String> entry : shardAddresses.entrySet()) {
            String shardId = entry.getKey();
            String address = entry.getValue();
            
            futures.put(shardId, queryShard(address, counterId));
        }
        
        // Wait for all responses
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .thenApply(v -> aggregateResponses(futures));
    }
}

// Sequential query strategy
public class SequentialQueryStrategy implements QueryStrategy {
    @Override
    public CompletableFuture<AggregationResult> queryShards(
            Map<String, String> shardAddresses, String counterId) {
        
        List<ShardResponse> responses = new ArrayList<>();
        
        // Query shards sequentially
        for (Map.Entry<String, String> entry : shardAddresses.entrySet()) {
            String shardId = entry.getKey();
            String address = entry.getValue();
            
            try {
                ShardResponse response = queryShard(address, counterId).get();
                responses.add(response);
            } catch (Exception e) {
                logger.warn("Failed to query shard: " + address, e);
            }
        }
        
        return CompletableFuture.completedFuture(aggregateResponses(responses));
    }
}
```

</details>

## Caching Strategies

The read operation system implements several caching strategies to improve performance:

### Coordinator-Level Caching

The coordinator can cache aggregated results:

Coordinator-level caching improves read performance:

```java
public class CoordinatorCache {
    private final Cache<String, CachedResult> cache;
    private final Duration ttl;
    
    public CoordinatorCache(int maxSize, Duration ttl) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
        this.ttl = ttl;
    }
    
    public Optional<Long> getCachedValue(String counterId) {
        CachedResult cached = cache.getIfPresent(counterId);
        if (cached != null && !cached.isExpired()) {
            return Optional.of(cached.getValue());
        }
        return Optional.empty();
    }
    
    public void cacheValue(String counterId, long value) {
        CachedResult result = new CachedResult(value, System.currentTimeMillis());
        cache.put(counterId, result);
    }
    
    private static class CachedResult {
        private final long value;
        private final long timestamp;
        private final Duration ttl;
        
        public CachedResult(long value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
            this.ttl = Duration.ofSeconds(5); // 5 second TTL
        }
        
        public long getValue() { return value; }
        public boolean isExpired() { 
            return System.currentTimeMillis() - timestamp > ttl.toMillis(); 
        }
    }
}
```

For the complete coordinator-level caching implementation with TTL management, see **Listing 4.9** in the appendix.

### Cache Invalidation

The cache must be invalidated when data changes:

Cache invalidation ensures data consistency:

```java
public class CacheInvalidationHandler {
    private final CoordinatorCache cache;
    private final Set<String> invalidatedKeys = ConcurrentHashMap.newKeySet();
    
    public CacheInvalidationHandler(CoordinatorCache cache) {
        this.cache = cache;
    }
    
    public void invalidateCounter(String counterId) {
        invalidatedKeys.add(counterId);
        cache.invalidate(counterId);
        
        // Schedule cleanup of invalidated keys set
        CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS)
                .execute(() -> invalidatedKeys.remove(counterId));
    }
    
    public boolean isInvalidated(String counterId) {
        return invalidatedKeys.contains(counterId);
    }
    
    public void onWriteOperation(String counterId) {
        // Invalidate cache when write occurs
        invalidateCounter(counterId);
    }
}
```

For the complete cache invalidation implementation with write-through strategies, see **Listing 4.10** in the appendix.

### Cache Consistency

The cache must maintain consistency with the underlying data:

<details>
<summary><strong>Cache Consistency Implementation</strong></summary>

```java
// Cache consistency management
public class ConsistentCache {
    private final CoordinatorCache cache;
    private final Map<String, Long> writeTimestamps = new ConcurrentHashMap<>();
    
    public ConsistentCache(CoordinatorCache cache) {
        this.cache = cache;
    }
    
    public Optional<Long> getConsistentValue(String counterId, long readTimestamp) {
        CachedResult cached = cache.getIfPresent(counterId);
        
        if (cached != null) {
            Long lastWrite = writeTimestamps.get(counterId);
            
            // Only return cached value if it's newer than the last write
            if (lastWrite == null || cached.getTimestamp() > lastWrite) {
                return Optional.of(cached.getValue());
            }
        }
        
        return Optional.empty();
    }
    
    public void onWrite(String counterId) {
        writeTimestamps.put(counterId, System.currentTimeMillis());
        cache.invalidate(counterId);
    }
}
```

</details>

## Conclusion

The read operation system in the distributed sharded counter provides a robust, scalable solution for aggregating data from multiple shards. Through careful design of the multi-shard query strategy, aggregation algorithms, and fault tolerance mechanisms, the system achieves both performance and reliability.

The key design principles—eventual consistency for availability, fault tolerance for reliability, and caching for performance—work together to create a system that can handle the demands of modern, high-scale applications.

In the next chapter, we'll examine the replication strategies used to provide data redundancy and improve fault tolerance across the distributed system. 