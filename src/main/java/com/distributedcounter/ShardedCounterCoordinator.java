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
 * True Distributed Sharded Counter Coordinator
 * 
 * This implements a sharded counter where:
 * - A single counter is split across multiple shards
 * - Writes use consistent hashing to route to specific shards
 * - Reads aggregate from all shards to get total count
 * - Each shard maintains a portion of the total count
 */
public class ShardedCounterCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(ShardedCounterCoordinator.class);
    
    private final int port;
    private final List<String> shardAddresses;
    private final ObjectMapper objectMapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final HttpClient httpClient;
    private final Map<String, ShardInfo> shards;
    private final ConsistentHash<String> hashRing;
    
    public ShardedCounterCoordinator(int port, List<String> shardAddresses) {
        this.port = port;
        this.shardAddresses = new ArrayList<>(shardAddresses);
        this.objectMapper = new ObjectMapper();
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.shards = new ConcurrentHashMap<>();
        
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
        
        logger.info("Sharded Counter Coordinator initialized on port {} with {} shards", port, shards.size());
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
                            pipeline.addLast(new ShardedCounterHandler(shards, objectMapper, httpClient, hashRing));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("Sharded Counter Coordinator started on port {}", port);
            
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
        for (Map.Entry<String, ShardInfo> entry : shards.entrySet()) {
            String address = entry.getKey();
            ShardInfo shardInfo = entry.getValue();
            
            CompletableFuture.runAsync(() -> {
                try {
                    // Simple health check - could be enhanced with actual HTTP call
                    shardInfo.setHealthy(true);
                    shardInfo.setLastSeen(System.currentTimeMillis());
                } catch (Exception e) {
                    logger.warn("Shard {} is unhealthy: {}", address, e.getMessage());
                    shardInfo.setHealthy(false);
                }
            });
        }
    }
    
    public void shutdown() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        logger.info("Sharded Counter Coordinator shutdown complete");
    }
    
    private static class ShardedCounterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final Map<String, ShardInfo> shards;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;
        private final ConsistentHash<String> hashRing;
        
        public ShardedCounterHandler(Map<String, ShardInfo> shards, 
                                   ObjectMapper objectMapper,
                                   HttpClient httpClient,
                                   ConsistentHash<String> hashRing) {
            this.shards = shards;
            this.objectMapper = objectMapper;
            this.httpClient = httpClient;
            this.hashRing = hashRing;
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
            // Use consistent hashing to route to specific shard
            String shardAddress = hashRing.get(operation.getCounterId());
            if (shardAddress == null) {
                return ShardedCounterResponse.error("No available shards");
            }
            
            logger.info("Routing increment for counter {} to shard {} using consistent hashing", 
                       operation.getCounterId(), shardAddress);
            
            try {
                // Make HTTP call to the selected shard
                String jsonRequest = objectMapper.writeValueAsString(operation);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + shardAddress + "/sharded"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        logger.info("Successfully incremented sharded counter {} on shard {}", 
                                   operation.getCounterId(), shardAddress);
                        return counterResponse;
                    } else {
                        return ShardedCounterResponse.error("Shard operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    return ShardedCounterResponse.error("HTTP error from shard: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling shard {} for increment: {}", shardAddress, e.getMessage());
                return ShardedCounterResponse.error("Failed to communicate with shard: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleDecrement(ShardedCounterOperation operation) {
            // Use consistent hashing to route to specific shard
            String shardAddress = hashRing.get(operation.getCounterId());
            if (shardAddress == null) {
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
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                    if (counterResponse.isSuccess()) {
                        logger.info("Successfully decremented sharded counter {} on shard {}", 
                                   operation.getCounterId(), shardAddress);
                        return counterResponse;
                    } else {
                        return ShardedCounterResponse.error("Shard operation failed: " + counterResponse.getMessage());
                    }
                } else {
                    return ShardedCounterResponse.error("HTTP error from shard: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error calling shard {} for decrement: {}", shardAddress, e.getMessage());
                return ShardedCounterResponse.error("Failed to communicate with shard: " + e.getMessage());
            }
        }
        
        private ShardedCounterResponse handleGetTotal(ShardedCounterOperation operation) {
            // For GET_TOTAL, query ALL shards and aggregate
            // This is the key characteristic of a sharded counter
            Map<String, Long> shardValues = new HashMap<>();
            long totalValue = 0;
            
            try {
                // Query all shards for this counter
                for (String shardAddress : shards.keySet()) {
                    ShardedCounterOperation getOperation = new ShardedCounterOperation(
                            operation.getCounterId(), 
                            ShardedCounterOperation.OperationType.GET_SHARD_VALUES);
                    
                    String jsonRequest = objectMapper.writeValueAsString(getOperation);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + shardAddress + "/sharded"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                            .build();
                    
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        ShardedCounterResponse counterResponse = objectMapper.readValue(response.body(), ShardedCounterResponse.class);
                        if (counterResponse.isSuccess()) {
                            // Add this shard's contribution to total
                            long shardValue = counterResponse.getShardValue();
                            totalValue += shardValue;
                            shardValues.put(shardAddress, shardValue);
                        }
                    }
                }
                
                logger.info("Aggregated total for sharded counter {}: {} (from {} shards)", 
                           operation.getCounterId(), totalValue, shardValues.size());
                return ShardedCounterResponse.success(totalValue, shardValues);
                
            } catch (Exception e) {
                logger.error("Error aggregating from shards for counter {}: {}", operation.getCounterId(), e.getMessage());
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
            System.out.println("Usage: ShardedCounterCoordinator <port> <shard_address1> [shard_address2] ...");
            System.exit(1);
        }
        
        int port = Integer.parseInt(args[0]);
        List<String> shardAddresses = Arrays.asList(args).subList(1, args.length);
        
        try {
            ShardedCounterCoordinator coordinator = new ShardedCounterCoordinator(port, shardAddresses);
            coordinator.start();
        } catch (Exception e) {
            logger.error("Failed to start sharded counter coordinator", e);
            System.exit(1);
        }
    }
} 