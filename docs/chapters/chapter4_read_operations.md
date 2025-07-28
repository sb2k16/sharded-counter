# Chapter 4: Read Operations: Aggregation and Consistency

## Read Operation Lifecycle

Read operations in the distributed sharded counter system present unique challenges compared to write operations. While writes can be routed to a single shard using consistent hashing, reads must aggregate data from all shards to provide a complete view of the counter value.

### Multi-Shard Query Strategy

The coordinator implements a multi-shard query strategy that queries all available shards and aggregates their individual values:

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

This strategy provides:
- **Complete View**: Aggregates values from all healthy shards
- **Fault Tolerance**: Continues operating even if some shards are down
- **Transparency**: Returns individual shard values for debugging
- **Health Awareness**: Skips unhealthy shards automatically

### Individual Shard Queries

The coordinator queries each shard individually to get its contribution to the total counter value:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse queryShard(String shardAddress, String counterId) throws Exception {
    // Create query operation
    ShardedCounterOperation queryOp = new ShardedCounterOperation();
    queryOp.setOperationType("GET_TOTAL");
    queryOp.setCounterId(counterId);
    
    // Send request to shard
    String jsonRequest = objectMapper.writeValueAsString(queryOp);
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://" + shardAddress + "/sharded"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
            .timeout(Duration.ofSeconds(5))
            .build();
    
    HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
    
    if (response.statusCode() == 200) {
        return objectMapper.readValue(response.body(), ShardedCounterResponse.class);
    } else {
        throw new RuntimeException("Shard query failed with status: " + response.statusCode());
    }
}
```

Each shard processes the query by reading from its local storage:

```java
// From ShardNode.java
private FullHttpResponse handleShardedOperation(ShardedCounterOperation operation) throws Exception {
    String counterId = operation.getCounterId();
    String operationType = operation.getOperationType();
    
    long currentValue = 0;
    
    switch (operationType) {
        case "GET_TOTAL":
            currentValue = storage.get(counterId);
            break;
            
        // ... other operation types
    }
    
    ShardedCounterResponse response = ShardedCounterResponse.success(currentValue, null);
    return createShardedResponse(HttpResponseStatus.OK, response);
}
```

## Aggregation Algorithms

The system implements several aggregation strategies depending on the consistency requirements and performance needs.

### Simple Sum Aggregation

The default aggregation strategy is a simple sum of all shard values:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse handleGetTotal(ShardedCounterOperation operation) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    int successfulResponses = 0;
    
    // Query all shards and sum their values
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String shardAddress = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        if (!shardInfo.isHealthy()) {
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
    
    return ShardedCounterResponse.success(totalValue, shardValues);
}
```

This approach provides:
- **Simplicity**: Easy to understand and debug
- **Performance**: Minimal computation overhead
- **Fault Tolerance**: Continues with available shards
- **Transparency**: Shows individual shard contributions

### Weighted Aggregation

For scenarios where shards have different reliability or performance characteristics, the system can implement weighted aggregation:

```java
// Conceptual weighted aggregation
private ShardedCounterResponse handleGetTotalWeighted(ShardedCounterOperation operation) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    double totalWeight = 0;
    
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String shardAddress = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        if (!shardInfo.isHealthy()) {
            continue;
        }
        
        try {
            ShardedCounterResponse response = queryShard(shardAddress, operation.getCounterId());
            if (response.isSuccess()) {
                long shardValue = response.getShardValue();
                double weight = calculateShardWeight(shardInfo);
                
                shardValues.put(shardAddress, shardValue);
                totalValue += (shardValue * weight);
                totalWeight += weight;
            }
        } catch (Exception e) {
            logger.error("Failed to query shard: " + shardAddress, e);
        }
    }
    
    if (totalWeight > 0) {
        totalValue = (long) (totalValue / totalWeight);
    }
    
    return ShardedCounterResponse.success(totalValue, shardValues);
}

private double calculateShardWeight(ShardInfo shardInfo) {
    // Weight based on health, performance, and reliability
    double weight = 1.0;
    
    // Reduce weight for recently failed shards
    if (shardInfo.getLastSeen() < System.currentTimeMillis() - 60000) {
        weight *= 0.5;
    }
    
    return weight;
}
```

### Consensus-Based Aggregation

For high-consistency requirements, the system can implement consensus-based aggregation:

```java
// Conceptual consensus-based aggregation
private ShardedCounterResponse handleGetTotalConsensus(ShardedCounterOperation operation) {
    Map<String, Long> shardValues = new HashMap<>();
    List<Long> allValues = new ArrayList<>();
    
    // Collect all shard values
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String shardAddress = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        if (!shardInfo.isHealthy()) {
            continue;
        }
        
        try {
            ShardedCounterResponse response = queryShard(shardAddress, operation.getCounterId());
            if (response.isSuccess()) {
                long shardValue = response.getShardValue();
                shardValues.put(shardAddress, shardValue);
                allValues.add(shardValue);
            }
        } catch (Exception e) {
            logger.error("Failed to query shard: " + shardAddress, e);
        }
    }
    
    // Calculate consensus value (median for robustness)
    if (allValues.isEmpty()) {
        return ShardedCounterResponse.error("No shards available");
    }
    
    Collections.sort(allValues);
    long consensusValue = allValues.get(allValues.size() / 2);
    
    return ShardedCounterResponse.success(consensusValue, shardValues);
}
```

## Eventual Consistency Model

The distributed nature of the system means that read operations follow an eventual consistency model:

### Consistency Characteristics

- **Shard-Level Consistency**: Each shard maintains consistent state internally
- **Cross-Shard Eventual Consistency**: Total values eventually converge across all shards
- **Read-Your-Writes**: Writes are immediately visible to subsequent reads on the same shard
- **Monotonic Reads**: Within a single shard, reads are monotonically consistent

### Consistency Levels

The system can implement different consistency levels based on requirements:

```java
// From ShardedCounterCoordinator.java
public enum ConsistencyLevel {
    EVENTUAL,    // Return immediately with available data
    QUORUM,      // Wait for majority of shards
    STRONG       // Wait for all shards
}

private ShardedCounterResponse handleGetTotalWithConsistency(ShardedCounterOperation operation, 
        ConsistencyLevel consistencyLevel) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    int successfulResponses = 0;
    int totalShards = shards.size();
    
    // Query all shards
    for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
        String shardAddress = entry.getKey();
        ShardInfo shardInfo = entry.getValue();
        
        if (!shardInfo.isHealthy()) {
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
    
    // Apply consistency level requirements
    switch (consistencyLevel) {
        case EVENTUAL:
            // Return immediately with available data
            break;
            
        case QUORUM:
            // Wait for majority of shards
            if (successfulResponses < (totalShards / 2) + 1) {
                return ShardedCounterResponse.error("Quorum not reached");
            }
            break;
            
        case STRONG:
            // Wait for all shards
            if (successfulResponses < totalShards) {
                return ShardedCounterResponse.error("Not all shards available");
            }
            break;
    }
    
    return ShardedCounterResponse.success(totalValue, shardValues);
}
```

## Fault Tolerance in Reads

Read operations must handle shard failures gracefully while maintaining data availability.

### Shard Failure Handling

When shards fail, the coordinator continues operating with available shards:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse handleGetTotal(ShardedCounterOperation operation) {
    Map<String, Long> shardValues = new HashMap<>();
    long totalValue = 0;
    int successfulResponses = 0;
    
    // Query all shards, skipping unhealthy ones
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
    
    // Ensure we have at least some healthy shards
    if (successfulResponses == 0) {
        return ShardedCounterResponse.error("No healthy shards available");
    }
    
    return ShardedCounterResponse.success(totalValue, shardValues);
}
```

### Timeout and Retry Logic

The system implements timeout and retry logic for read operations:

```java
// From ShardedCounterCoordinator.java
private ShardedCounterResponse queryShard(String shardAddress, String counterId) throws Exception {
    ShardedCounterOperation queryOp = new ShardedCounterOperation();
    queryOp.setOperationType("GET_TOTAL");
    queryOp.setCounterId(counterId);
    
    String jsonRequest = objectMapper.writeValueAsString(queryOp);
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://" + shardAddress + "/sharded"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
            .timeout(Duration.ofSeconds(5))  // 5-second timeout
            .build();
    
    HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
    
    if (response.statusCode() == 200) {
        return objectMapper.readValue(response.body(), ShardedCounterResponse.class);
    } else {
        throw new RuntimeException("Shard query failed with status: " + response.statusCode());
    }
}
```

## Performance Trade-offs

Read operations involve several performance trade-offs that must be carefully balanced:

### Latency vs. Completeness

- **Low Latency**: Return immediately with available data (eventual consistency)
- **High Completeness**: Wait for all shards (strong consistency)
- **Balanced Approach**: Wait for majority of shards (quorum consistency)

### Parallel vs. Sequential Queries

The system queries shards in parallel for optimal performance:

```java
// Conceptual parallel query implementation
private ShardedCounterResponse handleGetTotalParallel(ShardedCounterOperation operation) {
    Map<String, Long> shardValues = new ConcurrentHashMap<>();
    AtomicLong totalValue = new AtomicLong(0);
    AtomicInteger successfulResponses = new AtomicInteger(0);
    
    // Query all shards in parallel
    List<CompletableFuture<Void>> futures = shards.entrySet().stream()
            .map(entry -> CompletableFuture.runAsync(() -> {
                String shardAddress = entry.getKey();
                ShardInfo shardInfo = entry.getValue();
                
                if (!shardInfo.isHealthy()) {
                    return;
                }
                
                try {
                    ShardedCounterResponse response = queryShard(shardAddress, operation.getCounterId());
                    if (response.isSuccess()) {
                        long shardValue = response.getShardValue();
                        shardValues.put(shardAddress, shardValue);
                        totalValue.addAndGet(shardValue);
                        successfulResponses.incrementAndGet();
                    }
                } catch (Exception e) {
                    logger.error("Failed to query shard: " + shardAddress, e);
                }
            }))
            .collect(Collectors.toList());
    
    // Wait for all queries to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    if (successfulResponses.get() == 0) {
        return ShardedCounterResponse.error("No healthy shards available");
    }
    
    return ShardedCounterResponse.success(totalValue.get(), shardValues);
}
```

## Caching Strategies

The system can implement various caching strategies to improve read performance:

### Coordinator-Level Caching

The coordinator can cache aggregated results to reduce shard queries:

```java
// Conceptual coordinator caching
public class ShardedCounterCoordinator {
    private final Map<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private final long cacheExpiryMs = 1000; // 1 second cache
    
    private ShardedCounterResponse handleGetTotalWithCache(ShardedCounterOperation operation) {
        String cacheKey = operation.getCounterId();
        CachedResult cached = resultCache.get(cacheKey);
        
        if (cached != null && !cached.isExpired()) {
            return cached.getResponse();
        }
        
        // Query shards and cache result
        ShardedCounterResponse response = handleGetTotal(operation);
        resultCache.put(cacheKey, new CachedResult(response, System.currentTimeMillis()));
        
        return response;
    }
    
    private static class CachedResult {
        private final ShardedCounterResponse response;
        private final long timestamp;
        private final long expiryMs;
        
        public CachedResult(ShardedCounterResponse response, long timestamp) {
            this.response = response;
            this.timestamp = timestamp;
            this.expiryMs = 1000;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > expiryMs;
        }
        
        public ShardedCounterResponse getResponse() {
            return response;
        }
    }
}
```

### Cache Invalidation

Cache invalidation ensures consistency when data changes:

```java
// Conceptual cache invalidation
public void invalidateCache(String counterId) {
    resultCache.remove(counterId);
}

// Called after write operations
private ShardedCounterResponse handleIncrement(ShardedCounterOperation operation) {
    ShardedCounterResponse response = routeToShard(targetShard, operation);
    
    if (response.isSuccess()) {
        // Invalidate cache to ensure consistency
        invalidateCache(operation.getCounterId());
    }
    
    return response;
}
```

### Consistency-Aware Caching

The system can implement consistency-aware caching that respects consistency levels:

```java
// Conceptual consistency-aware caching
private ShardedCounterResponse handleGetTotalWithConsistencyCache(ShardedCounterOperation operation, 
        ConsistencyLevel consistencyLevel) {
    String cacheKey = operation.getCounterId() + "_" + consistencyLevel;
    CachedResult cached = resultCache.get(cacheKey);
    
    if (cached != null && !cached.isExpired()) {
        return cached.getResponse();
    }
    
    // Query with specified consistency level
    ShardedCounterResponse response = handleGetTotalWithConsistency(operation, consistencyLevel);
    
    if (response.isSuccess()) {
        resultCache.put(cacheKey, new CachedResult(response, System.currentTimeMillis()));
    }
    
    return response;
}
```

---

*This chapter explored the complexities of read operations in the distributed sharded counter system. In the next chapter, we'll examine data replication strategies for achieving stronger consistency guarantees across the distributed system.* 