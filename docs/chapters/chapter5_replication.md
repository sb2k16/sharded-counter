# Chapter 5: Data Replication and Strong Consistency

## Replication Strategies

While the basic distributed sharded counter system provides high availability and performance through eventual consistency, many applications require stronger consistency guarantees. This chapter explores how to implement data replication to achieve stronger consistency while maintaining the performance benefits of the distributed architecture.

The replication system extends the basic architecture by adding replica nodes that maintain copies of shard data. This creates a primary-replica model where each shard has one or more replicas for fault tolerance and consistency.

## Primary-Replica Architecture

The replication system implements a primary-replica architecture where each shard has designated primary and replica nodes:

```java
// From ReplicatedShardedCoordinator.java
public class ReplicatedShardedCoordinator {
    private final Map<String, ShardReplicaSet> replicaSets;
    private final ConsistentHash hashRing;
    
    public static class ShardReplicaSet {
        private final String primaryAddress;
        private final List<String> replicaAddresses;
        private final ReplicationManager replicationManager;
        
        public ShardReplicaSet(String primary, List<String> replicas) {
            this.primaryAddress = primary;
            this.replicaAddresses = replicas;
            this.replicationManager = new ReplicationManager(primary, replicas);
        }
    }
}
```

The primary-replica architecture provides:

- **Fault Tolerance**: If the primary fails, a replica can take over
- **Read Scalability**: Read requests can be served by replicas
- **Consistency**: All replicas maintain the same data as the primary
- **Performance**: Writes go to primary, reads can use replicas

## Synchronous vs Asynchronous Replication

The system supports both synchronous and asynchronous replication modes, each with different trade-offs.

### Synchronous Replication

```java
// From ReplicationManager.java
public class ReplicationManager {
    private final String primaryAddress;
    private final List<String> replicaAddresses;
    private final ReplicationMode replicationMode;
    
    public enum ReplicationMode {
        SYNC,    // Wait for all replicas to acknowledge
        ASYNC,   // Fire and forget replication
        SEMI_SYNC // Wait for majority of replicas
    }
    
    public ShardedCounterResponse replicateWrite(ShardedCounterOperation operation) {
        // Write to primary first
        ShardedCounterResponse primaryResponse = writeToPrimary(operation);
        
        if (!primaryResponse.isSuccess()) {
            return primaryResponse;
        }
        
        // Replicate to replicas based on mode
        switch (replicationMode) {
            case SYNC:
                return replicateSynchronously(operation);
            case ASYNC:
                replicateAsynchronously(operation);
                return primaryResponse;
            case SEMI_SYNC:
                return replicateSemiSynchronously(operation);
            default:
                throw new IllegalArgumentException("Unknown replication mode");
        }
    }
    
    private ShardedCounterResponse replicateSynchronously(ShardedCounterOperation operation) {
        List<CompletableFuture<ShardedCounterResponse>> futures = new ArrayList<>();
        
        // Send to all replicas
        for (String replicaAddress : replicaAddresses) {
            CompletableFuture<ShardedCounterResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return httpClient.post(replicaAddress + "/replica/write", operation);
                } catch (Exception e) {
                    logger.error("Replica write failed: " + replicaAddress, e);
                    return new ShardedCounterResponse(false, 0, "Replica error");
                }
            });
            futures.add(future);
        }
        
        // Wait for all replicas to acknowledge
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.error("Synchronous replication timeout");
            return new ShardedCounterResponse(false, 0, "Replication timeout");
        }
        
        // Check if all replicas succeeded
        for (CompletableFuture<ShardedCounterResponse> future : futures) {
            try {
                ShardedCounterResponse response = future.get();
                if (!response.isSuccess()) {
                    return new ShardedCounterResponse(false, 0, "Replica failed");
                }
            } catch (Exception e) {
                return new ShardedCounterResponse(false, 0, "Replica error");
            }
        }
        
        return new ShardedCounterResponse(true, 0, "Replication successful");
    }
}
```

Synchronous replication provides:
- **Strong Consistency**: All replicas have the same data
- **Durability**: Data is guaranteed to be on multiple nodes
- **Higher Latency**: Must wait for all replicas to acknowledge
- **Lower Throughput**: Limited by slowest replica

### Asynchronous Replication

```java
// From ReplicationManager.java
private void replicateAsynchronously(ShardedCounterOperation operation) {
    // Fire and forget replication
    for (String replicaAddress : replicaAddresses) {
        CompletableFuture.runAsync(() -> {
            try {
                httpClient.post(replicaAddress + "/replica/write", operation);
            } catch (Exception e) {
                logger.error("Asynchronous replication failed: " + replicaAddress, e);
            }
        });
    }
}
```

Asynchronous replication provides:
- **High Performance**: No waiting for replica acknowledgments
- **Eventual Consistency**: Replicas will eventually have the data
- **Lower Latency**: Primary responds immediately
- **Higher Throughput**: Not limited by replica performance

### Semi-Synchronous Replication

```java
// From ReplicationManager.java
private ShardedCounterResponse replicateSemiSynchronously(ShardedCounterOperation operation) {
    int requiredAcks = (replicaAddresses.size() / 2) + 1; // Majority
    List<CompletableFuture<ShardedCounterResponse>> futures = new ArrayList<>();
    
    // Send to all replicas
    for (String replicaAddress : replicaAddresses) {
        CompletableFuture<ShardedCounterResponse> future = CompletableFuture.supplyAsync(() -> {
            try {
                return httpClient.post(replicaAddress + "/replica/write", operation);
            } catch (Exception e) {
                logger.error("Replica write failed: " + replicaAddress, e);
                return new ShardedCounterResponse(false, 0, "Replica error");
            }
        });
        futures.add(future);
    }
    
    // Wait for majority to acknowledge
    int successfulAcks = 0;
    try {
        for (CompletableFuture<ShardedCounterResponse> future : futures) {
            ShardedCounterResponse response = future.get(2, TimeUnit.SECONDS);
            if (response.isSuccess()) {
                successfulAcks++;
                if (successfulAcks >= requiredAcks) {
                    return new ShardedCounterResponse(true, 0, "Majority replication successful");
                }
            }
        }
    } catch (TimeoutException e) {
        logger.warn("Semi-synchronous replication timeout");
    }
    
    return new ShardedCounterResponse(false, 0, "Insufficient replica acknowledgments");
}
```

Semi-synchronous replication provides:
- **Balanced Consistency**: Majority of replicas have the data
- **Good Performance**: Only wait for majority, not all
- **Fault Tolerance**: Can tolerate minority replica failures
- **Reasonable Latency**: Better than synchronous, worse than asynchronous

## Consistency Models

The replication system supports different consistency models to meet various application requirements.

### Strong Consistency

```java
// From ReplicatedShardedCoordinator.java
public ShardedCounterResponse handleWriteWithStrongConsistency(ShardedCounterOperation operation) {
    // Route to primary shard
    String targetShard = hashRing.get(operation.getCounterId());
    ShardReplicaSet replicaSet = replicaSets.get(targetShard);
    
    // Write with synchronous replication
    return replicaSet.getReplicationManager().replicateWrite(operation);
}

public ShardedCounterResponse handleReadWithStrongConsistency(String counterId) {
    String targetShard = hashRing.get(counterId);
    ShardReplicaSet replicaSet = replicaSets.get(targetShard);
    
    // Read from primary to ensure consistency
    return queryShard(replicaSet.getPrimaryAddress(), counterId);
}
```

Strong consistency provides:
- **Linearizability**: All operations appear to happen atomically
- **Read-Your-Writes**: Reads always see your own writes
- **Monotonic Reads**: Reads never go backwards in time
- **Higher Latency**: Must coordinate between primary and replicas

### Eventual Consistency

```java
// From ReplicatedShardedCoordinator.java
public ShardedCounterResponse handleReadWithEventualConsistency(String counterId) {
    String targetShard = hashRing.get(counterId);
    ShardReplicaSet replicaSet = replicaSets.get(targetShard);
    
    // Read from any available replica
    List<String> allReplicas = new ArrayList<>();
    allReplicas.add(replicaSet.getPrimaryAddress());
    allReplicas.addAll(replicaSet.getReplicaAddresses());
    
    // Try replicas in order until one responds
    for (String replicaAddress : allReplicas) {
        try {
            ShardedCounterResponse response = queryShard(replicaAddress, counterId);
            if (response.isSuccess()) {
                return response;
            }
        } catch (Exception e) {
            logger.warn("Replica read failed: " + replicaAddress, e);
        }
    }
    
    return new ShardedCounterResponse(false, 0, "No replicas available");
}
```

Eventual consistency provides:
- **High Availability**: System continues operating with network partitions
- **Low Latency**: No coordination required for reads
- **High Throughput**: Can serve reads from any replica
- **Stale Data**: Reads may return slightly outdated values

### Read-Your-Writes Consistency

```java
// From ReplicatedShardedCoordinator.java
public class SessionManager {
    private final Map<String, String> sessionToPrimary = new ConcurrentHashMap<>();
    
    public ShardedCounterResponse handleReadYourWrites(String counterId, String sessionId) {
        String targetShard = hashRing.get(counterId);
        ShardReplicaSet replicaSet = replicaSets.get(targetShard);
        
        // If this session has written to this shard, read from primary
        String sessionPrimary = sessionToPrimary.get(sessionId);
        if (replicaSet.getPrimaryAddress().equals(sessionPrimary)) {
            return queryShard(replicaSet.getPrimaryAddress(), counterId);
        }
        
        // Otherwise, read from any replica
        return handleReadWithEventualConsistency(counterId);
    }
    
    public void recordWrite(String sessionId, String shardAddress) {
        sessionToPrimary.put(sessionId, shardAddress);
    }
}
```

Read-your-writes consistency provides:
- **Session Consistency**: Users see their own writes immediately
- **Good Performance**: Only primary reads for session writes
- **Replica Reads**: Other reads can use replicas
- **Session Tracking**: Requires tracking user sessions

## Conflict Resolution

When replicas become inconsistent, the system must resolve conflicts to maintain data integrity.

### Timestamp-Based Resolution

```java
// From ReplicationManager.java
public class ConflictResolver {
    public long resolveConflicts(Map<String, Long> replicaValues, Map<String, Long> timestamps) {
        // Find the most recent value
        String mostRecentReplica = null;
        long mostRecentTimestamp = 0;
        
        for (Map.Entry<String, Long> entry : timestamps.entrySet()) {
            if (entry.getValue() > mostRecentTimestamp) {
                mostRecentTimestamp = entry.getValue();
                mostRecentReplica = entry.getKey();
            }
        }
        
        return replicaValues.get(mostRecentReplica);
    }
}
```

### Vector Clock Resolution

```java
// From ReplicationManager.java
public class VectorClock {
    private final Map<String, Long> clock = new ConcurrentHashMap<>();
    
    public void increment(String nodeId) {
        clock.compute(nodeId, (key, value) -> (value == null) ? 1 : value + 1);
    }
    
    public boolean isConcurrent(VectorClock other) {
        boolean thisGreater = false;
        boolean otherGreater = false;
        
        Set<String> allNodes = new HashSet<>(clock.keySet());
        allNodes.addAll(other.clock.keySet());
        
        for (String node : allNodes) {
            long thisValue = clock.getOrDefault(node, 0L);
            long otherValue = other.clock.getOrDefault(node, 0L);
            
            if (thisValue > otherValue) {
                thisGreater = true;
            } else if (otherValue > thisValue) {
                otherGreater = true;
            }
        }
        
        return thisGreater && otherGreater;
    }
}
```

## Replication Lag Management

Replication lag occurs when replicas fall behind the primary. The system implements several strategies to manage this.

### Lag Monitoring

```java
// From ReplicationManager.java
public class ReplicationLagMonitor {
    private final Map<String, AtomicLong> replicaLags = new ConcurrentHashMap<>();
    
    public void recordReplicationLag(String replicaAddress, long lagMs) {
        replicaLags.computeIfAbsent(replicaAddress, k -> new AtomicLong()).set(lagMs);
    }
    
    public long getReplicationLag(String replicaAddress) {
        AtomicLong lag = replicaLags.get(replicaAddress);
        return lag != null ? lag.get() : 0;
    }
    
    public boolean isReplicaHealthy(String replicaAddress) {
        long lag = getReplicationLag(replicaAddress);
        return lag < 5000; // 5 seconds threshold
    }
    
    public List<String> getHealthyReplicas() {
        return replicaAddresses.stream()
            .filter(this::isReplicaHealthy)
            .collect(Collectors.toList());
    }
}
```

### Adaptive Replication

```java
// From ReplicationManager.java
public class AdaptiveReplicationManager {
    private final ReplicationLagMonitor lagMonitor;
    private ReplicationMode currentMode = ReplicationMode.SEMI_SYNC;
    
    public ShardedCounterResponse replicateWithAdaptiveMode(ShardedCounterOperation operation) {
        // Check replica health
        List<String> healthyReplicas = lagMonitor.getHealthyReplicas();
        
        if (healthyReplicas.size() == replicaAddresses.size()) {
            // All replicas healthy, use synchronous replication
            currentMode = ReplicationMode.SYNC;
        } else if (healthyReplicas.size() >= replicaAddresses.size() / 2) {
            // Majority healthy, use semi-synchronous
            currentMode = ReplicationMode.SEMI_SYNC;
        } else {
            // Many replicas lagging, use asynchronous
            currentMode = ReplicationMode.ASYNC;
        }
        
        return replicateWithMode(operation, currentMode);
    }
}
```

## Disaster Recovery

The replication system includes comprehensive disaster recovery capabilities.

### Automatic Failover

```java
// From ReplicationManager.java
public class FailoverManager {
    private final Map<String, String> primaryToReplica = new ConcurrentHashMap<>();
    
    public String getPrimaryAddress(String shardId) {
        String primary = primaryAddresses.get(shardId);
        
        // Check if primary is healthy
        if (!isNodeHealthy(primary)) {
            // Promote a healthy replica to primary
            String newPrimary = promoteReplicaToPrimary(shardId);
            primaryToReplica.put(shardId, newPrimary);
            return newPrimary;
        }
        
        return primary;
    }
    
    private String promoteReplicaToPrimary(String shardId) {
        List<String> replicas = replicaAddresses.get(shardId);
        
        // Find the most up-to-date replica
        String bestReplica = null;
        long bestTimestamp = 0;
        
        for (String replica : replicas) {
            if (isNodeHealthy(replica)) {
                long timestamp = getReplicaTimestamp(replica);
                if (timestamp > bestTimestamp) {
                    bestTimestamp = timestamp;
                    bestReplica = replica;
                }
            }
        }
        
        if (bestReplica != null) {
            // Promote the replica to primary
            promoteReplica(bestReplica, shardId);
            return bestReplica;
        }
        
        throw new RuntimeException("No healthy replicas available for failover");
    }
}
```

### Data Recovery

```java
// From ReplicationManager.java
public class DataRecoveryManager {
    public void recoverShardData(String shardId, String newPrimaryAddress) {
        // Get data from healthy replicas
        List<String> healthyReplicas = getHealthyReplicas(shardId);
        
        if (healthyReplicas.isEmpty()) {
            throw new RuntimeException("No healthy replicas for recovery");
        }
        
        // Copy data from the most up-to-date replica
        String sourceReplica = findMostUpToDateReplica(healthyReplicas);
        copyDataFromReplica(sourceReplica, newPrimaryAddress);
        
        // Rebuild replica set
        rebuildReplicaSet(shardId, newPrimaryAddress);
    }
    
    private String findMostUpToDateReplica(List<String> replicas) {
        return replicas.stream()
            .max(Comparator.comparingLong(this::getReplicaTimestamp))
            .orElseThrow(() -> new RuntimeException("No replicas available"));
    }
}
```

---

*This chapter explored data replication strategies for achieving stronger consistency guarantees while maintaining the performance benefits of distributed systems. In the next chapter, we'll examine the storage layer implementation using RocksDB and in-memory caching.* 