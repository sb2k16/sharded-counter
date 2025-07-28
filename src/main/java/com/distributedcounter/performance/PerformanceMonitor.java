package com.distributedcounter.performance;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Comprehensive performance monitoring for the distributed sharded counter system.
 * 
 * This class provides real-time visibility into system behavior, enabling proactive
 * identification of bottlenecks and performance issues before they impact users.
 * 
 * Key monitoring areas:
 * - Operation latency (P50, P95, P99)
 * - Throughput metrics (operations per second)
 * - Error rates and types
 * - Resource utilization (CPU, memory, disk)
 * - Shard health and performance
 */
public class PerformanceMonitor {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> operationTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> resourceGauges = new ConcurrentHashMap<>();
    private final Map<String, ShardHealth> shardHealth = new ConcurrentHashMap<>();
    
    public PerformanceMonitor() {
        this.meterRegistry = new SimpleMeterRegistry();
        initializeMetrics();
    }
    
    private void initializeMetrics() {
        // Operation timers for different operation types
        Timer.builder("sharded_counter.operation.duration")
                .tag("operation", "increment")
                .register(meterRegistry);
        
        Timer.builder("sharded_counter.operation.duration")
                .tag("operation", "decrement")
                .register(meterRegistry);
        
        Timer.builder("sharded_counter.operation.duration")
                .tag("operation", "get_total")
                .register(meterRegistry);
        
        // Error counters by type
        Counter.builder("sharded_counter.errors")
                .tag("type", "shard_failure")
                .register(meterRegistry);
        
        Counter.builder("sharded_counter.errors")
                .tag("type", "network_timeout")
                .register(meterRegistry);
        
        Counter.builder("sharded_counter.errors")
                .tag("type", "storage_error")
                .register(meterRegistry);
        
        // Resource utilization gauges
        Gauge.builder("sharded_counter.memory.usage", this, PerformanceMonitor::getMemoryUsage)
                .register(meterRegistry);
        
        Gauge.builder("sharded_counter.cache.hit_rate", this, PerformanceMonitor::getCacheHitRate)
                .register(meterRegistry);
        
        // Throughput counters
        Counter.builder("sharded_counter.operations.total")
                .register(meterRegistry);
        
        // Shard health metrics
        Gauge.builder("sharded_counter.shards.healthy", this, PerformanceMonitor::getHealthyShardCount)
                .register(meterRegistry);
        
        Gauge.builder("sharded_counter.shards.total", this, PerformanceMonitor::getTotalShardCount)
                .register(meterRegistry);
    }
    
    /**
     * Record operation duration for performance analysis
     */
    public void recordOperation(String operation, String shardId, long durationMs) {
        String key = operation + "_" + shardId;
        Timer timer = operationTimers.computeIfAbsent(key,
                k -> Timer.builder("sharded_counter.operation.duration")
                        .tag("operation", operation)
                        .tag("shard", shardId)
                        .register(meterRegistry));
        
        timer.record(durationMs, TimeUnit.MILLISECONDS);
        
        // Increment total operations counter
        meterRegistry.counter("sharded_counter.operations.total").increment();
        
        logger.debug("Recorded {} operation on shard {}: {}ms", operation, shardId, durationMs);
    }
    
    /**
     * Record errors for monitoring and alerting
     */
    public void recordError(String errorType, String shardId) {
        String key = errorType + "_" + shardId;
        Counter counter = errorCounters.computeIfAbsent(key,
                k -> Counter.builder("sharded_counter.errors")
                        .tag("type", errorType)
                        .tag("shard", shardId)
                        .register(meterRegistry));
        
        counter.increment();
        
        logger.warn("Recorded {} error on shard {}", errorType, shardId);
    }
    
    /**
     * Update shard health metrics
     */
    public void updateShardHealth(String shardId, double latency, double errorRate) {
        ShardHealth health = shardHealth.computeIfAbsent(shardId, k -> new ShardHealth());
        health.updateMetrics(latency, errorRate);
        
        logger.debug("Updated health for shard {}: latency={}ms, errorRate={}%", 
                   shardId, latency, errorRate * 100);
    }
    
    /**
     * Get error rate for a specific shard
     */
    public double getErrorRate(String shardId) {
        return errorCounters.entrySet().stream()
                .filter(entry -> entry.getKey().contains(shardId))
                .mapToDouble(entry -> entry.getValue().count())
                .sum();
    }
    
    /**
     * Identify performance hotspots
     */
    public List<String> identifyHotspots() {
        return operationTimers.entrySet().stream()
                .filter(entry -> {
                    Timer timer = entry.getValue();
                    return timer.mean(TimeUnit.MILLISECONDS) > 100; // > 100ms
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    /**
     * Get unhealthy shards
     */
    public List<String> getUnhealthyShards() {
        return shardHealth.entrySet().stream()
                .filter(entry -> entry.getValue().isUnhealthy())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    /**
     * Get performance summary
     */
    public PerformanceSummary getPerformanceSummary() {
        double avgLatency = operationTimers.values().stream()
                .mapToDouble(timer -> timer.mean(TimeUnit.MILLISECONDS))
                .average()
                .orElse(0.0);
        
        double totalErrors = errorCounters.values().stream()
                .mapToDouble(Counter::count)
                .sum();
        
        double totalOperations = meterRegistry.counter("sharded_counter.operations.total").count();
        
        return new PerformanceSummary(
                avgLatency,
                totalErrors,
                (long) totalOperations,
                getHealthyShardCount(),
                getTotalShardCount()
        );
    }
    
    // Gauge methods for resource monitoring
    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (double) usedMemory / runtime.maxMemory() * 100;
    }
    
    private double getCacheHitRate() {
        // This would be implemented based on actual cache statistics
        return 85.0; // Placeholder - would be calculated from cache metrics
    }
    
    private int getHealthyShardCount() {
        return (int) shardHealth.values().stream()
                .filter(ShardHealth::isHealthy)
                .count();
    }
    
    private int getTotalShardCount() {
        return shardHealth.size();
    }
    
    /**
     * Shard health tracking
     */
    private static class ShardHealth {
        private final List<Double> latencyHistory = new ArrayList<>();
        private final List<Double> errorRateHistory = new ArrayList<>();
        private static final int HISTORY_SIZE = 100;
        
        public void updateMetrics(double latency, double errorRate) {
            latencyHistory.add(latency);
            errorRateHistory.add(errorRate);
            
            if (latencyHistory.size() > HISTORY_SIZE) {
                latencyHistory.remove(0);
                errorRateHistory.remove(0);
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
    
    /**
     * Performance summary data
     */
    public static class PerformanceSummary {
        private final double averageLatency;
        private final double totalErrors;
        private final long totalOperations;
        private final int healthyShards;
        private final int totalShards;
        
        public PerformanceSummary(double averageLatency, double totalErrors, 
                               long totalOperations, int healthyShards, int totalShards) {
            this.averageLatency = averageLatency;
            this.totalErrors = totalErrors;
            this.totalOperations = totalOperations;
            this.healthyShards = healthyShards;
            this.totalShards = totalShards;
        }
        
        // Getters
        public double getAverageLatency() { return averageLatency; }
        public double getTotalErrors() { return totalErrors; }
        public long getTotalOperations() { return totalOperations; }
        public int getHealthyShards() { return healthyShards; }
        public int getTotalShards() { return totalShards; }
        public double getErrorRate() { 
            return totalOperations > 0 ? totalErrors / totalOperations : 0; 
        }
        public double getHealthRate() { 
            return totalShards > 0 ? (double) healthyShards / totalShards : 0; 
        }
    }
} 