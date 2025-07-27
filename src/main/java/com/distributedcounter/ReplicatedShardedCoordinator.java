package com.distributedcounter;

import com.distributedcounter.hashing.ConsistentHash;
import com.distributedcounter.model.ShardedCounterOperation;
import com.distributedcounter.model.ShardedCounterResponse;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced Sharded Counter Coordinator with Read Replicas
 * 
 * This coordinator routes:
 * - Writes to primary shards (using consistent hashing)
 * - Reads to read replicas (for better performance)
 * - Provides fault tolerance and load balancing
 */
public class ReplicatedShardedCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(ReplicatedShardedCoordinator.class);
    
    private final int port;
    private final List<String> primaryShardAddresses;
    private final List<String> replicaAddresses;
    private final ObjectMapper objectMapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final HttpClient httpClient;
    private final Map<String, ShardInfo> primaryShards;
    private final Map<String, ShardInfo> replicas;
    private final ConsistentHash<String> primaryHashRing;
    private final ConsistentHash<String> replicaHashRing;
    
    public ReplicatedShardedCoordinator(int port, List<String> primaryShardAddresses, List<String> replicaAddresses) {
        this.port = port;
        this.primaryShardAddresses = new ArrayList<>(primaryShardAddresses);
        this.replicaAddresses = new ArrayList<>(replicaAddresses);
        this.objectMapper = new ObjectMapper();
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.primaryShards = new ConcurrentHashMap<>();
        this.replicas = new ConcurrentHashMap<>();
        
        // Initialize primary shards
        for (String address : primaryShardAddresses) {
            primaryShards.put(address, new ShardInfo(address, true));
        }
        
        // Initialize replicas
        for (String address : replicaAddresses) {
            replicas.put(address, new ShardInfo(address, false));
        }
        
        // Initialize consistent hash rings
        this.primaryHashRing = new ConsistentHash<>(
                new ConsistentHash.FNVHashFunction(), 
                150, // Number of virtual nodes per shard
                primaryShards.keySet()
        );
        
        this.replicaHashRing = new ConsistentHash<>(
                new ConsistentHash.FNVHashFunction(), 
                150, // Number of virtual nodes per replica
                replicas.keySet()
        );
        
        logger.info("Replicated Sharded Coordinator initialized on port {} with {} primary shards and {} replicas", 
                   port, primaryShards.size(), replicas.size());
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
                            pipeline.addLast(new ReplicatedShardedHandler(primaryShards, replicas, 
                                                                        objectMapper, httpClient, 
                                                                        primaryHashRing, replicaHashRing));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("Replicated Sharded Coordinator started on port {}", port);
            
            // Start health monitoring
            startHealthMonitoring();
            
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
    
    private void checkShardHealth() {
        // Check primary shards
        for (Map.Entry<String, ShardInfo> entry : primaryShards.entrySet()) {
            String address = entry.getKey();
            ShardInfo shardInfo = entry.getValue();
            
            CompletableFuture.runAsync(() -> {
                try {
                    // Simple health check - could be enhanced with actual HTTP call
                    shardInfo.setHealthy(true);
                    shardInfo.setLastSeen(System.currentTimeMillis());
                } catch (Exception e) {
                    logger.warn("Primary shard {} is unhealthy: {}", address, e.getMessage());
                    shardInfo.setHealthy(false);
                }
            });
        }
        
        // Check replicas
        for (Map.Entry<String, ShardInfo> entry : replicas.entrySet()) {
            String address = entry.getKey();
            ShardInfo replicaInfo = entry.getValue();
            
            CompletableFuture.runAsync(() -> {
                try {
                    // Simple health check - could be enhanced with actual HTTP call
                    replicaInfo.setHealthy(true);
                    replicaInfo.setLastSeen(System.currentTimeMillis());
                } catch (Exception e) {
                    logger.warn("Replica {} is unhealthy: {}", address, e.getMessage());
                    replicaInfo.setHealthy(false);
                }
            });
        }
    }
    
    public void shutdown() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        logger.info("Replicated Sharded Coordinator shutdown complete");
    }
    
    private static class ReplicatedShardedHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final Map<String, ShardInfo> primaryShards;
        private final Map<String, ShardInfo> replicas;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;
        private final ConsistentHash<String> primaryHashRing;
        private final ConsistentHash<String> replicaHashRing;
        
        public ReplicatedShardedHandler(Map<String, ShardInfo> primaryShards,
                                      Map<String, ShardInfo> replicas,
                                      ObjectMapper objectMapper,
                                      HttpClient httpClient,
                                      ConsistentHash<String> primaryHashRing,
                                      ConsistentHash<String> replicaHashRing) {
            this.primaryShards = primaryShards;
            this.replicas = replicas;
            this.objectMapper = objectMapper;
            this.httpClient = httpClient;
            this.primaryHashRing = primaryHashRing;
            this.replicaHashRing = replicaHashRing;
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
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
            } catch (Exception e) {
                logger.error("Error handling request", e);
                response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        ShardedCounterResponse.error("Internal server error: " + e.getMessage()));
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
            // Route to primary shard using consistent hashing
            String primaryShardAddress = primaryHashRing.get(operation.getCounterId());
            if (primaryShardAddress == null) {
                return ShardedCounterResponse.error("No available primary shards");
            }
            
            logger.info("Routing increment for counter {} to primary shard {} using consistent hashing", 
                       operation.getCounterId(), primaryShardAddress);
            
            try {
                // Make HTTP call to the selected primary shard
                String jsonRequest = objectMapper.writeValueAsString(operation);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + primaryShardAddress + "/sharded"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        logger.info("Successfully incremented counter {} on primary shard {}", 
                                   operation.getCounterId(), primaryShardAddress);
                        return counterResponse;
                    } else {
                        return ShardedCounterResponse.error("Primary shard operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    return ShardedCounterResponse.error("HTTP error from primary shard: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling primary shard {} for increment: {}", primaryShardAddress, e.getMessage());
                return ShardedCounterResponse.error("Failed to communicate with primary shard: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleDecrement(ShardedCounterOperation operation) {
            // Route to primary shard using consistent hashing
            String primaryShardAddress = primaryHashRing.get(operation.getCounterId());
            if (primaryShardAddress == null) {
                return ShardedCounterResponse.error("No available primary shards");
            }
            
            logger.info("Routing decrement for counter {} to primary shard {} using consistent hashing", 
                       operation.getCounterId(), primaryShardAddress);
            
            try {
                String jsonRequest = objectMapper.writeValueAsString(operation);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + primaryShardAddress + "/sharded"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        logger.info("Successfully decremented counter {} on primary shard {}", 
                                   operation.getCounterId(), primaryShardAddress);
                        return counterResponse;
                    } else {
                        return ShardedCounterResponse.error("Primary shard operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    return ShardedCounterResponse.error("HTTP error from primary shard: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling primary shard {} for decrement: {}", primaryShardAddress, e.getMessage());
                return ShardedCounterResponse.error("Failed to communicate with primary shard: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleGetTotal(ShardedCounterOperation operation) {
            // Route to replica using consistent hashing for better read performance
            String replicaAddress = replicaHashRing.get(operation.getCounterId());
            if (replicaAddress == null) {
                return ShardedCounterResponse.error("No available replicas");
            }
            
            logger.info("Routing get total for counter {} to replica {} for better read performance", 
                       operation.getCounterId(), replicaAddress);
            
            try {
                ShardedCounterOperation getOperation = new ShardedCounterOperation(
                        operation.getCounterId(), 
                        ShardedCounterOperation.OperationType.GET_TOTAL);
                
                String jsonRequest = objectMapper.writeValueAsString(getOperation);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + replicaAddress + "/sharded"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        logger.info("Successfully retrieved total for counter {} from replica {}", 
                                   operation.getCounterId(), replicaAddress);
                        return counterResponse;
                    } else {
                        return ShardedCounterResponse.error("Replica operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    return ShardedCounterResponse.error("HTTP error from replica: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling replica {} for get total: {}", replicaAddress, e.getMessage());
                return ShardedCounterResponse.error("Failed to communicate with replica: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleGetShardValues(ShardedCounterOperation operation) {
            // For detailed shard values, query all replicas
            Map<String, Long> shardValues = new HashMap<>();
            long totalValue = 0;
            
            try {
                // Query all replicas for this counter
                for (String replicaAddress : replicas.keySet()) {
                    ShardedCounterOperation getOperation = new ShardedCounterOperation(
                            operation.getCounterId(), 
                            ShardedCounterOperation.OperationType.GET_SHARD_VALUES);
                    
                    String jsonRequest = objectMapper.writeValueAsString(getOperation);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + replicaAddress + "/sharded"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                            .build();
                    
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                        if (counterResponse.isSuccess()) {
                            // Add this replica's contribution to total
                            long replicaValue = counterResponse.getShardValue();
                            totalValue += replicaValue;
                            shardValues.put(replicaAddress, replicaValue);
                        }
                    }
                }
                
                logger.info("Aggregated total for counter {} from replicas: {} (from {} replicas)", 
                           operation.getCounterId(), totalValue, shardValues.size());
                return ShardedCounterResponse.success(totalValue, shardValues);
                
            } catch (Exception e) {
                logger.error("Error aggregating from replicas for counter {}: {}", operation.getCounterId(), e.getMessage());
                return ShardedCounterResponse.error("Failed to aggregate from replicas: " + e.getMessage());
            }
        }
        
        private FullHttpResponse handleGet(FullHttpRequest request) throws Exception {
            String uri = request.uri();
            
            if (uri.equals("/health")) {
                Map<String, Object> healthInfo = new HashMap<>();
                healthInfo.put("primary_shards", primaryShards.size());
                healthInfo.put("replicas", replicas.size());
                healthInfo.put("healthy_primary_shards", primaryShards.values().stream()
                        .filter(ShardInfo::isHealthy).count());
                healthInfo.put("healthy_replicas", replicas.values().stream()
                        .filter(ShardInfo::isHealthy).count());
                
                ShardedCounterResponse healthResponse = ShardedCounterResponse.success(0);
                return createResponse(HttpResponseStatus.OK, healthResponse);
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
            ctx.close();
        }
    }
    
    private static class ShardInfo {
        private final String address;
        private final boolean isPrimary;
        private volatile boolean healthy;
        private volatile long lastSeen;
        
        public ShardInfo(String address, boolean isPrimary) {
            this.address = address;
            this.isPrimary = isPrimary;
            this.healthy = true;
            this.lastSeen = System.currentTimeMillis();
        }
        
        public String getAddress() {
            return address;
        }
        
        public boolean isPrimary() {
            return isPrimary;
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
        if (args.length < 3) {
            System.out.println("Usage: ReplicatedShardedCoordinator <port> <primary_shard1> [primary_shard2] ... <replica1> [replica2] ...");
            System.out.println("Example: ReplicatedShardedCoordinator 8080 localhost:8081 localhost:8082 localhost:8083 localhost:8084 localhost:8085");
            System.exit(1);
        }
        
        int port = Integer.parseInt(args[0]);
        
        // Parse primary shards and replicas
        List<String> primaryShards = new ArrayList<>();
        List<String> replicas = new ArrayList<>();
        
        boolean parsingPrimaries = true;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--replicas")) {
                parsingPrimaries = false;
            } else if (parsingPrimaries) {
                primaryShards.add(args[i]);
            } else {
                replicas.add(args[i]);
            }
        }
        
        // If no --replicas flag, assume first half are primaries, second half are replicas
        if (replicas.isEmpty() && primaryShards.size() > 1) {
            int mid = primaryShards.size() / 2;
            replicas = new ArrayList<>(primaryShards.subList(mid, primaryShards.size()));
            primaryShards = new ArrayList<>(primaryShards.subList(0, mid));
        }
        
        try {
            ReplicatedShardedCoordinator coordinator = new ReplicatedShardedCoordinator(port, primaryShards, replicas);
            coordinator.start();
        } catch (Exception e) {
            logger.error("Failed to start replicated sharded coordinator", e);
            System.exit(1);
        }
    }
} 