package com.distributedcounter;

import com.distributedcounter.storage.RocksDBStorage;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

import java.io.File;
import java.util.Map;

public class RocksDBStorageTest {
    
    private static final String TEST_DB_PATH = "./test_data";
    private RocksDBStorage storage;
    
    @Before
    public void setUp() throws Exception {
        // Clean up test directory
        File testDir = new File(TEST_DB_PATH);
        if (testDir.exists()) {
            deleteDirectory(testDir);
        }
        
        storage = new RocksDBStorage(TEST_DB_PATH);
    }
    
    @After
    public void tearDown() throws Exception {
        if (storage != null) {
            storage.close();
        }
        
        // Clean up test directory
        File testDir = new File(TEST_DB_PATH);
        if (testDir.exists()) {
            deleteDirectory(testDir);
        }
    }
    
    @Test
    public void testIncrementAndGet() throws Exception {
        // Test basic increment
        long value1 = storage.increment("counter1", 5);
        assertEquals("First increment should return 5", 5, value1);
        
        long value2 = storage.increment("counter1", 3);
        assertEquals("Second increment should return 8", 8, value2);
        
        // Test get
        long retrieved = storage.get("counter1");
        assertEquals("Retrieved value should be 8", 8, retrieved);
    }
    
    @Test
    public void testDecrementAndGet() throws Exception {
        // Test basic decrement
        long value1 = storage.decrement("counter1", 5);
        assertEquals("First decrement should return -5", -5, value1);
        
        long value2 = storage.decrement("counter1", 3);
        assertEquals("Second decrement should return -8", -8, value2);
        
        // Test get
        long retrieved = storage.get("counter1");
        assertEquals("Retrieved value should be -8", -8, retrieved);
    }
    
    @Test
    public void testIncrementAndDecrement() throws Exception {
        // Increment first
        long value1 = storage.increment("counter1", 10);
        assertEquals("Increment should return 10", 10, value1);
        
        // Then decrement
        long value2 = storage.decrement("counter1", 3);
        assertEquals("Decrement should return 7", 7, value2);
        
        // Decrement more
        long value3 = storage.decrement("counter1", 5);
        assertEquals("Decrement should return 2", 2, value3);
        
        // Final value
        long retrieved = storage.get("counter1");
        assertEquals("Final value should be 2", 2, retrieved);
    }
    
    @Test
    public void testMultipleCounters() throws Exception {
        storage.increment("counter1", 10);
        storage.increment("counter2", 20);
        storage.increment("counter3", 30);
        
        assertEquals("counter1 should be 10", 10, storage.get("counter1"));
        assertEquals("counter2 should be 20", 20, storage.get("counter2"));
        assertEquals("counter3 should be 30", 30, storage.get("counter3"));
    }
    
    @Test
    public void testGetAllCounters() throws Exception {
        storage.increment("counter1", 10);
        storage.increment("counter2", 20);
        storage.increment("counter3", 30);
        
        Map<String, Long> allCounters = storage.getAllCounters();
        
        assertEquals("Should have 3 counters", 3, allCounters.size());
        assertEquals("counter1 should be 10", Long.valueOf(10), allCounters.get("counter1"));
        assertEquals("counter2 should be 20", Long.valueOf(20), allCounters.get("counter2"));
        assertEquals("counter3 should be 30", Long.valueOf(30), allCounters.get("counter3"));
    }
    
    @Test
    public void testGetNonExistentCounter() throws Exception {
        long value = storage.get("non_existent");
        assertEquals("Non-existent counter should return 0", 0, value);
    }
    
    @Test
    public void testCacheSize() throws Exception {
        assertEquals("Empty storage should have 0 cache entries", 0, storage.getCacheSize());
        
        storage.increment("counter1", 10);
        assertEquals("After increment should have 1 cache entry", 1, storage.getCacheSize());
        
        storage.increment("counter2", 20);
        assertEquals("After second increment should have 2 cache entries", 2, storage.getCacheSize());
    }
    
    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
} 