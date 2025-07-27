# Chapter 4: Read Operations: Aggregation and Consistency

## Read Operation Lifecycle

Read operations in the distributed sharded counter system present unique challenges compared to write operations. While writes can be routed to a single shard, reads must aggregate data from all shards to provide a complete view of the counter's total value. This aggregation process introduces complexity in terms of consistency, performance, and fault tolerance.

The read operation lifecycle consists of several phases:

1. **Client Request Processing**: HTTP request parsing and validation
2. **Multi-Shard Query Strategy**: Parallel queries to all shards
3. **Aggregation Algorithm**: Combining results from multiple shards
4. **Consistency Handling**: Managing eventual consistency
5. **Response Generation**: Providing aggregated results to client

## Multi-Shard Query Strategy

Unlike write operations that route to a single shard, read operations must query all shards to gather the complete counter value. The coordinator implements a parallel query strategy to minimize latency.

```java
// From ShardedCounterCoordinator.java
public ShardedCounterResponse getTotalValue(String counterId) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    List<CompletableFuture<ShardResponse>> futures = new ArrayList<>();
    
    // Query all shards in parallel
    for (String shardAddress : shardAddresses) {
        CompletableFuture<ShardResponse> future = CompletableFuture.supplyAsync(() -> {
            try {
                ShardedCounterResponse response = queryShard(shardAddress, counterId);
                return new ShardResponse(shardAddress, response, null);
            } catch (Exception e) {
                return new ShardResponse(shardAddress, null, e);
            }
        });
        futures.add(future);
    }
    
    // Wait for all responses with timeout
    try {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        logger.warn("Timeout waiting for shard responses");
    }
    
    // Aggregate results
    for (CompletableFuture<ShardResponse> future : futures) {
        try {
            ShardResponse response = future.get();
            if (response.getError() == null && response.getResponse().isSuccess()) {
                long shardValue = response.getResponse().getShardValue();
                shardValues.put(response.getShardAddress(), shardValue);
                totalValue += shardValue;
            }
        } catch (Exception e) {
            logger.error("Error processing shard response", e);
        }
    }
    
    return new ShardedCounterResponse(true, totalValue, shardValues);
}
```

The parallel query strategy provides several benefits:

- **Reduced Latency**: All shards are queried simultaneously
- **Fault Tolerance**: Individual shard failures don't block the entire operation
- **Timeout Handling**: Prevents indefinite waiting for slow shards
- **Partial Results**: Can return results even if some shards are unavailable

## Aggregation Algorithms

The system implements several aggregation strategies depending on the consistency requirements and performance needs.

### Simple Sum Aggregation

The most straightforward aggregation algorithm simply sums the values from all available shards:

```java
// From ShardedCounterCoordinator.java
private long aggregateShardValues(Map<String, Long> shardValues) {
    return shardValues.values().stream()
        .mapToLong(Long::longValue)
        .sum();
}
```

This approach provides:
- **Simplicity**: Easy to understand and implement
- **Performance**: Fast aggregation with minimal overhead
- **Fault Tolerance**: Continues working even if some shards fail

### Weighted Aggregation

For systems with different shard capacities or reliability, weighted aggregation can be used:

```java
// From ShardedCounterCoordinator.java
private long aggregateWithWeights(Map<String, Long> shardValues, Map<String, Double> weights) {
    double totalWeight = 0;
    double weightedSum = 0;
    
    for (Map.Entry<String, Long> entry : shardValues.entrySet()) {
        String shardAddress = entry.getKey();
        long value = entry.getValue();
        double weight = weights.getOrDefault(shardAddress, 1.0);
        
        weightedSum += value * weight;
        totalWeight += weight;
    }
    
    return totalWeight > 0 ? (long) (weightedSum / totalWeight) : 0;
}
```

### Consensus-Based Aggregation

For higher consistency requirements, the system can implement consensus-based aggregation:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse aggregateWithConsensus(Map<String, Long> shardValues, int minResponses) {
    if (shardValues.size() < minResponses) {
        return new ShardedCounterResponse(false, 0, "Insufficient shard responses");
    }
    
    // Calculate median to handle outliers
    List<Long> values = new ArrayList<>(shardValues.values());
    Collections.sort(values);
    long median = values.get(values.size() / 2);
    
    // Check if values are within acceptable range
    long total = 0;
    int validResponses = 0;
    for (Long value : values) {
        if (Math.abs(value - median) <= median * 0.1) { // Within 10% of median
            total += value;
            validResponses++;
        }
    }
    
    if (validResponses < minResponses) {
        return new ShardedCounterResponse(false, 0, "Inconsistent shard responses");
    }
    
    return new ShardedCounterResponse(true, total, shardValues);
}
```

## Eventual Consistency Model

The distributed nature of the system means that read operations operate under an eventual consistency model. This has important implications for application design.

### Understanding Eventual Consistency

```java
// Example of eventual consistency behavior
public void demonstrateEventualConsistency() {
    // Write to shard 1
    increment("global_likes", 5); // Shard 1: 5
    
    // Immediate read might not see the update
    long immediateRead = getTotalValue("global_likes"); // Might return 0
    
    // After some time, the read will see the update
    Thread.sleep(100);
    long eventualRead = getTotalValue("global_likes"); // Will return 5
}
```

The eventual consistency model provides:

- **High Availability**: System continues operating even with network partitions
- **High Performance**: No coordination overhead between shards
- **Scalability**: Can add shards without coordination complexity

### Consistency Levels

The system supports different consistency levels:

```java
// From ShardedCounterCoordinator.java
public enum ConsistencyLevel {
    EVENTUAL,      // Return results from available shards
    QUORUM,        // Require majority of shards to respond
    STRONG         // Require all shards to respond
}

public ShardedCounterResponse getTotalValueWithConsistency(String counterId, ConsistencyLevel level) {
    Map<String, Long> shardValues = queryAllShards(counterId);
    
    switch (level) {
        case EVENTUAL:
            return aggregateShardValues(shardValues);
            
        case QUORUM:
            int requiredResponses = (shardAddresses.size() / 2) + 1;
            if (shardValues.size() < requiredResponses) {
                return new ShardedCounterResponse(false, 0, "Quorum not reached");
            }
            return aggregateShardValues(shardValues);
            
        case STRONG:
            if (shardValues.size() < shardAddresses.size()) {
                return new ShardedCounterResponse(false, 0, "Not all shards responded");
            }
            return aggregateShardValues(shardValues);
            
        default:
            throw new IllegalArgumentException("Unknown consistency level");
    }
}
```

## Fault Tolerance in Reads

Read operations must handle various failure scenarios gracefully.

### Shard Failure Handling

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse handleShardFailures(Map<String, ShardResponse> responses) {
    Map<String, Long> successfulResponses = new HashMap<>();
    List<String> failedShards = new ArrayList<>();
    
    for (Map.Entry<String, ShardResponse> entry : responses.entrySet()) {
        String shardAddress = entry.getKey();
        ShardResponse response = entry.getValue();
        
        if (response.getError() != null) {
            failedShards.add(shardAddress);
            logger.warn("Shard failed: " + shardAddress + ", error: " + response.getError().getMessage());
        } else if (response.getResponse().isSuccess()) {
            successfulResponses.put(shardAddress, response.getResponse().getShardValue());
        }
    }
    
    // Continue with available shards
    if (successfulResponses.isEmpty()) {
        return new ShardedCounterResponse(false, 0, "All shards failed");
    }
    
    long totalValue = aggregateShardValues(successfulResponses);
    return new ShardedCounterResponse(true, totalValue, successfulResponses);
}
```

### Timeout and Retry Logic

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse queryShardWithRetry(String shardAddress, String counterId) {
    int maxRetries = 2;
    int retryCount = 0;
    
    while (retryCount <= maxRetries) {
        try {
            return httpClient.get(shardAddress + "/sharded/" + counterId)
                .timeout(2, TimeUnit.SECONDS)
                .execute();
        } catch (TimeoutException e) {
            logger.warn("Timeout querying shard: " + shardAddress + ", retry: " + retryCount);
            retryCount++;
            if (retryCount <= maxRetries) {
                Thread.sleep(100 * retryCount);
            }
        } catch (Exception e) {
            logger.error("Error querying shard: " + shardAddress, e);
            break;
        }
    }
    
    return new ShardedCounterResponse(false, 0, "Shard unavailable");
}
```

## Performance Trade-offs

Read operations involve several performance trade-offs that must be carefully considered.

### Latency vs Completeness

```java
// From ShardedCounterCoordinator.java
public ShardedCounterResponse getTotalValueWithTimeout(String counterId, long timeoutMs) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    
    // Query shards with individual timeouts
    List<CompletableFuture<ShardResponse>> futures = shardAddresses.stream()
        .map(shardAddress -> CompletableFuture.supplyAsync(() -> 
            queryShardWithTimeout(shardAddress, counterId, timeoutMs)))
        .collect(Collectors.toList());
    
    // Wait for responses with overall timeout
    try {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        logger.warn("Overall timeout reached, proceeding with available responses");
    }
    
    // Aggregate available responses
    for (CompletableFuture<ShardResponse> future : futures) {
        if (future.isDone()) {
            try {
                ShardResponse response = future.get();
                if (response.getResponse().isSuccess()) {
                    shardValues.put(response.getShardAddress(), response.getResponse().getShardValue());
                    totalValue += response.getResponse().getShardValue();
                }
            } catch (Exception e) {
                logger.error("Error processing shard response", e);
            }
        }
    }
    
    return new ShardedCounterResponse(true, totalValue, shardValues);
}
```

### Caching Strategies

To improve read performance, the system implements several caching strategies:

```java
// From ShardedCounterCoordinator.java
private final Cache<String, ShardedCounterResponse> readCache = CacheBuilder.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(1, TimeUnit.SECONDS)
    .build();

public ShardedCounterResponse getTotalValueWithCache(String counterId) {
    // Check cache first
    ShardedCounterResponse cached = readCache.getIfPresent(counterId);
    if (cached != null) {
        return cached;
    }
    
    // Query shards and cache result
    ShardedCounterResponse response = getTotalValue(counterId);
    if (response.isSuccess()) {
        readCache.put(counterId, response);
    }
    
    return response;
}
```

## Caching Strategies

The system implements multiple caching layers to optimize read performance.

### Coordinator-Level Caching

```java
// From ShardedCounterCoordinator.java
public class CoordinatorCache {
    private final LoadingCache<String, Long> counterCache;
    private final LoadingCache<String, Map<String, Long>> shardValuesCache;
    
    public CoordinatorCache() {
        this.counterCache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(500, TimeUnit.MILLISECONDS)
            .build(new CacheLoader<String, Long>() {
                @Override
                public Long load(String counterId) {
                    return queryAllShardsAndAggregate(counterId);
                }
            });
        
        this.shardValuesCache = CacheBuilder.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(200, TimeUnit.MILLISECONDS)
            .build(new CacheLoader<String, Map<String, Long>>() {
                @Override
                public Map<String, Long> load(String counterId) {
                    return queryAllShards(counterId);
                }
            });
    }
}
```

### Cache Invalidation

```java
// From ShardedCounterCoordinator.java
public void invalidateCache(String counterId) {
    counterCache.invalidate(counterId);
    shardValuesCache.invalidate(counterId);
}

// Called after write operations
public ShardedCounterResponse handleWriteOperation(ShardedCounterOperation operation) {
    ShardedCounterResponse response = routeToShard(operation);
    
    if (response.isSuccess()) {
        // Invalidate cache to ensure consistency
        invalidateCache(operation.getCounterId());
    }
    
    return response;
}
```

### Cache Consistency

The system must balance cache performance with consistency:

```java
// From ShardedCounterCoordinator.java
public enum CacheConsistencyLevel {
    STRICT,     // Always invalidate cache on writes
    RELAXED,    // Allow cache to expire naturally
    ADAPTIVE    // Adjust based on write frequency
}

public class AdaptiveCacheManager {
    private final Map<String, AtomicLong> writeCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWriteTime = new ConcurrentHashMap<>();
    
    public void recordWrite(String counterId) {
        writeCounters.computeIfAbsent(counterId, k -> new AtomicLong()).incrementAndGet();
        lastWriteTime.put(counterId, System.currentTimeMillis());
    }
    
    public boolean shouldInvalidateCache(String counterId) {
        AtomicLong counter = writeCounters.get(counterId);
        if (counter == null) {
            return false;
        }
        
        long writeCount = counter.get();
        long timeSinceLastWrite = System.currentTimeMillis() - lastWriteTime.getOrDefault(counterId, 0L);
        
        // Invalidate if high write frequency or recent writes
        return writeCount > 10 || timeSinceLastWrite < 1000;
    }
}
```

---

*This chapter explored the complexities of read operations in a distributed system, including aggregation strategies, consistency models, and performance optimizations. In the next chapter, we'll examine data replication strategies for achieving stronger consistency guarantees.* 