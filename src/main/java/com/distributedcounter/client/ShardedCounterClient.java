package com.distributedcounter.client;

import com.distributedcounter.model.ShardedCounterOperation;
import com.distributedcounter.model.ShardedCounterResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Client for the Sharded Counter System
 * 
 * This client interacts with the sharded counter coordinator which:
 * - Uses consistent hashing for deterministic write routing
 * - Aggregates reads from all shards
 * - Provides fault tolerance across multiple shards
 */
public class ShardedCounterClient {
    private static final Logger logger = LoggerFactory.getLogger(ShardedCounterClient.class);
    
    private final String coordinatorUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public ShardedCounterClient(String coordinatorUrl) {
        this.coordinatorUrl = coordinatorUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Increment a sharded counter
     * Uses consistent hashing to route to specific shard
     */
    public long increment(String counterId, long delta) throws IOException, InterruptedException {
        ShardedCounterOperation operation = new ShardedCounterOperation(
                counterId, 
                ShardedCounterOperation.OperationType.INCREMENT, 
                delta);
        
        ShardedCounterResponse response = sendRequest(operation);
        
        if (response.isSuccess()) {
            logger.info("Successfully incremented sharded counter {} by {}", counterId, delta);
            return response.getShardValue();
        } else {
            throw new RuntimeException("Failed to increment counter: " + response.getMessage());
        }
    }
    
    /**
     * Decrement a sharded counter
     * Uses consistent hashing to route to specific shard
     */
    public long decrement(String counterId, long delta) throws IOException, InterruptedException {
        ShardedCounterOperation operation = new ShardedCounterOperation(
                counterId, 
                ShardedCounterOperation.OperationType.DECREMENT, 
                delta);
        
        ShardedCounterResponse response = sendRequest(operation);
        
        if (response.isSuccess()) {
            logger.info("Successfully decremented sharded counter {} by {}", counterId, delta);
            return response.getShardValue();
        } else {
            throw new RuntimeException("Failed to decrement counter: " + response.getMessage());
        }
    }
    
    /**
     * Get total value of a sharded counter
     * Aggregates values from all shards
     */
    public long getTotal(String counterId) throws IOException, InterruptedException {
        ShardedCounterOperation operation = new ShardedCounterOperation(
                counterId, 
                ShardedCounterOperation.OperationType.GET_TOTAL);
        
        ShardedCounterResponse response = sendRequest(operation);
        
        if (response.isSuccess()) {
            logger.info("Retrieved total for sharded counter {}: {}", counterId, response.getTotalValue());
            return response.getTotalValue();
        } else {
            throw new RuntimeException("Failed to get total: " + response.getMessage());
        }
    }
    
    /**
     * Get values from all shards for a counter
     * Returns map of shard address to value
     */
    public Map<String, Long> getShardValues(String counterId) throws IOException, InterruptedException {
        ShardedCounterOperation operation = new ShardedCounterOperation(
                counterId, 
                ShardedCounterOperation.OperationType.GET_SHARD_VALUES);
        
        ShardedCounterResponse response = sendRequest(operation);
        
        if (response.isSuccess()) {
            logger.info("Retrieved shard values for counter {}: {} shards", counterId, response.getShardCount());
            return response.getShardValues();
        } else {
            throw new RuntimeException("Failed to get shard values: " + response.getMessage());
        }
    }
    
    /**
     * Health check for the coordinator
     */
    public void healthCheck() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(coordinatorUrl + "/health"))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            logger.info("Health check passed");
        } else {
            logger.error("Health check failed: {}", response.statusCode());
        }
    }
    
    private ShardedCounterResponse sendRequest(ShardedCounterOperation operation) 
            throws IOException, InterruptedException {
        String jsonRequest = objectMapper.writeValueAsString(operation);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(coordinatorUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), ShardedCounterResponse.class);
        } else {
            throw new RuntimeException("HTTP error: " + response.statusCode() + " - " + response.body());
        }
    }
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: ShardedCounterClient <coordinator_url>");
            System.out.println("Example: ShardedCounterClient http://localhost:8080");
            System.exit(1);
        }
        
        String coordinatorUrl = args[0];
        ShardedCounterClient client = new ShardedCounterClient(coordinatorUrl);
        
        try {
            // Health check
            client.healthCheck();
            
            String counterId = "global_likes";
            
            // Demonstrate deterministic routing with consistent hashing
            System.out.println("=== Sharded Counter Demo ===");
            System.out.println("Counter ID: " + counterId);
            System.out.println("Using consistent hashing for deterministic routing");
            
            // Increment operations (will always go to same shard due to consistent hashing)
            System.out.println("\n--- Increment Operations ---");
            long value1 = client.increment(counterId, 5);
            System.out.println("Increment 5: Shard value = " + value1);
            
            long value2 = client.increment(counterId, 3);
            System.out.println("Increment 3: Shard value = " + value2);
            
            long value3 = client.increment(counterId, 2);
            System.out.println("Increment 2: Shard value = " + value3);
            
            // Get total (aggregates from all shards)
            System.out.println("\n--- Read Operations ---");
            long total = client.getTotal(counterId);
            System.out.println("Total value: " + total);
            
            // Get shard values (for debugging)
            Map<String, Long> shardValues = client.getShardValues(counterId);
            System.out.println("Shard values: " + shardValues);
            
            // Decrement operation
            System.out.println("\n--- Decrement Operations ---");
            long value4 = client.decrement(counterId, 1);
            System.out.println("Decrement 1: Shard value = " + value4);
            
            long finalTotal = client.getTotal(counterId);
            System.out.println("Final total: " + finalTotal);
            
            System.out.println("\n=== Demo Complete ===");
            System.out.println("Note: All increments went to the same shard due to consistent hashing");
            System.out.println("Total is aggregated from all shards");
            
        } catch (Exception e) {
            logger.error("Demo failed", e);
            System.exit(1);
        }
    }
} 