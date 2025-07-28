# Chapter 7: Performance Analysis and Optimization

## Throughput Analysis

The distributed sharded counter system achieves remarkable performance improvements over traditional database approaches. Understanding the performance characteristics is crucial for capacity planning and optimization.

### Mathematical Performance Model

The performance of the distributed system can be modeled mathematically:

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

```java
// Batch processing for high-throughput scenarios
public class BatchProcessor {
    private final Map<String, List<ShardedCounterOperation>> batchQueue = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final int maxBatchSize = 100;
    private final long maxBatchDelay = 10; // milliseconds
    
    public void submitOperation(String counterId, ShardedCounterOperation operation) {
        batchQueue.computeIfAbsent(counterId, k -> new ArrayList<>()).add(operation);
        
        // Trigger batch processing if queue is full
        List<ShardedCounterOperation> batch = batchQueue.get(counterId);
        if (batch.size() >= maxBatchSize) {
            processBatch(counterId, batch);
        }
    }
    
    private void processBatch(String counterId, List<ShardedCounterOperation> operations) {
        // Calculate total delta
        long totalDelta = operations.stream()
                .mapToLong(ShardedCounterOperation::getDelta)
                .sum();
        
        // Create single batched operation
        ShardedCounterOperation batchedOp = new ShardedCounterOperation();
        batchedOp.setCounterId(counterId);
        batchedOp.setOperationType("INCREMENT");
        batchedOp.setDelta(totalDelta);
        
        // Send to appropriate shard
        String targetShard = hashRing.get(counterId);
        routeToShard(targetShard, batchedOp);
        
        // Clear the batch
        batchQueue.remove(counterId);
    }
    
    public void startBatchScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            // Process all pending batches
            batchQueue.forEach((counterId, operations) -> {
                if (!operations.isEmpty()) {
                    processBatch(counterId, new ArrayList<>(operations));
                }
            });
        }, maxBatchDelay, maxBatchDelay, TimeUnit.MILLISECONDS);
    }
}
```

### 3. Asynchronous Processing and Write-Behind

```java
// Asynchronous write-behind implementation
public class AsyncWriteBehindStorage {
    private final Map<String, Long> inMemoryCache = new ConcurrentHashMap<>();
    private final RocksDBStorage rocksDB;
    private final BlockingQueue<WriteOperation> writeQueue = new LinkedBlockingQueue<>();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong pendingWrites = new AtomicLong(0);
    
    public AsyncWriteBehindStorage(String dbPath) throws Exception {
        this.rocksDB = new RocksDBStorage(dbPath);
        startBackgroundWriter();
    }
    
    public long increment(String counterId, long delta) {
        // Immediate in-memory update
        long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            return current + delta;
        });
        
        // Queue for background persistence
        writeQueue.offer(new WriteOperation(counterId, newValue));
        pendingWrites.incrementAndGet();
        
        return newValue;
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
                        
                        // Persist batch
                        persistBatch(batch);
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
    
    private void persistBatch(List<WriteOperation> operations) {
        try (WriteBatch batch = new WriteBatch()) {
            for (WriteOperation op : operations) {
                batch.put(op.getKey().getBytes(), 
                         String.valueOf(op.getValue()).getBytes());
            }
            
            WriteOptions writeOptions = new WriteOptions();
            writeOptions.setSync(false); // Asynchronous for performance
            rocksDB.getDb().write(writeOptions, batch);
        } catch (Exception e) {
            logger.error("Failed to persist batch", e);
        }
    }
}
```

### 4. Read Optimization with Caching

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

---

*This chapter provided comprehensive performance optimization strategies and implementations that can be applied to the distributed sharded counter system. The next chapter will explore fault tolerance and high availability mechanisms to ensure system reliability under various failure scenarios.* 