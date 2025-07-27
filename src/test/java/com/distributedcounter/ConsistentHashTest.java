package com.distributedcounter;

import com.distributedcounter.hashing.ConsistentHash;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

public class ConsistentHashTest {
    
    @Test
    public void testConsistentHashing() {
        List<String> shards = Arrays.asList("shard1", "shard2", "shard3");
        ConsistentHash<String> hashRing = new ConsistentHash<>(
                new ConsistentHash.FNVHashFunction(), 150, shards);
        
        // Test that we get a shard for any key
        String shard1 = hashRing.get("counter1");
        String shard2 = hashRing.get("counter2");
        String shard3 = hashRing.get("counter3");
        
        assertNotNull("Should get a shard for counter1", shard1);
        assertNotNull("Should get a shard for counter2", shard2);
        assertNotNull("Should get a shard for counter3", shard3);
        
        // Test that same key always maps to same shard
        assertEquals("Same key should map to same shard", shard1, hashRing.get("counter1"));
        assertEquals("Same key should map to same shard", shard2, hashRing.get("counter2"));
        assertEquals("Same key should map to same shard", shard3, hashRing.get("counter3"));
        
        // Test node count
        assertEquals("Should have 3 nodes", 3, hashRing.getNodeCount());
        assertFalse("Should not be empty", hashRing.isEmpty());
    }
    
    @Test
    public void testHashFunction() {
        ConsistentHash.FNVHashFunction hashFunction = new ConsistentHash.FNVHashFunction();
        
        // Test that different strings produce different hashes
        long hash1 = hashFunction.hash("test1");
        long hash2 = hashFunction.hash("test2");
        long hash3 = hashFunction.hash("test1");
        
        assertNotEquals("Different strings should have different hashes", hash1, hash2);
        assertEquals("Same string should have same hash", hash1, hash3);
        
        // Test that hash is deterministic
        assertEquals("Hash should be deterministic", hash1, hashFunction.hash("test1"));
    }
    
    @Test
    public void testEmptyHashRing() {
        ConsistentHash<String> hashRing = new ConsistentHash<>(
                new ConsistentHash.FNVHashFunction(), 150);
        
        assertTrue("Empty hash ring should be empty", hashRing.isEmpty());
        assertEquals("Empty hash ring should have 0 nodes", 0, hashRing.getNodeCount());
        assertNull("Empty hash ring should return null", hashRing.get("test"));
    }
} 