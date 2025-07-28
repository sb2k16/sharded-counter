# Chapter 8: Fault Tolerance and High Availability

In distributed systems, failures are not a matter of "if" but "when." A robust distributed sharded counter must continue operating even when individual components fail. This chapter explores the fault tolerance mechanisms, failure scenarios, and high availability strategies that ensure the system remains operational and consistent.

## Understanding Failure Scenarios

### Node Failures
Node failures can occur due to various reasons:
- **Hardware Failures**: CPU, memory, disk, or network interface failures
- **Software Crashes**: Application bugs, memory leaks, or unhandled exceptions
- **Resource Exhaustion**: Out of memory, disk space, or network bandwidth
- **Operating System Issues**: Kernel panics, system crashes, or service failures

### Network Partitions
Network partitions create split-brain scenarios where nodes cannot communicate:
- **Temporary Network Issues**: Brief connectivity problems between nodes
- **Permanent Network Failures**: Cable cuts, router failures, or data center isolation
- **Partial Network Failures**: Some nodes can communicate while others cannot

### Data Corruption
Data integrity issues can occur at multiple levels:
- **Storage Corruption**: Disk failures, bit rot, or filesystem corruption
- **Memory Corruption**: RAM errors or application-level data corruption
- **Network Corruption**: Data corruption during transmission

## Fault Tolerance Mechanisms

### 1. Replication and Redundancy

#### Primary-Replica Architecture
```java
public class ReplicationManager {
    private final Map<String, ShardNode> primaries;
    private final Map<String, List<ShardNode>> replicas;
    
    public void replicateWrite(String shardId, ShardedCounterOperation operation) {
        ShardNode primary = primaries.get(shardId);
        List<ShardNode> replicaNodes = replicas.get(shardId);
        
        // Write to primary first
        primary.processWrite(operation);
        
        // Replicate to all replicas
        for (ShardNode replica : replicaNodes) {
            try {
                replica.processWrite(operation);
            } catch (Exception e) {
                // Log failure but continue with other replicas
                logger.error("Failed to replicate to replica: " + replica.getId(), e);
            }
        }
    }
}
```

#### Quorum-based Consistency
```java
public class QuorumManager {
    private final int writeQuorum;
    private final int readQuorum;
    
    public boolean writeWithQuorum(String shardId, ShardedCounterOperation operation) {
        List<ShardNode> nodes = getNodesForShard(shardId);
        int successfulWrites = 0;
        
        for (ShardNode node : nodes) {
            try {
                node.processWrite(operation);
                successfulWrites++;
            } catch (Exception e) {
                logger.warn("Write failed on node: " + node.getId(), e);
            }
        }
        
        return successfulWrites >= writeQuorum;
    }
}
```

### 2. Health Monitoring and Detection

#### Health Check Implementation
```java
public class HealthMonitor {
    private final Map<String, NodeHealth> nodeHealth = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public void startHealthMonitoring() {
        scheduler.scheduleAtFixedRate(this::checkNodeHealth, 0, 30, TimeUnit.SECONDS);
    }
    
    private void checkNodeHealth() {
        for (ShardNode node : getAllNodes()) {
            try {
                HealthResponse response = node.checkHealth();
                updateNodeHealth(node.getId(), response);
            } catch (Exception e) {
                markNodeUnhealthy(node.getId(), e);
            }
        }
    }
    
    private void updateNodeHealth(String nodeId, HealthResponse response) {
        NodeHealth health = new NodeHealth(
            response.isHealthy(),
            response.getResponseTime(),
            System.currentTimeMillis()
        );
        nodeHealth.put(nodeId, health);
    }
}
```

#### Circuit Breaker Pattern
```java
public class CircuitBreaker {
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final int failureThreshold;
    private final long timeoutMs;
    
    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
    
    public <T> T execute(Supplier<T> operation) throws Exception {
        if (state.get() == CircuitState.OPEN) {
            throw new CircuitBreakerOpenException("Circuit breaker is open");
        }
        
        try {
            T result = operation.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }
    
    private void onSuccess() {
        failureCount.set(0);
        state.set(CircuitState.CLOSED);
    }
    
    private void onFailure() {
        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold) {
            state.set(CircuitState.OPEN);
            scheduleHalfOpen();
        }
    }
}
```

### 3. Automatic Recovery Mechanisms

#### Node Recovery Process
```java
public class NodeRecoveryManager {
    private final ReplicationManager replicationManager;
    private final HealthMonitor healthMonitor;
    
    public void recoverNode(String nodeId) {
        ShardNode node = getNodeById(nodeId);
        
        // Step 1: Check if node is actually down
        if (healthMonitor.isNodeHealthy(nodeId)) {
            return; // Node is already healthy
        }
        
        // Step 2: Attempt to restart the node
        try {
            node.restart();
        } catch (Exception e) {
            logger.error("Failed to restart node: " + nodeId, e);
            return;
        }
        
        // Step 3: Wait for node to become healthy
        waitForNodeHealth(nodeId, Duration.ofMinutes(2));
        
        // Step 4: Resynchronize data from replicas
        resynchronizeNodeData(nodeId);
    }
    
    private void resynchronizeNodeData(String nodeId) {
        ShardNode node = getNodeById(nodeId);
        List<String> shardIds = node.getAssignedShards();
        
        for (String shardId : shardIds) {
            List<ShardNode> healthyReplicas = getHealthyReplicas(shardId);
            if (!healthyReplicas.isEmpty()) {
                ShardNode source = healthyReplicas.get(0);
                copyShardData(source, node, shardId);
            }
        }
    }
}
```

#### Data Recovery from Backups
```java
public class BackupRecoveryManager {
    private final StorageManager storageManager;
    private final BackupService backupService;
    
    public void recoverFromBackup(String nodeId, String backupId) {
        // Step 1: Download backup data
        BackupMetadata metadata = backupService.getBackupMetadata(backupId);
        Path backupPath = backupService.downloadBackup(backupId);
        
        // Step 2: Stop the node
        ShardNode node = getNodeById(nodeId);
        node.stop();
        
        // Step 3: Restore data from backup
        try {
            storageManager.restoreFromBackup(node.getStoragePath(), backupPath);
        } finally {
            // Clean up downloaded backup
            Files.deleteIfExists(backupPath);
        }
        
        // Step 4: Restart the node
        node.start();
        
        // Step 5: Verify data integrity
        verifyDataIntegrity(nodeId, metadata);
    }
    
    private void verifyDataIntegrity(String nodeId, BackupMetadata metadata) {
        ShardNode node = getNodeById(nodeId);
        for (String shardId : node.getAssignedShards()) {
            long expectedCount = metadata.getShardCount(shardId);
            long actualCount = node.getShardCount(shardId);
            
            if (expectedCount != actualCount) {
                logger.error("Data integrity check failed for shard: " + shardId);
                // Trigger additional recovery procedures
            }
        }
    }
}
```

## Graceful Degradation Strategies

### 1. Partial Failure Handling

#### Degraded Mode Operations
```java
public class DegradedModeManager {
    private final Set<String> unhealthyNodes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean degradedMode = new AtomicBoolean(false);
    
    public ShardedCounterResponse handleRequest(ShardedCounterRequest request) {
        if (degradedMode.get()) {
            return handleRequestInDegradedMode(request);
        }
        
        try {
            return handleRequestNormally(request);
        } catch (InsufficientNodesException e) {
            degradedMode.set(true);
            return handleRequestInDegradedMode(request);
        }
    }
    
    private ShardedCounterResponse handleRequestInDegradedMode(ShardedCounterRequest request) {
        switch (request.getOperationType()) {
            case READ:
                return handleReadInDegradedMode(request);
            case WRITE:
                return handleWriteInDegradedMode(request);
            default:
                throw new UnsupportedOperationException("Operation not supported in degraded mode");
        }
    }
    
    private ShardedCounterResponse handleReadInDegradedMode(ShardedCounterRequest request) {
        // In degraded mode, read from available nodes only
        List<ShardNode> availableNodes = getAvailableNodes();
        if (availableNodes.isEmpty()) {
            throw new ServiceUnavailableException("No healthy nodes available");
        }
        
        // Aggregate from available nodes, even if incomplete
        long totalCount = 0;
        for (ShardNode node : availableNodes) {
            try {
                totalCount += node.getShardCount(request.getShardId());
            } catch (Exception e) {
                logger.warn("Failed to read from node: " + node.getId(), e);
            }
        }
        
        return ShardedCounterResponse.builder()
            .count(totalCount)
            .isPartial(true)
            .availableNodes(availableNodes.size())
            .totalNodes(getTotalNodes())
            .build();
    }
}
```

### 2. Read-Only Mode

#### Automatic Read-Only Transition
```java
public class ReadOnlyModeManager {
    private final AtomicBoolean readOnlyMode = new AtomicBoolean(false);
    private final HealthMonitor healthMonitor;
    
    public void checkSystemHealth() {
        int healthyNodes = healthMonitor.getHealthyNodeCount();
        int totalNodes = healthMonitor.getTotalNodeCount();
        
        // Switch to read-only if less than 50% of nodes are healthy
        if (healthyNodes < totalNodes / 2) {
            readOnlyMode.set(true);
            logger.warn("Switching to read-only mode due to insufficient healthy nodes");
        } else if (readOnlyMode.get()) {
            // Switch back to normal mode if enough nodes are healthy
            readOnlyMode.set(false);
            logger.info("Switching back to normal mode");
        }
    }
    
    public ShardedCounterResponse processRequest(ShardedCounterRequest request) {
        if (readOnlyMode.get() && request.getOperationType() == OperationType.WRITE) {
            throw new ReadOnlyModeException("System is in read-only mode");
        }
        
        return processRequestNormally(request);
    }
}
```

## High Availability Strategies

### 1. Multi-Datacenter Deployment

#### Cross-Datacenter Replication
```java
public class CrossDatacenterReplication {
    private final Map<String, DatacenterInfo> datacenters;
    private final ReplicationManager replicationManager;
    
    public void replicateAcrossDatacenters(String shardId, ShardedCounterOperation operation) {
        String primaryDatacenter = getPrimaryDatacenter(shardId);
        
        // Write to primary datacenter first
        replicateToDatacenter(primaryDatacenter, shardId, operation);
        
        // Asynchronously replicate to other datacenters
        for (String datacenterId : datacenters.keySet()) {
            if (!datacenterId.equals(primaryDatacenter)) {
                CompletableFuture.runAsync(() -> {
                    try {
                        replicateToDatacenter(datacenterId, shardId, operation);
                    } catch (Exception e) {
                        logger.error("Failed to replicate to datacenter: " + datacenterId, e);
                    }
                });
            }
        }
    }
    
    private void replicateToDatacenter(String datacenterId, String shardId, 
                                     ShardedCounterOperation operation) {
        DatacenterInfo dc = datacenters.get(datacenterId);
        List<ShardNode> nodes = dc.getNodesForShard(shardId);
        
        for (ShardNode node : nodes) {
            try {
                node.processWrite(operation);
            } catch (Exception e) {
                logger.warn("Failed to replicate to node in datacenter: " + datacenterId, e);
            }
        }
    }
}
```

### 2. Automatic Failover

#### Failover Manager
```java
public class FailoverManager {
    private final Map<String, String> primaryToBackup = new ConcurrentHashMap<>();
    private final HealthMonitor healthMonitor;
    
    public void checkAndFailover() {
        for (Map.Entry<String, String> entry : primaryToBackup.entrySet()) {
            String primaryId = entry.getKey();
            String backupId = entry.getValue();
            
            if (!healthMonitor.isNodeHealthy(primaryId)) {
                performFailover(primaryId, backupId);
            }
        }
    }
    
    private void performFailover(String primaryId, String backupId) {
        logger.info("Initiating failover from {} to {}", primaryId, backupId);
        
        // Step 1: Promote backup to primary
        ShardNode backup = getNodeById(backupId);
        backup.promoteToPrimary();
        
        // Step 2: Update routing tables
        updateRoutingTables(primaryId, backupId);
        
        // Step 3: Notify clients of the change
        notifyClientsOfFailover(primaryId, backupId);
        
        // Step 4: Start monitoring the new primary
        startMonitoringNewPrimary(backupId);
        
        logger.info("Failover completed successfully");
    }
}
```

## Disaster Recovery Planning

### 1. Backup Strategies

#### Automated Backup System
```java
public class AutomatedBackupSystem {
    private final BackupService backupService;
    private final ScheduledExecutorService scheduler;
    
    public void scheduleBackups() {
        // Full backup every 24 hours
        scheduler.scheduleAtFixedRate(this::performFullBackup, 0, 24, TimeUnit.HOURS);
        
        // Incremental backup every 6 hours
        scheduler.scheduleAtFixedRate(this::performIncrementalBackup, 0, 6, TimeUnit.HOURS);
    }
    
    private void performFullBackup() {
        try {
            String backupId = backupService.createFullBackup();
            logger.info("Full backup completed: " + backupId);
            
            // Clean up old backups (keep last 7 days)
            backupService.cleanupOldBackups(Duration.ofDays(7));
        } catch (Exception e) {
            logger.error("Full backup failed", e);
        }
    }
    
    private void performIncrementalBackup() {
        try {
            String backupId = backupService.createIncrementalBackup();
            logger.info("Incremental backup completed: " + backupId);
        } catch (Exception e) {
            logger.error("Incremental backup failed", e);
        }
    }
}
```

### 2. Recovery Procedures

#### Disaster Recovery Plan
```java
public class DisasterRecoveryPlan {
    private final BackupService backupService;
    private final NodeManager nodeManager;
    
    public void executeDisasterRecovery(String disasterType) {
        switch (disasterType) {
            case "DATACENTER_OUTAGE":
                handleDatacenterOutage();
                break;
            case "NETWORK_PARTITION":
                handleNetworkPartition();
                break;
            case "DATA_CORRUPTION":
                handleDataCorruption();
                break;
            default:
                throw new IllegalArgumentException("Unknown disaster type: " + disasterType);
        }
    }
    
    private void handleDatacenterOutage() {
        // Step 1: Identify affected nodes
        List<String> affectedNodes = identifyAffectedNodes();
        
        // Step 2: Promote replicas in other datacenters
        for (String nodeId : affectedNodes) {
            String backupNode = findBackupNode(nodeId);
            if (backupNode != null) {
                performFailover(nodeId, backupNode);
            }
        }
        
        // Step 3: Update client routing
        updateClientRouting();
        
        // Step 4: Monitor system health
        startHealthMonitoring();
    }
    
    private void handleDataCorruption() {
        // Step 1: Identify corrupted data
        List<DataCorruptionReport> corruptions = detectDataCorruption();
        
        // Step 2: Restore from backups
        for (DataCorruptionReport corruption : corruptions) {
            String backupId = findLatestGoodBackup(corruption.getShardId());
            restoreFromBackup(corruption.getNodeId(), backupId);
        }
        
        // Step 3: Verify data integrity
        verifyDataIntegrity();
    }
}
```

## Monitoring and Alerting

### 1. Comprehensive Monitoring

#### System Metrics Collection
```java
public class SystemMetricsCollector {
    private final MetricRegistry metricRegistry;
    private final Map<String, Gauge> nodeHealthGauges = new ConcurrentHashMap<>();
    
    public void collectSystemMetrics() {
        // Node health metrics
        for (ShardNode node : getAllNodes()) {
            String nodeId = node.getId();
            Gauge<Integer> healthGauge = () -> node.isHealthy() ? 1 : 0;
            nodeHealthGauges.put(nodeId, healthGauge);
            metricRegistry.register("node.health." + nodeId, healthGauge);
        }
        
        // Performance metrics
        Timer requestTimer = metricRegistry.timer("request.latency");
        Counter errorCounter = metricRegistry.counter("request.errors");
        Counter successCounter = metricRegistry.counter("request.success");
        
        // Storage metrics
        Gauge<Long> storageUsage = () -> getTotalStorageUsage();
        metricRegistry.register("storage.usage", storageUsage);
        
        // Network metrics
        Gauge<Integer> activeConnections = () -> getActiveConnections();
        metricRegistry.register("network.active_connections", activeConnections);
    }
}
```

### 2. Alerting System

#### Alert Manager
```java
public class AlertManager {
    private final Map<String, AlertRule> alertRules = new ConcurrentHashMap<>();
    private final NotificationService notificationService;
    
    public void checkAlerts() {
        for (AlertRule rule : alertRules.values()) {
            if (rule.evaluate()) {
                sendAlert(rule);
            }
        }
    }
    
    public void addAlertRule(String name, AlertRule rule) {
        alertRules.put(name, rule);
    }
    
    private void sendAlert(AlertRule rule) {
        Alert alert = Alert.builder()
            .severity(rule.getSeverity())
            .message(rule.getMessage())
            .timestamp(System.currentTimeMillis())
            .build();
        
        notificationService.sendAlert(alert);
    }
    
    // Example alert rules
    public void setupDefaultAlertRules() {
        // High error rate
        addAlertRule("high_error_rate", new AlertRule(
            () -> getErrorRate() > 0.05,
            "Error rate exceeds 5%",
            AlertSeverity.HIGH
        ));
        
        // Node down
        addAlertRule("node_down", new AlertRule(
            () -> getHealthyNodeCount() < getTotalNodeCount(),
            "One or more nodes are down",
            AlertSeverity.CRITICAL
        ));
        
        // High latency
        addAlertRule("high_latency", new AlertRule(
            () -> getAverageLatency() > 1000,
            "Average latency exceeds 1 second",
            AlertSeverity.MEDIUM
        ));
    }
}
```

## Testing Fault Tolerance

### 1. Chaos Engineering

#### Chaos Monkey Implementation
```java
public class ChaosMonkey {
    private final Random random = new Random();
    private final NodeManager nodeManager;
    private final ScheduledExecutorService scheduler;
    
    public void startChaosTesting() {
        // Randomly kill nodes
        scheduler.scheduleAtFixedRate(this::randomlyKillNode, 0, 5, TimeUnit.MINUTES);
        
        // Randomly partition network
        scheduler.scheduleAtFixedRate(this::randomlyPartitionNetwork, 0, 10, TimeUnit.MINUTES);
        
        // Randomly corrupt data
        scheduler.scheduleAtFixedRate(this::randomlyCorruptData, 0, 30, TimeUnit.MINUTES);
    }
    
    private void randomlyKillNode() {
        if (random.nextDouble() < 0.1) { // 10% chance
            List<ShardNode> nodes = getAllNodes();
            if (!nodes.isEmpty()) {
                ShardNode randomNode = nodes.get(random.nextInt(nodes.size()));
                logger.info("Chaos Monkey: Killing node " + randomNode.getId());
                randomNode.stop();
                
                // Restart after 2 minutes
                scheduler.schedule(() -> {
                    try {
                        randomNode.start();
                        logger.info("Chaos Monkey: Restarted node " + randomNode.getId());
                    } catch (Exception e) {
                        logger.error("Chaos Monkey: Failed to restart node " + randomNode.getId(), e);
                    }
                }, 2, TimeUnit.MINUTES);
            }
        }
    }
    
    private void randomlyPartitionNetwork() {
        if (random.nextDouble() < 0.05) { // 5% chance
            logger.info("Chaos Monkey: Creating network partition");
            createNetworkPartition();
            
            // Remove partition after 1 minute
            scheduler.schedule(this::removeNetworkPartition, 1, TimeUnit.MINUTES);
        }
    }
}
```

### 2. Failure Injection Testing

#### Failure Injection Framework
```java
public class FailureInjectionFramework {
    private final Map<String, FailureInjector> injectors = new ConcurrentHashMap<>();
    
    public void injectFailure(String nodeId, FailureType type) {
        FailureInjector injector = injectors.get(nodeId);
        if (injector != null) {
            injector.injectFailure(type);
        }
    }
    
    public void setupFailureInjectors() {
        for (ShardNode node : getAllNodes()) {
            FailureInjector injector = new FailureInjector(node);
            injectors.put(node.getId(), injector);
        }
    }
    
    public enum FailureType {
        NODE_CRASH,
        NETWORK_PARTITION,
        HIGH_LATENCY,
        DATA_CORRUPTION,
        MEMORY_LEAK
    }
}
```

## Best Practices for Fault Tolerance

### 1. Design Principles

- **Fail Fast**: Detect failures quickly and fail fast to prevent cascading failures
- **Graceful Degradation**: Continue operating with reduced functionality when possible
- **Redundancy**: Always have multiple copies of critical data and components
- **Monitoring**: Comprehensive monitoring and alerting for all components
- **Testing**: Regular testing of failure scenarios and recovery procedures

### 2. Operational Guidelines

- **Automated Recovery**: Automate recovery procedures where possible
- **Manual Override**: Provide manual override capabilities for critical situations
- **Documentation**: Maintain detailed documentation of all procedures
- **Training**: Regular training for operations team on failure scenarios
- **Post-Mortem**: Conduct post-mortem analysis after every failure

### 3. Performance Considerations

- **Overhead**: Fault tolerance mechanisms should have minimal performance impact
- **Resource Usage**: Monitor resource usage of fault tolerance components
- **Scalability**: Ensure fault tolerance scales with the system
- **Latency**: Minimize latency impact of health checks and monitoring

## Summary

Fault tolerance and high availability are critical aspects of distributed sharded counters. By implementing comprehensive monitoring, automatic recovery mechanisms, graceful degradation strategies, and thorough testing, we can ensure the system remains operational even in the face of various failure scenarios.

Key takeaways from this chapter:
- **Multiple Failure Types**: Hardware, software, network, and data corruption failures
- **Replication Strategies**: Primary-replica and quorum-based approaches
- **Health Monitoring**: Continuous monitoring with circuit breakers and health checks
- **Automatic Recovery**: Node recovery, data restoration, and failover mechanisms
- **Graceful Degradation**: Partial failure handling and read-only mode
- **Disaster Recovery**: Backup strategies and comprehensive recovery procedures
- **Testing**: Chaos engineering and failure injection testing
- **Best Practices**: Design principles and operational guidelines

In the next chapter, we'll explore deployment strategies and operational best practices for maintaining a distributed sharded counter system in production.

---

*This chapter explored fault tolerance and high availability mechanisms in distributed sharded counters. In the next chapter, we'll discuss deployment and operational best practices.* 