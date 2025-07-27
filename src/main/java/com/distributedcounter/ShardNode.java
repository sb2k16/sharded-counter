package com.distributedcounter;

import com.distributedcounter.model.ShardedCounterOperation;
import com.distributedcounter.model.ShardedCounterResponse;
import com.distributedcounter.storage.RocksDBStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ShardNode {
    private static final Logger logger = LoggerFactory.getLogger(ShardNode.class);
    
    private final int port;
    private final RocksDBStorage storage;
    private final ObjectMapper objectMapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    
    public ShardNode(int port, String dbPath) throws Exception {
        this.port = port;
        this.storage = new RocksDBStorage(dbPath);
        this.objectMapper = new ObjectMapper();
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        
        logger.info("Shard node initialized on port {} with storage at {}", port, dbPath);
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
                            pipeline.addLast(new ShardNodeHandler(storage, objectMapper));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("Shard node started on port {}", port);
            
            // Wait until the server socket is closed
            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }
    
    public void shutdown() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        try {
            storage.close();
        } catch (Exception e) {
            logger.error("Error closing storage", e);
        }
        logger.info("Shard node shutdown complete");
    }
    
    private static class ShardNodeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final RocksDBStorage storage;
        private final ObjectMapper objectMapper;
        
        public ShardNodeHandler(RocksDBStorage storage, ObjectMapper objectMapper) {
            this.storage = storage;
            this.objectMapper = objectMapper;
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
                    response = createShardedResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, 
                            ShardedCounterResponse.error("Method not allowed"));
                }
            } catch (Exception e) {
                logger.error("Error handling request", e);
                response = createShardedResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        ShardedCounterResponse.error("Internal server error: " + e.getMessage()));
            }
            
            ctx.writeAndFlush(response);
        }
        
        private FullHttpResponse handlePost(FullHttpRequest request) throws Exception {
            String content = request.content().toString(CharsetUtil.UTF_8);
            
            try {
                // Parse as ShardedCounterOperation
                ShardedCounterOperation operation = objectMapper.readValue(content, ShardedCounterOperation.class);
                return handleShardedOperation(operation);
            } catch (Exception e) {
                return createShardedResponse(HttpResponseStatus.BAD_REQUEST,
                        ShardedCounterResponse.error("Invalid operation format"));
            }
        }
        
        private FullHttpResponse handleGet(FullHttpRequest request) throws Exception {
            String uri = request.uri();
            
            if (uri.equals("/health")) {
                ShardedCounterResponse healthResponse = ShardedCounterResponse.shardSuccess(storage.getCacheSize());
                return createShardedResponse(HttpResponseStatus.OK, healthResponse);
            } else if (uri.equals("/sharded")) {
                // Handle sharded counter operations
                return handleShardedRequest(request);
            } else {
                return createShardedResponse(HttpResponseStatus.NOT_FOUND,
                        ShardedCounterResponse.error("Endpoint not found"));
            }
        }
        
        private FullHttpResponse handleShardedRequest(FullHttpRequest request) throws Exception {
            String content = request.content().toString(CharsetUtil.UTF_8);
            
            try {
                // Parse as ShardedCounterOperation
                ShardedCounterOperation operation = objectMapper.readValue(content, ShardedCounterOperation.class);
                return handleShardedOperation(operation);
            } catch (Exception e) {
                return createShardedResponse(HttpResponseStatus.BAD_REQUEST,
                        ShardedCounterResponse.error("Invalid operation format"));
            }
        }
        
        private FullHttpResponse handleShardedOperation(ShardedCounterOperation operation) throws Exception {
            ShardedCounterResponse response;
            
            switch (operation.getOperationType()) {
                case INCREMENT:
                    long newValue = storage.increment(operation.getCounterId(), operation.getDelta());
                    response = ShardedCounterResponse.shardSuccess(newValue);
                    break;
                case DECREMENT:
                    long decrementedValue = storage.decrement(operation.getCounterId(), operation.getDelta());
                    response = ShardedCounterResponse.shardSuccess(decrementedValue);
                    break;
                case GET_TOTAL:
                    long totalValue = storage.get(operation.getCounterId());
                    response = ShardedCounterResponse.shardSuccess(totalValue);
                    break;
                case GET_SHARD_VALUES:
                    long shardValue = storage.get(operation.getCounterId());
                    response = ShardedCounterResponse.shardSuccess(shardValue);
                    break;
                default:
                    response = ShardedCounterResponse.error("Unknown operation type");
            }
            
            return createShardedResponse(HttpResponseStatus.OK, response);
        }
        
        
        
                private FullHttpResponse createShardedResponse(HttpResponseStatus status, ShardedCounterResponse counterResponse) {
            try {
                String jsonResponse = objectMapper.writeValueAsString(counterResponse);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, status,
                        io.netty.buffer.Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8));
                
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                
                return response;
            } catch (Exception e) {
                logger.error("Error creating sharded response", e);
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
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: ShardNode <port> <db_path>");
            System.exit(1);
        }
        
        int port = Integer.parseInt(args[0]);
        String dbPath = args[1];
        
        try {
            ShardNode shardNode = new ShardNode(port, dbPath);
            shardNode.start();
        } catch (Exception e) {
            logger.error("Failed to start shard node", e);
            System.exit(1);
        }
    }
} 