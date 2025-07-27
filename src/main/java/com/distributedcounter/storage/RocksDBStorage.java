package com.distributedcounter.storage;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RocksDBStorage implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RocksDBStorage.class);
    
    private final RocksDB db;
    private final Map<String, Long> inMemoryCache;
    private final String dbPath;
    
    public RocksDBStorage(String dbPath) throws RocksDBException {
        this.dbPath = dbPath;
        this.inMemoryCache = new ConcurrentHashMap<>();
        
        // Create directory if it doesn't exist
        File directory = new File(dbPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        
        // Configure RocksDB options
        Options options = new Options();
        options.setCreateIfMissing(true);
        options.setMaxBackgroundJobs(4);
        options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
        options.setMaxWriteBufferNumber(3);
        options.setTargetFileSizeBase(64 * 1024 * 1024); // 64MB
        
        // Open the database
        this.db = RocksDB.open(options, dbPath);
        
        // Load existing data into memory
        loadDataIntoMemory();
        
        logger.info("RocksDB storage initialized at: {}", dbPath);
    }
    
    private void loadDataIntoMemory() {
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            int loadedCount = 0;
            
            while (iterator.isValid()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                String value = new String(iterator.value(), StandardCharsets.UTF_8);
                Long count = Long.parseLong(value);
                
                inMemoryCache.put(key, count);
                loadedCount++;
                iterator.next();
            }
            
            logger.info("Loaded {} counters from RocksDB into memory", loadedCount);
        }
    }
    
    public long increment(String counterId, long delta) throws RocksDBException {
        // Update in-memory cache
        long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            return current + delta;
        });
        
        // Persist to RocksDB
        WriteOptions writeOptions = new WriteOptions();
        writeOptions.setSync(true); // Ensure durability
        
        db.put(writeOptions, 
               counterId.getBytes(StandardCharsets.UTF_8),
               String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));
        
        logger.debug("Incremented counter {} by {}, new value: {}", counterId, delta, newValue);
        return newValue;
    }
    
    public long decrement(String counterId, long delta) throws RocksDBException {
        // Update in-memory cache
        long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            return current - delta;
        });
        
        // Persist to RocksDB
        WriteOptions writeOptions = new WriteOptions();
        writeOptions.setSync(true); // Ensure durability
        
        db.put(writeOptions, 
               counterId.getBytes(StandardCharsets.UTF_8),
               String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));
        
        logger.debug("Decremented counter {} by {}, new value: {}", counterId, delta, newValue);
        return newValue;
    }
    
    public long get(String counterId) throws RocksDBException {
        // First check in-memory cache
        Long cachedValue = inMemoryCache.get(counterId);
        if (cachedValue != null) {
            return cachedValue;
        }
        
        // If not in cache, check RocksDB
        byte[] value = db.get(counterId.getBytes(StandardCharsets.UTF_8));
        if (value != null) {
            long count = Long.parseLong(new String(value, StandardCharsets.UTF_8));
            inMemoryCache.put(counterId, count);
            return count;
        }
        
        return 0;
    }
    
    public Map<String, Long> getAllCounters() {
        return new HashMap<>(inMemoryCache);
    }
    
    public void flush() throws RocksDBException {
        db.flush(new FlushOptions());
        logger.debug("Flushed RocksDB to disk");
    }
    
    public void compact() throws RocksDBException {
        db.compactRange();
        logger.debug("Compacted RocksDB");
    }
    
    @Override
    public void close() throws Exception {
        if (db != null) {
            db.close();
            logger.info("RocksDB storage closed");
        }
    }
    
    public String getDbPath() {
        return dbPath;
    }
    
    public int getCacheSize() {
        return inMemoryCache.size();
    }
} 