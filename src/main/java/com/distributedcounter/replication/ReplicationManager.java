package com.distributedcounter.replication;

import com.distributedcounter.storage.RocksDBStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Manages data replication between primary shards and read replicas
 * 
 * Replication Strategy:
 * - Primary shards handle writes for their assigned counters
 * - Read replicas handle reads for their assigned counters
 * - Each replica only stores a subset of data (partial replication)
 * - Asynchronous replication for performance
 * - Eventual consistency model
 */
public class ReplicationManager {
    private static final Logger logger = LoggerFactory.getLogger(ReplicationManager.class);
    
    private final String shardId;
    private final boolean isPrimary;
    private final List<String> replicaAddresses;
    private final RocksDBStorage storage;
    private final ScheduledExecutorService replicationExecutor;
    private final Map<String, Long> pendingReplications;
    private final ReplicationClient replicationClient;
    
    public ReplicationManager(String shardId, boolean isPrimary, List<String> replicaAddresses, RocksDBStorage storage) {
        this.shardId = shardId;
        this.isPrimary = isPrimary;
        this.replicaAddresses = new ArrayList<>(replicaAddresses);
        this.storage = storage;
        this.replicationExecutor = Executors.newScheduledThreadPool(2);
        this.pendingReplications = new ConcurrentHashMap<>();
        this.replicationClient = new ReplicationClient();
        
        if (isPrimary) {
            startReplicationScheduler();
        }
        
        logger.info("ReplicationManager initialized for shard {} (primary: {}) with {} replicas", 
                   shardId, isPrimary, replicaAddresses.size());
    }
    
    /**
     * Handle write operation on primary shard
     * Triggers asynchronous replication to replicas
     * Note: Only replicates counters assigned to this primary shard
     */
    public void handleWrite(String counterId, long newValue) {
        if (!isPrimary) {
            logger.warn("Attempted write on replica shard {}", shardId);
            return;
        }
        
        // Store pending replication
        pendingReplications.put(counterId, newValue);
        
        // Trigger immediate replication to replicas
        replicateToReplicas(counterId, newValue);
        
        logger.debug("Write operation for counter {} with value {} queued for replication", counterId, newValue);
    }
    
    /**
     * Handle read operation on replica shard
     * Returns local value for counters assigned to this replica
     * Note: Each replica only has a subset of data (partial replication)
     */
    public long handleRead(String counterId) {
        try {
            if (isPrimary) {
                // Primary can serve reads directly for its assigned counters
                return storage.get(counterId);
            } else {
                // Replica serves reads for its assigned counters
                long value = storage.get(counterId);
                logger.debug("Read operation for counter {} returned value {} from replica {}", counterId, value, shardId);
                return value;
            }
        } catch (Exception e) {
            logger.error("Error reading counter {} from storage", counterId, e);
            return 0; // Return 0 on error
        }
    }
    
    /**
     * Handle replication update from primary
     * Called by replicas when they receive updates for their assigned counters
     */
    public void handleReplicationUpdate(String counterId, long newValue) {
        if (isPrimary) {
            logger.warn("Replication update received on primary shard {}", shardId);
            return;
        }
        
        try {
            // Update local storage for this replica's assigned counters
            long currentValue = storage.get(counterId);
            storage.increment(counterId, newValue - currentValue);
            logger.debug("Replication update for counter {} with value {} applied to replica {}", 
                       counterId, newValue, shardId);
        } catch (Exception e) {
            logger.error("Failed to apply replication update for counter {} on replica {}", counterId, shardId, e);
        }
    }
    
    /**
     * Replicate a counter update to replicas
     * Note: Only replicates to replicas that should have this counter
     */
    private void replicateToReplicas(String counterId, long newValue) {
        List<CompletableFuture<Void>> replicationFutures = new ArrayList<>();
        
        for (String replicaAddress : replicaAddresses) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    replicationClient.sendReplicationUpdate(replicaAddress, counterId, newValue);
                    logger.debug("Replication update sent to replica {} for counter {}", replicaAddress, counterId);
                } catch (Exception e) {
                    logger.error("Failed to replicate to {} for counter {}", replicaAddress, counterId, e);
                }
            }, replicationExecutor);
            
            replicationFutures.add(future);
        }
        
        // Wait for all replications to complete (with timeout)
        CompletableFuture.allOf(replicationFutures.toArray(new CompletableFuture[0]))
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    logger.warn("Some replications failed for counter {}", counterId);
                    return null;
                });
    }
    
    /**
     * Start periodic replication scheduler
     * Ensures eventual consistency even if immediate replication fails
     */
    private void startReplicationScheduler() {
        replicationExecutor.scheduleAtFixedRate(() -> {
            try {
                replicatePendingUpdates();
            } catch (Exception e) {
                logger.error("Error in replication scheduler", e);
            }
        }, 1, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Replicate all pending updates
     */
    private void replicatePendingUpdates() {
        if (pendingReplications.isEmpty()) {
            return;
        }
        
        logger.debug("Replicating {} pending updates", pendingReplications.size());
        
        for (Map.Entry<String, Long> entry : pendingReplications.entrySet()) {
            String counterId = entry.getKey();
            long newValue = entry.getValue();
            
            replicateToReplicas(counterId, newValue);
        }
        
        // Clear successfully replicated updates
        pendingReplications.clear();
    }
    
    /**
     * Get replication status
     */
    public ReplicationStatus getStatus() {
        return new ReplicationStatus(
                shardId,
                isPrimary,
                replicaAddresses.size(),
                pendingReplications.size(),
                storage.getCacheSize()
        );
    }
    
    /**
     * Shutdown replication manager
     */
    public void shutdown() {
        replicationExecutor.shutdown();
        try {
            if (!replicationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                replicationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            replicationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("ReplicationManager shutdown complete for shard {}", shardId);
    }
    
    /**
     * Replication status information
     */
    public static class ReplicationStatus {
        private final String shardId;
        private final boolean isPrimary;
        private final int replicaCount;
        private final int pendingReplications;
        private final int cacheSize;
        
        public ReplicationStatus(String shardId, boolean isPrimary, int replicaCount, 
                               int pendingReplications, int cacheSize) {
            this.shardId = shardId;
            this.isPrimary = isPrimary;
            this.replicaCount = replicaCount;
            this.pendingReplications = pendingReplications;
            this.cacheSize = cacheSize;
        }
        
        public String getShardId() { return shardId; }
        public boolean isPrimary() { return isPrimary; }
        public int getReplicaCount() { return replicaCount; }
        public int getPendingReplications() { return pendingReplications; }
        public int getCacheSize() { return cacheSize; }
        
        @Override
        public String toString() {
            return String.format("ReplicationStatus{shardId='%s', isPrimary=%s, replicaCount=%d, " +
                               "pendingReplications=%d, cacheSize=%d}", 
                               shardId, isPrimary, replicaCount, pendingReplications, cacheSize);
        }
    }
} 