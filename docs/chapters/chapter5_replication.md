# Chapter 5: Data Replication and Consistency

## Replication Architecture Overview

Data replication is a critical component of the distributed sharded counter system, providing fault tolerance, improved read performance, and stronger consistency guarantees. The replication system ensures that data remains available even when individual nodes fail, and provides mechanisms for maintaining consistency across replicas.

### Primary-Replica Architecture

The system implements a primary-replica architecture where each shard has one primary node and multiple replica nodes:

Primary-replica architecture provides fault tolerance:

```java
public class ReplicationConfig {
    private final String primaryShard;
    private final List<String> replicaShards;
    private final ReplicationStrategy strategy;
    private final int replicationFactor;
    
    public ReplicationConfig(String primaryShard, List<String> replicaShards, 
                           ReplicationStrategy strategy) {
        this.primaryShard = primaryShard;
        this.replicaShards = new ArrayList<>(replicaShards);
        this.strategy = strategy;
        this.replicationFactor = replicaShards.size() + 1; // +1 for primary
    }
    
    public enum ReplicationStrategy {
        SYNCHRONOUS,    // Wait for all replicas
        ASYNCHRONOUS,   // Don't wait for replicas
        SEMI_SYNCHRONOUS // Wait for majority
    }
}
```

For the complete primary-replica architecture implementation with health monitoring, see **Listing 5.1** in the appendix.

This architecture provides:
- **Fault Tolerance**: Data survives individual node failures
- **Read Scalability**: Replicas can handle read requests
- **Consistency**: Multiple copies ensure data availability
- **Performance**: Replicas can be geographically distributed

## Replication Strategies

The system supports multiple replication strategies to balance consistency, availability, and performance requirements.

### Synchronous Replication

Synchronous replication ensures that all replicas are updated before the operation completes:

Synchronous replication ensures all replicas are updated:

```java
public class SynchronousReplicationManager {
    private final ReplicationConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public SynchronousReplicationManager(ReplicationConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public CompletableFuture<ReplicationResult> replicateSynchronously(
            String counterId, long delta, String operation) {
        
        List<CompletableFuture<ReplicaResponse>> replicaFutures = new ArrayList<>();
        
        // Send operation to all replicas
        for (String replicaAddress : config.getReplicaShards()) {
            CompletableFuture<ReplicaResponse> future = sendToReplica(
                replicaAddress, counterId, delta, operation);
            replicaFutures.add(future);
        }
        
        // Wait for all replicas to complete
        return CompletableFuture.allOf(
                replicaFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Check if all replicas succeeded
                    boolean allSucceeded = replicaFutures.stream()
                            .allMatch(f -> {
                                try {
                                    return f.get().isSuccess();
                                } catch (Exception e) {
                                    return false;
                                }
                            });
                    
                    if (allSucceeded) {
                        return ReplicationResult.success();
                    } else {
                        return ReplicationResult.failure("Some replicas failed");
                    }
                });
    }
    
    private CompletableFuture<ReplicaResponse> sendToReplica(
            String replicaAddress, String counterId, long delta, String operation) {
        
        try {
            ReplicationRequest request = new ReplicationRequest(counterId, delta, operation);
            String jsonRequest = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + replicaAddress + "/replicate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            
            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            return ReplicaResponse.success();
                        } else {
                            return ReplicaResponse.failure("HTTP error: " + response.statusCode());
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ReplicaResponse.failure("Communication error: " + e.getMessage()));
        }
    }
}
```

</details>

Synchronous replication provides:
- **Strong Consistency**: All replicas are updated before operation completes
- **Data Safety**: No data loss even if primary fails immediately after write
- **Higher Latency**: Operations wait for all replicas
- **Lower Throughput**: Limited by slowest replica

### Asynchronous Replication

Asynchronous replication provides better performance by not waiting for replicas:

Asynchronous replication provides high performance:

```java
public class AsynchronousReplicationManager {
    private final ReplicationConfig config;
    private final BlockingQueue<ReplicationTask> replicationQueue;
    private final ExecutorService replicationExecutor;
    
    public AsynchronousReplicationManager(ReplicationConfig config) {
        this.config = config;
        this.replicationQueue = new LinkedBlockingQueue<>();
        this.replicationExecutor = Executors.newSingleThreadExecutor();
        startReplicationWorker();
    }
    
    public void replicateAsynchronously(String counterId, long delta, String operation) {
        ReplicationTask task = new ReplicationTask(counterId, delta, operation);
        replicationQueue.offer(task);
    }
}
```

For the complete asynchronous replication implementation with background processing, see **Listing 5.2** in the appendix.

Asynchronous replication provides:
- **High Performance**: Operations complete immediately
- **High Throughput**: Not limited by replica performance
- **Eventual Consistency**: Replicas are updated eventually
- **Potential Data Loss**: Data may be lost if primary fails before replication

### Semi-Synchronous Replication

Semi-synchronous replication waits for a majority of replicas:

Semi-synchronous replication waits for quorum:

```java
public class SemiSynchronousReplicationManager {
    private final ReplicationConfig config;
    private final int quorumSize;
    
    public SemiSynchronousReplicationManager(ReplicationConfig config) {
        this.config = config;
        this.quorumSize = (config.getReplicationFactor() / 2) + 1;
    }
    
    public CompletableFuture<ReplicationResult> replicateSemiSynchronously(
            String counterId, long delta, String operation) {
        
        List<CompletableFuture<ReplicaResponse>> replicaFutures = new ArrayList<>();
        
        // Send to all replicas
        for (String replicaAddress : config.getReplicaShards()) {
            CompletableFuture<ReplicaResponse> future = sendToReplica(
                replicaAddress, counterId, delta, operation);
            replicaFutures.add(future);
        }
        
        // Wait for quorum
        return waitForQuorum(replicaFutures);
    }
}
```

For the complete semi-synchronous replication implementation with quorum management, see **Listing 5.3** in the appendix.

Semi-synchronous replication provides:
- **Balanced Performance**: Better than synchronous, worse than asynchronous
- **Quorum Consistency**: Ensures majority of replicas are updated
- **Fault Tolerance**: Can tolerate minority of replica failures
- **Configurable**: Quorum size can be adjusted

## Consistency Models

The replication system supports different consistency models to meet various application requirements.

### Strong Consistency

Strong consistency ensures that all replicas have the same view of data:

Strong consistency ensures all replicas have the same view:

```java
public class StrongConsistencyManager {
    private final ReplicationConfig config;
    private final Map<String, Long> versionNumbers = new ConcurrentHashMap<>();
    
    public StrongConsistencyManager(ReplicationConfig config) {
        this.config = config;
    }
    
    public CompletableFuture<ConsistencyResult> writeWithStrongConsistency(
            String counterId, long delta, String operation) {
        
        // Generate new version number
        long newVersion = versionNumbers.compute(counterId, (key, oldVersion) -> 
            (oldVersion != null ? oldVersion : 0) + 1);
        
        // Prepare write with version
        VersionedWrite write = new VersionedWrite(counterId, delta, operation, newVersion);
        
        // Send to all replicas synchronously
        List<CompletableFuture<ReplicaResponse>> replicaFutures = new ArrayList<>();
        
        for (String replicaAddress : config.getAllShards()) {
            CompletableFuture<ReplicaResponse> future = sendVersionedWrite(
                replicaAddress, write);
            replicaFutures.add(future);
        }
        
        // Wait for all replicas to acknowledge
        return CompletableFuture.allOf(
                replicaFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    boolean allAcknowledged = replicaFutures.stream()
                            .allMatch(f -> {
                                try {
                                    return f.get().isSuccess();
                                } catch (Exception e) {
                                    return false;
                                }
                            });
                    
                    if (allAcknowledged) {
                        return ConsistencyResult.success(newVersion);
                    } else {
                        return ConsistencyResult.failure("Not all replicas acknowledged");
                    }
                });
    }
    
    public CompletableFuture<Long> readWithStrongConsistency(String counterId) {
        // Read from all replicas and verify consistency
        List<CompletableFuture<VersionedRead>> readFutures = new ArrayList<>();
        
        for (String replicaAddress : config.getAllShards()) {
            CompletableFuture<VersionedRead> future = readFromReplica(replicaAddress, counterId);
            readFutures.add(future);
        }
        
        return CompletableFuture.allOf(
                readFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Verify all replicas have same version
                    List<VersionedRead> reads = readFutures.stream()
                            .map(f -> {
                                try {
                                    return f.get();
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    
                    if (reads.isEmpty()) {
                        throw new RuntimeException("No replicas available");
                    }
                    
                    // Check version consistency
                    long expectedVersion = reads.get(0).getVersion();
                    boolean allConsistent = reads.stream()
                            .allMatch(read -> read.getVersion() == expectedVersion);
                    
                    if (!allConsistent) {
                        throw new RuntimeException("Inconsistent versions across replicas");
                    }
                    
                    return reads.get(0).getValue();
                });
    }
}
```

</details>

Strong consistency provides:
- **Linearizability**: All operations appear to execute atomically
- **Global Ordering**: All replicas see operations in the same order
- **High Latency**: Operations wait for all replicas
- **Lower Availability**: System unavailable if any replica is down

### Eventual Consistency

Eventual consistency allows replicas to diverge temporarily:

<details>
<summary><strong>Eventual Consistency Implementation</strong></summary>

```java
// Eventual consistency implementation
public class EventualConsistencyManager {
    private final ReplicationConfig config;
    private final Map<String, VectorClock> vectorClocks = new ConcurrentHashMap<>();
    
    public EventualConsistencyManager(ReplicationConfig config) {
        this.config = config;
    }
    
    public CompletableFuture<ConsistencyResult> writeWithEventualConsistency(
            String counterId, long delta, String operation) {
        
        // Update vector clock
        VectorClock clock = vectorClocks.computeIfAbsent(counterId, k -> new VectorClock());
        clock.increment(config.getPrimaryShard());
        
        // Send to primary immediately
        CompletableFuture<ReplicationResult> primaryFuture = sendToPrimary(
            config.getPrimaryShard(), counterId, delta, operation, clock);
        
        // Replicate to replicas asynchronously
        List<CompletableFuture<Void>> replicaFutures = new ArrayList<>();
        for (String replicaAddress : config.getReplicaShards()) {
            CompletableFuture<Void> future = replicateAsync(
                replicaAddress, counterId, delta, operation, clock);
            replicaFutures.add(future);
        }
        
        // Return immediately after primary acknowledgment
        return primaryFuture.thenApply(result -> {
            // Fire and forget for replicas
            CompletableFuture.allOf(replicaFutures.toArray(new CompletableFuture[0]))
                    .exceptionally(throwable -> {
                        logger.warn("Async replication failed for counter: " + counterId, throwable);
                        return null;
                    });
            
            return ConsistencyResult.success(clock.getTimestamp());
        });
    }
    
    public CompletableFuture<Long> readWithEventualConsistency(String counterId) {
        // Read from any available replica
        List<String> allShards = new ArrayList<>(config.getAllShards());
        Collections.shuffle(allShards); // Random selection for load balancing
        
        for (String shardAddress : allShards) {
            try {
                CompletableFuture<Long> future = readFromShard(shardAddress, counterId);
                return future;
            } catch (Exception e) {
                logger.warn("Failed to read from shard: " + shardAddress, e);
                continue;
            }
        }
        
        return CompletableFuture.failedFuture(
            new RuntimeException("No shards available for reading"));
    }
}
```

</details>

Eventual consistency provides:
- **High Availability**: System continues operating with partial failures
- **Low Latency**: Operations complete quickly
- **High Throughput**: No coordination between replicas
- **Potential Staleness**: Reads may return slightly stale data

### Read-Your-Writes Consistency

Read-your-writes consistency ensures clients see their own writes:

Read-your-writes consistency ensures clients see their own writes:

```java
public class ReadYourWritesManager {
    private final ReplicationConfig config;
    private final Map<String, Long> clientTimestamps = new ConcurrentHashMap<>();
    
    public ReadYourWritesManager(ReplicationConfig config) {
        this.config = config;
    }
    
    public CompletableFuture<ConsistencyResult> writeWithReadYourWrites(
            String clientId, String counterId, long delta, String operation) {
        
        // Generate client timestamp
        long clientTimestamp = System.currentTimeMillis();
        clientTimestamps.put(clientId, clientTimestamp);
        
        // Send to primary with client timestamp
        CompletableFuture<ReplicationResult> primaryFuture = sendToPrimaryWithTimestamp(
            config.getPrimaryShard(), counterId, delta, operation, clientTimestamp);
        
        // Replicate to replicas asynchronously
        List<CompletableFuture<Void>> replicaFutures = new ArrayList<>();
        for (String replicaAddress : config.getReplicaShards()) {
            CompletableFuture<Void> future = replicateWithTimestamp(
                replicaAddress, counterId, delta, operation, clientTimestamp);
            replicaFutures.add(future);
        }
        
        return primaryFuture.thenApply(result -> {
            // Fire and forget for replicas
            CompletableFuture.allOf(replicaFutures.toArray(new CompletableFuture[0]))
                    .exceptionally(throwable -> {
                        logger.warn("Async replication failed", throwable);
                        return null;
                    });
            
            return ConsistencyResult.success(clientTimestamp);
        });
    }
    
    public CompletableFuture<Long> readWithReadYourWrites(String clientId, String counterId) {
        Long lastWriteTimestamp = clientTimestamps.get(clientId);
        
        if (lastWriteTimestamp == null) {
            // No previous writes, can read from any replica
            return readWithEventualConsistency(counterId);
        }
        
        // Must read from replica that has seen our last write
        return findReplicaWithTimestamp(counterId, lastWriteTimestamp)
                .thenCompose(replicaAddress -> {
                    if (replicaAddress != null) {
                        return readFromShard(replicaAddress, counterId);
                    } else {
                        // Fall back to eventual consistency
                        return readWithEventualConsistency(counterId);
                    }
                });
    }
    
    private CompletableFuture<String> findReplicaWithTimestamp(
            String counterId, long requiredTimestamp) {
        
        List<CompletableFuture<ReplicaTimestamp>> timestampFutures = new ArrayList<>();
        
        for (String replicaAddress : config.getAllShards()) {
            CompletableFuture<ReplicaTimestamp> future = getReplicaTimestamp(
                replicaAddress, counterId);
            timestampFutures.add(future);
        }
        
        return CompletableFuture.allOf(
                timestampFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    for (int i = 0; i < timestampFutures.size(); i++) {
                        try {
                            ReplicaTimestamp timestamp = timestampFutures.get(i).get();
                            if (timestamp.getTimestamp() >= requiredTimestamp) {
                                return config.getAllShards().get(i);
                            }
                        } catch (Exception e) {
                            // Skip failed replicas
                        }
                    }
                    return null; // No replica has seen our write
                });
    }
}
```

</details>

Read-your-writes consistency provides:
- **Client Consistency**: Clients always see their own writes
- **Session Guarantees**: Writes are visible within the same session
- **Moderate Performance**: Better than strong consistency, worse than eventual
- **Implementation Complexity**: Requires tracking client write timestamps

## Conflict Resolution

When replicas diverge, the system must resolve conflicts to maintain consistency.

### Timestamp-Based Resolution

Timestamp-based resolution uses timestamps to determine the winning value:

<details>
<summary><strong>Timestamp-Based Conflict Resolution Implementation</strong></summary>

```java
// Timestamp-based conflict resolution
public class TimestampBasedConflictResolver {
    
    public ConflictResolutionResult resolveTimestampConflict(
            List<ConflictingValue> conflictingValues) {
        
        // Find the value with the latest timestamp
        ConflictingValue latestValue = conflictingValues.stream()
                .max(Comparator.comparing(ConflictingValue::getTimestamp))
                .orElse(null);
        
        if (latestValue == null) {
            return ConflictResolutionResult.failure("No valid values found");
        }
        
        return ConflictResolutionResult.success(latestValue.getValue(), latestValue.getTimestamp());
    }
    
    public static class ConflictingValue {
        private final long value;
        private final long timestamp;
        private final String source;
        
        public ConflictingValue(long value, long timestamp, String source) {
            this.value = value;
            this.timestamp = timestamp;
            this.source = source;
        }
        
        // Getters
        public long getValue() { return value; }
        public long getTimestamp() { return timestamp; }
        public String getSource() { return source; }
    }
}
```

</details>

Timestamp-based resolution provides:
- **Simple Logic**: Easy to understand and implement
- **Deterministic**: Same conflict always resolves the same way
- **Clock Dependency**: Requires synchronized clocks
- **Potential Data Loss**: Loses updates with older timestamps

### Vector Clock Resolution

Vector clocks provide more sophisticated conflict detection:

<details>
<summary><strong>Vector Clock Conflict Resolution Implementation</strong></summary>

```java
// Vector clock-based conflict resolution
public class VectorClockConflictResolver {
    
    public ConflictResolutionResult resolveVectorClockConflict(
            List<VectorClockValue> conflictingValues) {
        
        // Check for concurrent updates
        List<VectorClockValue> concurrentUpdates = findConcurrentUpdates(conflictingValues);
        
        if (concurrentUpdates.size() > 1) {
            // True conflict - multiple concurrent updates
            return resolveTrueConflict(concurrentUpdates);
        } else if (concurrentUpdates.size() == 1) {
            // No conflict - one update dominates
            VectorClockValue dominant = concurrentUpdates.get(0);
            return ConflictResolutionResult.success(dominant.getValue(), dominant.getVectorClock());
        } else {
            // No concurrent updates found
            return ConflictResolutionResult.failure("No valid concurrent updates");
        }
    }
    
    private List<VectorClockValue> findConcurrentUpdates(List<VectorClockValue> values) {
        List<VectorClockValue> concurrent = new ArrayList<>();
        
        for (VectorClockValue value1 : values) {
            boolean isConcurrent = true;
            
            for (VectorClockValue value2 : values) {
                if (value1 != value2) {
                    if (value1.getVectorClock().happensBefore(value2.getVectorClock())) {
                        isConcurrent = false;
                        break;
                    }
                }
            }
            
            if (isConcurrent) {
                concurrent.add(value1);
            }
        }
        
        return concurrent;
    }
    
    private ConflictResolutionResult resolveTrueConflict(List<VectorClockValue> concurrentUpdates) {
        // For true conflicts, we can implement various strategies:
        // 1. Last-writer-wins (by timestamp)
        // 2. Merge values (for counters, sum the deltas)
        // 3. Application-specific resolution
        
        // For counter operations, merge the values
        long mergedValue = concurrentUpdates.stream()
                .mapToLong(VectorClockValue::getValue)
                .sum();
        
        // Create merged vector clock
        VectorClock mergedClock = new VectorClock();
        for (VectorClockValue value : concurrentUpdates) {
            mergedClock.merge(value.getVectorClock());
        }
        
        return ConflictResolutionResult.success(mergedValue, mergedClock);
    }
    
    public static class VectorClockValue {
        private final long value;
        private final VectorClock vectorClock;
        private final String source;
        
        public VectorClockValue(long value, VectorClock vectorClock, String source) {
            this.value = value;
            this.vectorClock = vectorClock;
            this.source = source;
        }
        
        // Getters
        public long getValue() { return value; }
        public VectorClock getVectorClock() { return vectorClock; }
        public String getSource() { return source; }
    }
}
```

</details>

Vector clock resolution provides:
- **Accurate Conflict Detection**: Detects true conflicts vs. causal relationships
- **Causal Ordering**: Maintains causal relationships between operations
- **Complex Implementation**: More complex than timestamp-based
- **No Clock Synchronization**: Doesn't require synchronized clocks

## Replication Lag Management

Replication lag can impact consistency and performance, requiring careful management.

### Lag Monitoring

The system monitors replication lag to detect issues:

<details>
<summary><strong>Replication Lag Monitoring Implementation</strong></summary>

```java
// Replication lag monitoring
public class ReplicationLagMonitor {
    private final Map<String, Long> replicaLags = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdateTimes = new ConcurrentHashMap<>();
    private final long lagThresholdMs;
    
    public ReplicationLagMonitor(long lagThresholdMs) {
        this.lagThresholdMs = lagThresholdMs;
    }
    
    public void recordReplication(String replicaAddress, long timestamp) {
        lastUpdateTimes.put(replicaAddress, timestamp);
        long lag = System.currentTimeMillis() - timestamp;
        replicaLags.put(replicaAddress, lag);
    }
    
    public List<String> getLaggingReplicas() {
        long currentTime = System.currentTimeMillis();
        
        return replicaLags.entrySet().stream()
                .filter(entry -> entry.getValue() > lagThresholdMs)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    public double getAverageLag() {
        return replicaLags.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
    }
    
    public Map<String, Long> getReplicaLags() {
        return new HashMap<>(replicaLags);
    }
    
    public boolean isReplicaHealthy(String replicaAddress) {
        Long lag = replicaLags.get(replicaAddress);
        return lag != null && lag <= lagThresholdMs;
    }
}
```

</details>

### Adaptive Replication

The system can adapt replication behavior based on lag:

<details>
<summary><strong>Adaptive Replication Implementation</strong></summary>

```java
// Adaptive replication based on lag
public class AdaptiveReplicationManager {
    private final ReplicationLagMonitor lagMonitor;
    private final ReplicationConfig config;
    private final long maxLagThresholdMs;
    
    public AdaptiveReplicationManager(ReplicationConfig config, long maxLagThresholdMs) {
        this.config = config;
        this.lagMonitor = new ReplicationLagMonitor(maxLagThresholdMs);
        this.maxLagThresholdMs = maxLagThresholdMs;
    }
    
    public CompletableFuture<ReplicationResult> replicateAdaptively(
            String counterId, long delta, String operation) {
        
        List<String> healthyReplicas = config.getReplicaShards().stream()
                .filter(lagMonitor::isReplicaHealthy)
                .collect(Collectors.toList());
        
        if (healthyReplicas.isEmpty()) {
            // No healthy replicas, use primary only
            return replicateToPrimaryOnly(counterId, delta, operation);
        } else if (healthyReplicas.size() >= config.getReplicationFactor() - 1) {
            // All replicas healthy, use synchronous replication
            return replicateSynchronously(counterId, delta, operation, healthyReplicas);
        } else {
            // Some replicas lagging, use semi-synchronous
            return replicateSemiSynchronously(counterId, delta, operation, healthyReplicas);
        }
    }
    
    private CompletableFuture<ReplicationResult> replicateToPrimaryOnly(
            String counterId, long delta, String operation) {
        
        logger.warn("No healthy replicas available, using primary-only replication");
        return sendToPrimary(config.getPrimaryShard(), counterId, delta, operation)
                .thenApply(result -> {
                    // Schedule async replication to unhealthy replicas
                    scheduleAsyncReplication(counterId, delta, operation);
                    return result;
                });
    }
    
    private void scheduleAsyncReplication(String counterId, long delta, String operation) {
        CompletableFuture.runAsync(() -> {
            for (String replicaAddress : config.getReplicaShards()) {
                if (!lagMonitor.isReplicaHealthy(replicaAddress)) {
                    try {
                        sendToReplica(replicaAddress, counterId, delta, operation);
                    } catch (Exception e) {
                        logger.error("Failed to replicate to lagging replica: " + replicaAddress, e);
                    }
                }
            }
        });
    }
}
```

</details>

## Disaster Recovery

The replication system provides disaster recovery capabilities.

### Automatic Failover

The system can automatically failover to healthy replicas:

<details>
<summary><strong>Automatic Failover Implementation</strong></summary>

```java
// Automatic failover implementation
public class AutomaticFailoverManager {
    private final ReplicationConfig config;
    private final ReplicationLagMonitor lagMonitor;
    private final Map<String, Boolean> replicaHealth = new ConcurrentHashMap<>();
    
    public AutomaticFailoverManager(ReplicationConfig config, ReplicationLagMonitor lagMonitor) {
        this.config = config;
        this.lagMonitor = lagMonitor;
        startHealthMonitoring();
    }
    
    private void startHealthMonitoring() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::checkReplicaHealth, 0, 30, TimeUnit.SECONDS);
    }
    
    private void checkReplicaHealth() {
        for (String replicaAddress : config.getReplicaShards()) {
            boolean healthy = lagMonitor.isReplicaHealthy(replicaAddress);
            boolean wasHealthy = replicaHealth.getOrDefault(replicaAddress, true);
            
            replicaHealth.put(replicaAddress, healthy);
            
            if (wasHealthy && !healthy) {
                logger.warn("Replica {} became unhealthy", replicaAddress);
                onReplicaFailure(replicaAddress);
            } else if (!wasHealthy && healthy) {
                logger.info("Replica {} recovered", replicaAddress);
                onReplicaRecovery(replicaAddress);
            }
        }
    }
    
    private void onReplicaFailure(String replicaAddress) {
        // Remove from active replica list
        config.removeReplica(replicaAddress);
        
        // Notify monitoring systems
        notifyMonitoringSystem("REPLICA_FAILURE", replicaAddress);
        
        // Check if we need to promote a new primary
        if (replicaAddress.equals(config.getPrimaryShard())) {
            promoteNewPrimary();
        }
    }
    
    private void onReplicaRecovery(String replicaAddress) {
        // Add back to active replica list
        config.addReplica(replicaAddress);
        
        // Trigger catch-up replication
        triggerCatchUpReplication(replicaAddress);
    }
    
    private void promoteNewPrimary() {
        List<String> healthyReplicas = config.getReplicaShards().stream()
                .filter(lagMonitor::isReplicaHealthy)
                .collect(Collectors.toList());
        
        if (!healthyReplicas.isEmpty()) {
            String newPrimary = healthyReplicas.get(0);
            config.setPrimaryShard(newPrimary);
            logger.info("Promoted {} to primary", newPrimary);
            
            // Notify all clients of primary change
            notifyClientsOfPrimaryChange(newPrimary);
        } else {
            logger.error("No healthy replicas available for primary promotion");
        }
    }
}
```

</details>

### Data Recovery

The system can recover data from replicas:

<details>
<summary><strong>Data Recovery Implementation</strong></summary>

```java
// Data recovery implementation
public class DataRecoveryManager {
    private final ReplicationConfig config;
    private final Map<String, RocksDBStorage> replicaStorages = new ConcurrentHashMap<>();
    
    public DataRecoveryManager(ReplicationConfig config) {
        this.config = config;
    }
    
    public CompletableFuture<RecoveryResult> recoverData(String counterId) {
        // Try to recover from all available replicas
        List<CompletableFuture<RecoveryAttempt>> recoveryAttempts = new ArrayList<>();
        
        for (String replicaAddress : config.getAllShards()) {
            CompletableFuture<RecoveryAttempt> attempt = recoverFromReplica(
                replicaAddress, counterId);
            recoveryAttempts.add(attempt);
        }
        
        return CompletableFuture.anyOf(
                recoveryAttempts.toArray(new CompletableFuture[0]))
                .thenApply(result -> {
                    RecoveryAttempt bestAttempt = null;
                    
                    for (CompletableFuture<RecoveryAttempt> attempt : recoveryAttempts) {
                        try {
                            RecoveryAttempt recovery = attempt.get();
                            if (recovery.isSuccess() && 
                                (bestAttempt == null || recovery.getTimestamp() > bestAttempt.getTimestamp())) {
                                bestAttempt = recovery;
                            }
                        } catch (Exception e) {
                            // Skip failed attempts
                        }
                    }
                    
                    if (bestAttempt != null) {
                        return RecoveryResult.success(bestAttempt.getValue(), bestAttempt.getTimestamp());
                    } else {
                        return RecoveryResult.failure("No successful recovery attempts");
                    }
                });
    }
    
    private CompletableFuture<RecoveryAttempt> recoverFromReplica(
            String replicaAddress, String counterId) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Connect to replica and read data
                RocksDBStorage storage = getReplicaStorage(replicaAddress);
                long value = storage.get(counterId);
                long timestamp = System.currentTimeMillis(); // In real implementation, get from metadata
                
                return RecoveryAttempt.success(value, timestamp, replicaAddress);
            } catch (Exception e) {
                logger.error("Failed to recover from replica: " + replicaAddress, e);
                return RecoveryAttempt.failure(replicaAddress, e.getMessage());
            }
        });
    }
    
    public static class RecoveryAttempt {
        private final boolean success;
        private final long value;
        private final long timestamp;
        private final String source;
        private final String error;
        
        private RecoveryAttempt(boolean success, long value, long timestamp, 
                             String source, String error) {
            this.success = success;
            this.value = value;
            this.timestamp = timestamp;
            this.source = source;
            this.error = error;
        }
        
        public static RecoveryAttempt success(long value, long timestamp, String source) {
            return new RecoveryAttempt(true, value, timestamp, source, null);
        }
        
        public static RecoveryAttempt failure(String source, String error) {
            return new RecoveryAttempt(false, 0, 0, source, error);
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public long getValue() { return value; }
        public long getTimestamp() { return timestamp; }
        public String getSource() { return source; }
        public String getError() { return error; }
    }
}
```

</details>

## Conclusion

The replication system in the distributed sharded counter provides robust fault tolerance and consistency guarantees. Through careful design of replication strategies, consistency models, and conflict resolution mechanisms, the system achieves both high availability and data safety.

The key design principles—appropriate consistency levels for different use cases, intelligent conflict resolution, and comprehensive disaster recovery—work together to create a system that can handle the demands of modern, high-scale applications while maintaining data integrity.

In the next chapter, we'll examine the storage layer implementation, exploring how the system manages data persistence and provides the foundation for all counter operations. 