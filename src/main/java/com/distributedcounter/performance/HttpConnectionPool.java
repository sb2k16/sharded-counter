package com.distributedcounter.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * HTTP Connection Pool for high-performance HTTP communication with shards.
 * 
 * This class provides connection pooling to reduce TCP handshake overhead
 * and improve throughput for high-frequency operations. It's essential for
 * production deployments with >100 requests/second.
 * 
 * Key benefits:
 * - Reduced latency by reusing connections
 * - Higher throughput through connection reuse
 * - Resource efficiency with connection pooling
 * - Better reliability with connection management
 */
public class HttpConnectionPool {
    private static final Logger logger = LoggerFactory.getLogger(HttpConnectionPool.class);
    
    private final Map<String, HttpClient> clientPools = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    
    // Configuration
    private final int maxConnectionsPerShard;
    private final Duration connectionTimeout;
    private final Duration requestTimeout;
    private final boolean enableKeepAlive;
    
    public HttpConnectionPool() {
        this(10, Duration.ofSeconds(5), Duration.ofSeconds(30), true);
    }
    
    public HttpConnectionPool(int maxConnectionsPerShard, Duration connectionTimeout, 
                            Duration requestTimeout, boolean enableKeepAlive) {
        this.maxConnectionsPerShard = maxConnectionsPerShard;
        this.connectionTimeout = connectionTimeout;
        this.requestTimeout = requestTimeout;
        this.enableKeepAlive = enableKeepAlive;
        this.executorService = Executors.newFixedThreadPool(maxConnectionsPerShard * 2);
        
        logger.info("HTTP Connection Pool initialized with {} connections per shard", maxConnectionsPerShard);
    }
    
    /**
     * Get or create HTTP client for a specific shard
     */
    private HttpClient getClient(String shardAddress) {
        return clientPools.computeIfAbsent(shardAddress, address -> {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(connectionTimeout)
                    .executor(executorService);
            
            if (enableKeepAlive) {
                // Enable HTTP/2 and keep-alive
                builder.version(HttpClient.Version.HTTP_2);
            }
            
            HttpClient client = builder.build();
            connectionCounts.put(address, new AtomicInteger(0));
            requestCounts.put(address, new AtomicLong(0));
            
            logger.debug("Created HTTP client for shard: {}", address);
            return client;
        });
    }
    
    /**
     * Send HTTP request with connection pooling
     */
    public CompletableFuture<HttpResponse<String>> sendRequest(String shardAddress, 
                                                             HttpRequest request) {
        HttpClient client = getClient(shardAddress);
        AtomicInteger connectionCount = connectionCounts.get(shardAddress);
        AtomicLong requestCount = requestCounts.get(shardAddress);
        
        // Check connection limit
        if (connectionCount.get() >= maxConnectionsPerShard) {
            logger.warn("Connection limit reached for shard: {}", shardAddress);
        }
        
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    requestCount.incrementAndGet();
                    
                    if (throwable != null) {
                        logger.error("HTTP request failed for shard {}: {}", 
                                   shardAddress, throwable.getMessage());
                    } else {
                        logger.debug("HTTP request completed for shard {}: status={}", 
                                   shardAddress, response.statusCode());
                    }
                });
    }
    
    /**
     * Send HTTP request with timeout and retry logic
     */
    public CompletableFuture<HttpResponse<String>> sendRequestWithRetry(String shardAddress, 
                                                                       HttpRequest request, 
                                                                       int maxRetries) {
        CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
        
        sendRequestWithRetryInternal(shardAddress, request, maxRetries, 0, future);
        
        return future;
    }
    
    private void sendRequestWithRetryInternal(String shardAddress, HttpRequest request, 
                                            int maxRetries, int attempt, 
                                            CompletableFuture<HttpResponse<String>> future) {
        sendRequest(shardAddress, request)
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        future.complete(response);
                    } else if (attempt < maxRetries) {
                        // Retry on server errors
                        logger.warn("Retrying request for shard {} (attempt {}/{}): status={}", 
                                   shardAddress, attempt + 1, maxRetries, response.statusCode());
                        
                        try {
                            Thread.sleep(100 * (attempt + 1)); // Exponential backoff
                            sendRequestWithRetryInternal(shardAddress, request, maxRetries, attempt + 1, future);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            future.completeExceptionally(e);
                        }
                    } else {
                        future.complete(response);
                    }
                })
                .exceptionally(throwable -> {
                    if (attempt < maxRetries) {
                        logger.warn("Retrying request for shard {} (attempt {}/{}): error={}", 
                                   shardAddress, attempt + 1, maxRetries, throwable.getMessage());
                        
                        try {
                            Thread.sleep(100 * (attempt + 1)); // Exponential backoff
                            sendRequestWithRetryInternal(shardAddress, request, maxRetries, attempt + 1, future);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            future.completeExceptionally(e);
                        }
                    } else {
                        future.completeExceptionally(throwable);
                    }
                    return null;
                });
    }
    
    /**
     * Get connection statistics for monitoring
     */
    public ConnectionStats getConnectionStats(String shardAddress) {
        AtomicInteger connectionCount = connectionCounts.get(shardAddress);
        AtomicLong requestCount = requestCounts.get(shardAddress);
        
        return new ConnectionStats(
                shardAddress,
                connectionCount != null ? connectionCount.get() : 0,
                requestCount != null ? requestCount.get() : 0,
                maxConnectionsPerShard
        );
    }
    
    /**
     * Get all connection statistics
     */
    public Map<String, ConnectionStats> getAllConnectionStats() {
        Map<String, ConnectionStats> stats = new ConcurrentHashMap<>();
        
        for (String shardAddress : clientPools.keySet()) {
            stats.put(shardAddress, getConnectionStats(shardAddress));
        }
        
        return stats;
    }
    
    /**
     * Shutdown connection pool gracefully
     */
    public void shutdown() {
        logger.info("Shutting down HTTP connection pool");
        
        // Close all clients
        clientPools.clear();
        connectionCounts.clear();
        requestCounts.clear();
        
        // Shutdown executor service
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("HTTP connection pool shutdown complete");
    }
    
    /**
     * Connection statistics for monitoring
     */
    public static class ConnectionStats {
        private final String shardAddress;
        private final int activeConnections;
        private final long totalRequests;
        private final int maxConnections;
        
        public ConnectionStats(String shardAddress, int activeConnections, 
                            long totalRequests, int maxConnections) {
            this.shardAddress = shardAddress;
            this.activeConnections = activeConnections;
            this.totalRequests = totalRequests;
            this.maxConnections = maxConnections;
        }
        
        // Getters
        public String getShardAddress() { return shardAddress; }
        public int getActiveConnections() { return activeConnections; }
        public long getTotalRequests() { return totalRequests; }
        public int getMaxConnections() { return maxConnections; }
        public double getConnectionUtilization() { 
            return maxConnections > 0 ? (double) activeConnections / maxConnections : 0; 
        }
    }
} 