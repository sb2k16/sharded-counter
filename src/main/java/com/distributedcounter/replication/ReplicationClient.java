package com.distributedcounter.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for sending replication updates between shards
 */
public class ReplicationClient {
    private static final Logger logger = LoggerFactory.getLogger(ReplicationClient.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public ReplicationClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Send replication update to a replica
     */
    public void sendReplicationUpdate(String replicaAddress, String counterId, long newValue) 
            throws IOException, InterruptedException {
        
        ReplicationUpdate update = new ReplicationUpdate(counterId, newValue);
        String jsonRequest = objectMapper.writeValueAsString(update);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + replicaAddress + "/replication"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Replication failed with status: " + response.statusCode());
        }
        
        logger.debug("Replication update sent to {} for counter {} with value {}", 
                   replicaAddress, counterId, newValue);
    }
    
    /**
     * Data model for replication updates
     */
    public static class ReplicationUpdate {
        private final String counterId;
        private final long newValue;
        
        public ReplicationUpdate(String counterId, long newValue) {
            this.counterId = counterId;
            this.newValue = newValue;
        }
        
        public String getCounterId() { return counterId; }
        public long getNewValue() { return newValue; }
        
        @Override
        public String toString() {
            return String.format("ReplicationUpdate{counterId='%s', newValue=%d}", counterId, newValue);
        }
    }
} 