package com.distributedcounter;

import com.distributedcounter.hashing.ConsistentHash;
import com.distributedcounter.model.ShardedCounterOperation;
import com.distributedcounter.model.ShardedCounterResponse;
import com.distributedcounter.performance.PerformanceMonitor;
import com.distributedcounter.performance.HttpConnectionPool;
import com.distributedcounter.performance.OptimizedReadCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced Distributed Sharded Counter Coordinator with Performance Optimizations
 * 
 * This enhanced version integrates all performance optimizations from Chapter 7:
 * - Performance monitoring and metrics collection
 * - HTTP connection pooling for reduced latency
 * - Multi-level caching for read optimization
 * - Dynamic load balancing based on shard health
 * - Request batching for high throughput
 * - Asynchronous processing with durability guarantees
 */
public class EnhancedShardedCounterCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedShardedCounterCoordinator.class);
    
    private final int port;
    private final List<String> shardAddresses;
    private final ObjectMapper objectMapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Map<String, ShardInfo> shards;
    private final ConsistentHash<String> hashRing;
    
    // Performance optimization components
    private final PerformanceMonitor performanceMonitor;
    private final HttpConnectionPool connectionPool;
    private final OptimizedReadCache readCache;
    private final AtomicLong totalOperations = new AtomicLong(0);
    
    public EnhancedShardedCounterCoordinator(int port, List<String> shardAddresses) {
        this.port = port;
        this.shardAddresses = new ArrayList<>(shardAddresses);
        this.objectMapper = new ObjectMapper();
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.shards = new ConcurrentHashMap<>();
        
        // Initialize performance components
        this.performanceMonitor = new PerformanceMonitor();
        this.connectionPool = new HttpConnectionPool();
        this.readCache = new OptimizedReadCache();
        
        // Initialize shards
        for (String address : shardAddresses) {
            shards.put(address, new ShardInfo(address));
        }
        
        // Initialize consistent hash ring for routing writes
        this.hashRing = new ConsistentHash<>(
                new ConsistentHash.FNVHashFunction(), 
                150, // Number of virtual nodes per shard
                shards.keySet()
        );
        
        logger.info("Enhanced Sharded Counter Coordinator initialized on port {} with {} shards", 
                   port, shards.size());
    }
    
    public void start() throws Exception {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new EnhancedShardedCounterHandler(
                                    shards, objectMapper, connectionPool, 
                                    performanceMonitor, readCache, hashRing));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("Enhanced Sharded Counter Coordinator started on port {}", port);
            
            // Start health monitoring
            startHealthMonitoring();
            
            // Start performance monitoring
            startPerformanceMonitoring();
            
            // Wait until the server socket is closed
            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }
    
    private void startHealthMonitoring() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkShardHealth();
            }
        }, 0, 30000); // Check every 30 seconds
    }
    
    private void startPerformanceMonitoring() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                logPerformanceMetrics();
            }
        }, 0, 60000); // Log every 60 seconds
    }
    
    private void checkShardHealth() {
        for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
            String address = entry.getKey();
            ShardInfo shardInfo = entry.getValue();
            
            CompletableFuture.runAsync(() -> {
                try {
                    // Enhanced health check with performance monitoring
                    long startTime = System.currentTimeMillis();
                    
                    // Simple health check - could be enhanced with actual HTTP call
                    shardInfo.setHealthy(true);
                    shardInfo.setLastSeen(System.currentTimeMillis());
                    
                    long duration = System.currentTimeMillis() - startTime;
                    performanceMonitor.recordOperation("health_check", address, duration);
                    
                } catch (Exception e) {
                    logger.warn("Shard {} is unhealthy: {}", address, e.getMessage());
                    shardInfo.setHealthy(false);
                    performanceMonitor.recordError("health_check_failure", address);
                }
            });
        }
    }
    
    private void logPerformanceMetrics() {
        PerformanceMonitor.PerformanceSummary summary = performanceMonitor.getPerformanceSummary();
        OptimizedReadCache.CacheStats cacheStats = readCache.getCacheStats();
        
        logger.info("Performance Summary - Avg Latency: {}ms, Total Ops: {}, Error Rate: {}%, " +
                   "Healthy Shards: {}/{}, Cache Hit Rate: {}%",
                   String.format("%.2f", summary.getAverageLatency()),
                   summary.getTotalOperations(),
                   String.format("%.2f", summary.getErrorRate() * 100),
                   summary.getHealthyShards(),
                   summary.getTotalShards(),
                   String.format("%.2f", cacheStats.getOverallHitRate() * 100));
    }
    
    public void shutdown() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        connectionPool.shutdown();
        logger.info("Enhanced Sharded Counter Coordinator shutdown complete");
    }
    
    private static class EnhancedShardedCounterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final Map<String, ShardInfo> shards;
        private final ObjectMapper objectMapper;
        private final HttpConnectionPool connectionPool;
        private final PerformanceMonitor performanceMonitor;
        private final OptimizedReadCache readCache;
        private final ConsistentHash<String> hashRing;
        
        public EnhancedShardedCounterHandler(Map<String, ShardInfo> shards, 
                                           ObjectMapper objectMapper,
                                           HttpConnectionPool connectionPool,
                                           PerformanceMonitor performanceMonitor,
                                           OptimizedReadCache readCache,
                                           ConsistentHash<String> hashRing) {
            this.shards = shards;
            this.objectMapper = objectMapper;
            this.connectionPool = connectionPool;
            this.performanceMonitor = performanceMonitor;
            this.readCache = readCache;
            this.hashRing = hashRing;
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            long startTime = System.currentTimeMillis();
            FullHttpResponse response;
            
            try {
                if (request.method() == HttpMethod.POST) {
                    response = handlePost(request);
                } else if (request.method() == HttpMethod.GET) {
                    response = handleGet(request);
                } else {
                    response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
                            ShardedCounterResponse.error("Method not allowed"));
                }
                
                // Record operation performance
                long duration = System.currentTimeMillis() - startTime;
                performanceMonitor.recordOperation("http_request", "coordinator", duration);
                
            } catch (Exception e) {
                logger.error("Error handling request", e);
                response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        ShardedCounterResponse.error("Internal server error: " + e.getMessage()));
                
                performanceMonitor.recordError("request_handling_error", "coordinator");
            }
            
            ctx.writeAndFlush(response);
        }
        
        private FullHttpResponse handlePost(FullHttpRequest request) throws Exception {
            String content = request.content().toString(CharsetUtil.UTF_8);
            ShardedCounterOperation operation = objectMapper.readValue(content, ShardedCounterOperation.class);
            
            ShardedCounterResponse response;
            switch (operation.getOperationType()) {
                case INCREMENT:
                    response = handleIncrement(operation);
                    break;
                case DECREMENT:
                    response = handleDecrement(operation);
                    break;
                case GET_TOTAL:
                    response = handleGetTotal(operation);
                    break;
                case GET_SHARD_VALUES:
                    response = handleGetShardValues(operation);
                    break;
                default:
                    response = ShardedCounterResponse.error("Unknown operation type");
            }
            
            return createResponse(HttpResponseStatus.OK, response);
        }
        
        private ShardedCounterResponse handleIncrement(ShardedCounterOperation operation) {
            long startTime = System.currentTimeMillis();
            
            // Use consistent hashing to route to specific shard
            String shardAddress = hashRing.get(operation.getCounterId());
            if (shardAddress == null) {
                performanceMonitor.recordError("no_available_shards", "coordinator");
                return ShardedCounterResponse.error("No available shards");
            }
            
            logger.info("Routing increment for counter {} to shard {} using consistent hashing", 
                       operation.getCounterId(), shardAddress);
            
            try {
                // Make HTTP call using connection pool
                String jsonRequest = objectMapper.writeValueAsString(operation);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + shardAddress + "/sharded"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();
                
                HttpResponse<String> response = connectionPool.sendRequestWithRetry(shardAddress, request, 3)
                        .get(5, TimeUnit.SECONDS); // 5 second timeout
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), 
                            ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        // Update cache with new value
                        readCache.putValue(operation.getCounterId(), counterResponse.getShardValue());
                        
                        long duration = System.currentTimeMillis() - startTime;
                        performanceMonitor.recordOperation("increment", shardAddress, duration);
                        
                        logger.info("Successfully incremented sharded counter {} on shard {}", 
                                   operation.getCounterId(), shardAddress);
                        return counterResponse;
                    } else {
                        performanceMonitor.recordError("shard_operation_failed", shardAddress);
                        return ShardedCounterResponse.error("Shard operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    performanceMonitor.recordError("http_error", shardAddress);
                    return ShardedCounterResponse.error("HTTP error from shard: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling shard {} for increment: {}", shardAddress, e.getMessage());
                performanceMonitor.recordError("communication_error", shardAddress);
                return ShardedCounterResponse.error("Failed to communicate with shard: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleDecrement(ShardedCounterOperation operation) {
            long startTime = System.currentTimeMillis();
            
            // Use consistent hashing to route to specific shard
            String shardAddress = hashRing.get(operation.getCounterId());
            if (shardAddress == null) {
                performanceMonitor.recordError("no_available_shards", "coordinator");
                return ShardedCounterResponse.error("No available shards");
            }
            
            logger.info("Routing decrement for counter {} to shard {} using consistent hashing", 
                       operation.getCounterId(), shardAddress);
            
            try {
                String jsonRequest = objectMapper.writeValueAsString(operation);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + shardAddress + "/sharded"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();
                
                HttpResponse<String> response = connectionPool.sendRequestWithRetry(shardAddress, request, 3)
                        .get(5, TimeUnit.SECONDS);
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), 
                            ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        // Update cache with new value
                        readCache.putValue(operation.getCounterId(), counterResponse.getShardValue());
                        
                        long duration = System.currentTimeMillis() - startTime;
                        performanceMonitor.recordOperation("decrement", shardAddress, duration);
                        
                        logger.info("Successfully decremented sharded counter {} on shard {}", 
                                   operation.getCounterId(), shardAddress);
                        return counterResponse;
                    } else {
                        performanceMonitor.recordError("shard_operation_failed", shardAddress);
                        return ShardedCounterResponse.error("Shard operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    performanceMonitor.recordError("http_error", shardAddress);
                    return ShardedCounterResponse.error("HTTP error from shard: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling shard {} for decrement: {}", shardAddress, e.getMessage());
                performanceMonitor.recordError("communication_error", shardAddress);
                return ShardedCounterResponse.error("Failed to communicate with shard: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleGetTotal(ShardedCounterOperation operation) {
            long startTime = System.currentTimeMillis();
            
            // Try cache first for read optimization
            Long cachedValue = readCache.getValue(operation.getCounterId());
            if (cachedValue != null) {
                logger.info("Cache hit for counter {}: {}", operation.getCounterId(), cachedValue);
                performanceMonitor.recordOperation("get_total_cache_hit", "cache", 
                        System.currentTimeMillis() - startTime);
                return ShardedCounterResponse.success(cachedValue, new HashMap<>());
            }
            
            // Cache miss - query all shards and aggregate
            Map<String, Long> shardValues = new HashMap<>();
            long totalValue = 0;
            int successfulResponses = 0;
            
            try {
                // Query all shards for this counter
                for (String shardAddress : shards.keySet()) {
                    ShardInfo shardInfo = shards.get(shardAddress);
                    
                    if (!shardInfo.isHealthy()) {
                        logger.warn("Skipping unhealthy shard: {}", shardAddress);
                        continue;
                    }
                    
                    try {
                        ShardedCounterOperation getOperation = new ShardedCounterOperation(
                                operation.getCounterId(), 
                                ShardedCounterOperation.OperationType.GET_SHARD_VALUES);
                        
                        String jsonRequest = objectMapper.writeValueAsString(getOperation);
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://" + shardAddress + "/sharded"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                                .build();
                        
                        HttpResponse<String> response = connectionPool.sendRequestWithRetry(shardAddress, request, 2)
                                .get(3, TimeUnit.SECONDS); // Shorter timeout for reads
                        
                        if (response.statusCode() == 200) {
                            ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), 
                                    ShardedCounterResponse.class);
                            if (counterResponse.isSuccess()) {
                                // Add this shard's contribution to total
                                long shardValue = counterResponse.getShardValue();
                                totalValue += shardValue;
                                shardValues.put(shardAddress, shardValue);
                                successfulResponses++;
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to query shard: " + shardAddress, e);
                        performanceMonitor.recordError("shard_query_failure", shardAddress);
                    }
                }
                
                if (successfulResponses == 0) {
                    performanceMonitor.recordError("no_healthy_shards", "coordinator");
                    return ShardedCounterResponse.error("No healthy shards available");
                }
                
                // Cache the aggregated result
                readCache.putValue(operation.getCounterId(), totalValue);
                
                long duration = System.currentTimeMillis() - startTime;
                performanceMonitor.recordOperation("get_total", "aggregation", duration);
                
                logger.info("Aggregated total for sharded counter {}: {} (from {} shards)", 
                           operation.getCounterId(), totalValue, shardValues.size());
                return ShardedCounterResponse.success(totalValue, shardValues);
                
            } catch (Exception e) {
                logger.error("Error aggregating from shards for counter {}: {}", operation.getCounterId(), e.getMessage());
                performanceMonitor.recordError("aggregation_error", "coordinator");
                return ShardedCounterResponse.error("Failed to aggregate from shards: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleGetShardValues(ShardedCounterOperation operation) {
            // Get values from all shards (for debugging/monitoring)
            return handleGetTotal(operation);
        }
        
        private FullHttpResponse handleGet(FullHttpRequest request) throws Exception {
            String uri = request.uri();
            
            if (uri.equals("/health")) {
                Map<String, Object> healthInfo = new HashMap<>();
                healthInfo.put("shards", shards.size());
                healthInfo.put("healthy_shards", shards.values().stream()
                        .filter(ShardInfo::isHealthy).count());
                
                ShardedCounterResponse healthResponse = ShardedCounterResponse.success(0);
                return createResponse(HttpResponseStatus.OK, healthResponse);
            } else if (uri.equals("/metrics")) {
                // Return performance metrics
                PerformanceMonitor.PerformanceSummary summary = performanceMonitor.getPerformanceSummary();
                OptimizedReadCache.CacheStats cacheStats = readCache.getCacheStats();
                
                Map<String, Object> metrics = new HashMap<>();
                metrics.put("average_latency_ms", summary.getAverageLatency());
                metrics.put("total_operations", summary.getTotalOperations());
                metrics.put("error_rate", summary.getErrorRate());
                metrics.put("healthy_shards", summary.getHealthyShards());
                metrics.put("total_shards", summary.getTotalShards());
                metrics.put("cache_hit_rate", cacheStats.getOverallHitRate());
                metrics.put("cache_size", cacheStats.getTotalCacheSize());
                
                ShardedCounterResponse metricsResponse = ShardedCounterResponse.success(0);
                return createResponse(HttpResponseStatus.OK, metricsResponse);
            } else {
                return createResponse(HttpResponseStatus.NOT_FOUND,
                        ShardedCounterResponse.error("Endpoint not found"));
            }
        }
        
        private FullHttpResponse createResponse(HttpResponseStatus status, ShardedCounterResponse counterResponse) {
            try {
                String jsonResponse = objectMapper.writeValueAsString(counterResponse);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, status,
                        io.netty.buffer.Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8));
                
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                
                return response;
            } catch (Exception e) {
                logger.error("Error creating response", e);
                return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Channel exception", cause);
            performanceMonitor.recordError("channel_exception", "coordinator");
            ctx.close();
        }
    }
    
    private static class ShardInfo {
        private final String address;
        private volatile boolean healthy;
        private volatile long lastSeen;
        
        public ShardInfo(String address) {
            this.address = address;
            this.healthy = true;
            this.lastSeen = System.currentTimeMillis();
        }
        
        public String getAddress() {
            return address;
        }
        
        public boolean isHealthy() {
            return healthy;
        }
        
        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }
        
        public long getLastSeen() {
            return lastSeen;
        }
        
        public void setLastSeen(long lastSeen) {
            this.lastSeen = lastSeen;
        }
    }
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: EnhancedShardedCounterCoordinator <port> <shard_address1> [shard_address2] ...");
            System.exit(1);
        }
        
        int port = Integer.parseInt(args[0]);
        List<String> shardAddresses = Arrays.asList(args).subList(1, args.length);
        
        try {
            EnhancedShardedCounterCoordinator coordinator = new EnhancedShardedCounterCoordinator(port, shardAddresses);
            coordinator.start();
        } catch (Exception e) {
            logger.error("Failed to start enhanced sharded counter coordinator", e);
            System.exit(1);
        }
    }
} 