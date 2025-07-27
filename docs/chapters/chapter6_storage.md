# Chapter 6: Storage Layer: RocksDB and In-Memory Caching

## Dual-Layer Storage Architecture

The storage layer of the distributed sharded counter system implements a sophisticated dual-layer architecture that combines the speed of in-memory caching with the durability of persistent storage. This approach provides the best of both worlds: sub-millisecond response times for hot data and guaranteed durability for all data.

The storage architecture consists of two primary components:

1. **In-Memory Cache**: Fast access layer using ConcurrentHashMap for thread-safe operations
2. **Persistent Storage**: Durable storage layer using RocksDB for high-performance key-value storage

## RocksDB Implementation

RocksDB is a high-performance embedded key-value store that provides excellent write performance and compression. It's particularly well-suited for the distributed sharded counter use case due to its LSM (Log-Structured Merge) tree architecture.

### RocksDB Configuration

```java
// From RocksDBStorage.java
public class RocksDBStorage {
    private RocksDB db;
    private final String dbPath;
    
    public RocksDBStorage(String dbPath) {
        this.dbPath = dbPath;
        initializeDatabase();
    }
    
    private void initializeDatabase() {
        try {
            Options options = new Options();
            
            // Optimize for write-heavy workloads
            options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
            options.setMaxWriteBufferNumber(4);
            options.setMinWriteBufferNumberToMerge(2);
            
            // Compression settings
            options.setCompressionType(CompressionType.LZ4_COMPRESSION);
            options.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);
            
            // Bloom filter for faster lookups
            options.setUseBloomFilter(true);
            options.setBloomFilterBitsPerKey(10);
            
            // Block cache for frequently accessed data
            options.setBlockCache(new LRUCache(100 * 1024 * 1024)); // 100MB
            
            // Create database directory if it doesn't exist
            Files.createDirectories(Paths.get(dbPath));
            
            db = RocksDB.open(options, dbPath);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize RocksDB", e);
        }
    }
}
```

The RocksDB configuration is optimized for:

- **Write Performance**: Large write buffers and multiple write buffers
- **Read Performance**: Bloom filters and block cache
- **Storage Efficiency**: LZ4 compression for active data, ZSTD for older data
- **Memory Usage**: Configurable block cache size

### Key-Value Operations

```java
// From RocksDBStorage.java
public class RocksDBStorage {
    
    public void put(String key, String value) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            db.put(keyBytes, valueBytes);
        } catch (Exception e) {
            throw new StorageException("Failed to write to RocksDB", e);
        }
    }
    
    public String get(String key) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = db.get(keyBytes);
            
            if (valueBytes == null) {
                return null;
            }
            
            return new String(valueBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new StorageException("Failed to read from RocksDB", e);
        }
    }
    
    public void delete(String key) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            db.remove(keyBytes);
        } catch (Exception e) {
            throw new StorageException("Failed to delete from RocksDB", e);
        }
    }
    
    public RocksIterator getIterator() {
        return db.newIterator();
    }
}
```

### Batch Operations

For high-throughput scenarios, RocksDB supports batch operations:

```java
// From RocksDBStorage.java
public class RocksDBStorage {
    
    public void batchWrite(Map<String, String> keyValuePairs) {
        try (WriteBatch batch = new WriteBatch()) {
            for (Map.Entry<String, String> entry : keyValuePairs.entrySet()) {
                byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] valueBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                batch.put(keyBytes, valueBytes);
            }
            
            WriteOptions writeOptions = new WriteOptions();
            writeOptions.setSync(false); // Asynchronous writes for performance
            db.write(writeOptions, batch);
            
        } catch (Exception e) {
            throw new StorageException("Failed to perform batch write", e);
        }
    }
    
    public Map<String, String> batchRead(List<String> keys) {
        Map<String, String> results = new HashMap<>();
        
        try {
            List<byte[]> keyBytes = keys.stream()
                .map(key -> key.getBytes(StandardCharsets.UTF_8))
                .collect(Collectors.toList());
            
            List<byte[]> valueBytes = db.multiGetAsList(keyBytes);
            
            for (int i = 0; i < keys.size(); i++) {
                if (valueBytes.get(i) != null) {
                    String value = new String(valueBytes.get(i), StandardCharsets.UTF_8);
                    results.put(keys.get(i), value);
                }
            }
            
        } catch (Exception e) {
            throw new StorageException("Failed to perform batch read", e);
        }
        
        return results;
    }
}
```

## In-Memory Cache Management

The in-memory cache provides fast access to frequently accessed data and serves as a write-through cache for the persistent storage layer.

### Cache Implementation

```java
// From ShardNode.java
public class ShardNode {
    private final Map<String, Long> inMemoryCache;
    private final RocksDBStorage persistentStorage;
    private final CacheMetrics cacheMetrics;
    
    public ShardNode() {
        this.inMemoryCache = new ConcurrentHashMap<>();
        this.persistentStorage = new RocksDBStorage("shard_data");
        this.cacheMetrics = new CacheMetrics();
        loadDataFromPersistentStorage();
    }
    
    public long increment(String counterId, long delta) {
        return inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            long newValue = current + delta;
            
            // Asynchronous persistence
            CompletableFuture.runAsync(() -> {
                persistentStorage.put(counterId, String.valueOf(newValue));
                cacheMetrics.recordWrite();
            });
            
            cacheMetrics.recordHit();
            return newValue;
        });
    }
    
    public long getValue(String counterId) {
        Long value = inMemoryCache.get(counterId);
        
        if (value != null) {
            cacheMetrics.recordHit();
            return value;
        }
        
        // Cache miss - load from persistent storage
        String persistedValue = persistentStorage.get(counterId);
        if (persistedValue != null) {
            long longValue = Long.parseLong(persistedValue);
            inMemoryCache.put(counterId, longValue);
            cacheMetrics.recordMiss();
            return longValue;
        }
        
        cacheMetrics.recordMiss();
        return 0;
    }
}
```

### Cache Performance Monitoring

```java
// From CacheMetrics.java
public class CacheMetrics {
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    
    public void recordHit() {
        hitCount.incrementAndGet();
    }
    
    public void recordMiss() {
        missCount.incrementAndGet();
    }
    
    public void recordWrite() {
        writeCount.incrementAndGet();
    }
    
    public void recordEviction() {
        evictionCount.incrementAndGet();
    }
    
    public double getHitRate() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        
        return total > 0 ? (double) hits / total : 0.0;
    }
    
    public CacheStats getStats() {
        return new CacheStats(
            hitCount.get(),
            missCount.get(),
            writeCount.get(),
            evictionCount.get(),
            getHitRate()
        );
    }
}
```

## Persistence Strategies

The system implements several persistence strategies to balance performance with durability.

### Write-Through Caching

```java
// From ShardNode.java
public class WriteThroughCache {
    private final Map<String, Long> cache;
    private final RocksDBStorage storage;
    
    public long increment(String counterId, long delta) {
        return cache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            long newValue = current + delta;
            
            // Synchronous write to storage
            storage.put(counterId, String.valueOf(newValue));
            
            return newValue;
        });
    }
}
```

Write-through caching provides:
- **Strong Durability**: Data is immediately persisted
- **Consistency**: Cache and storage are always in sync
- **Lower Performance**: Each write requires disk I/O

### Write-Behind Caching

```java
// From ShardNode.java
public class WriteBehindCache {
    private final Map<String, Long> cache;
    private final RocksDBStorage storage;
    private final ExecutorService writeExecutor;
    private final BlockingQueue<WriteOperation> writeQueue;
    
    public WriteBehindCache() {
        this.cache = new ConcurrentHashMap<>();
        this.storage = new RocksDBStorage("shard_data");
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.writeQueue = new LinkedBlockingQueue<>();
        
        // Start background writer
        writeExecutor.submit(this::backgroundWriter);
    }
    
    public long increment(String counterId, long delta) {
        return cache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            long newValue = current + delta;
            
            // Queue for background persistence
            writeQueue.offer(new WriteOperation(counterId, newValue));
            
            return newValue;
        });
    }
    
    private void backgroundWriter() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WriteOperation operation = writeQueue.take();
                storage.put(operation.getKey(), String.valueOf(operation.getValue()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Background write failed", e);
            }
        }
    }
}
```

Write-behind caching provides:
- **High Performance**: Writes return immediately
- **Batch Efficiency**: Multiple writes can be batched
- **Eventual Durability**: Data is persisted asynchronously
- **Risk of Data Loss**: Recent writes may be lost on crash

### Adaptive Persistence

```java
// From ShardNode.java
public class AdaptivePersistence {
    private final Map<String, Long> cache;
    private final RocksDBStorage storage;
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong lastSyncTime = new AtomicLong(System.currentTimeMillis());
    
    private static final long SYNC_INTERVAL = 1000; // 1 second
    private static final int SYNC_THRESHOLD = 100; // 100 writes
    
    public long increment(String counterId, long delta) {
        return cache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            long newValue = current + delta;
            
            // Adaptive persistence based on write frequency
            if (shouldSync()) {
                // Synchronous write for high-frequency counters
                storage.put(counterId, String.valueOf(newValue));
            } else {
                // Asynchronous write for low-frequency counters
                CompletableFuture.runAsync(() -> {
                    storage.put(counterId, String.valueOf(newValue));
                });
            }
            
            return newValue;
        });
    }
    
    private boolean shouldSync() {
        long writes = writeCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long lastSync = lastSyncTime.get();
        
        // Sync if threshold reached or time interval passed
        if (writes >= SYNC_THRESHOLD || (now - lastSync) >= SYNC_INTERVAL) {
            lastSyncTime.set(now);
            writeCount.set(0);
            return true;
        }
        
        return false;
    }
}
```

## Data Recovery Mechanisms

The system implements comprehensive data recovery mechanisms to handle various failure scenarios.

### Startup Recovery

```java
// From ShardNode.java
private void loadDataFromPersistentStorage() {
    try {
        logger.info("Loading data from persistent storage...");
        int loadedCount = 0;
        
        try (RocksIterator iterator = persistentStorage.getIterator()) {
            iterator.seekToFirst();
            
            while (iterator.isValid()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                String value = new String(iterator.value(), StandardCharsets.UTF_8);
                
                try {
                    long longValue = Long.parseLong(value);
                    inMemoryCache.put(key, longValue);
                    loadedCount++;
                } catch (NumberFormatException e) {
                    logger.warn("Invalid counter value for key: " + key);
                }
                
                iterator.next();
            }
        }
        
        logger.info("Loaded " + loadedCount + " counters from persistent storage");
        
    } catch (Exception e) {
        logger.error("Failed to load data from persistent storage", e);
        throw new RuntimeException("Data recovery failed", e);
    }
}
```

### Incremental Recovery

```java
// From ShardNode.java
public class IncrementalRecovery {
    private final Map<String, Long> cache;
    private final RocksDBStorage storage;
    private final AtomicLong lastRecoveryTime = new AtomicLong();
    
    public void performIncrementalRecovery() {
        long now = System.currentTimeMillis();
        long lastRecovery = lastRecoveryTime.get();
        
        // Only perform recovery if enough time has passed
        if (now - lastRecovery < 60000) { // 1 minute
            return;
        }
        
        try {
            // Find counters in cache but not in storage
            Set<String> cacheKeys = cache.keySet();
            Set<String> storageKeys = getStorageKeys();
            
            Set<String> missingInStorage = new HashSet<>(cacheKeys);
            missingInStorage.removeAll(storageKeys);
            
            // Persist missing counters
            for (String key : missingInStorage) {
                Long value = cache.get(key);
                if (value != null) {
                    storage.put(key, String.valueOf(value));
                }
            }
            
            lastRecoveryTime.set(now);
            logger.info("Incremental recovery completed, persisted " + missingInStorage.size() + " counters");
            
        } catch (Exception e) {
            logger.error("Incremental recovery failed", e);
        }
    }
    
    private Set<String> getStorageKeys() {
        Set<String> keys = new HashSet<>();
        
        try (RocksIterator iterator = storage.getIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                keys.add(key);
                iterator.next();
            }
        }
        
        return keys;
    }
}
```

## Storage Performance Optimization

The system implements several optimizations to maximize storage performance.

### Memory Management

```java
// From ShardNode.java
public class MemoryManager {
    private final Map<String, Long> cache;
    private final long maxCacheSize;
    private final AtomicLong currentCacheSize = new AtomicLong();
    
    public MemoryManager(long maxCacheSize) {
        this.cache = new ConcurrentHashMap<>();
        this.maxCacheSize = maxCacheSize;
    }
    
    public long increment(String counterId, long delta) {
        return cache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            long newValue = current + delta;
            
            // Check memory usage
            long estimatedSize = estimateMemoryUsage(counterId, newValue);
            if (currentCacheSize.addAndGet(estimatedSize) > maxCacheSize) {
                // Evict least recently used entries
                evictLRUEntries();
            }
            
            return newValue;
        });
    }
    
    private void evictLRUEntries() {
        // Simple LRU eviction - in practice, use a proper LRU cache
        if (cache.size() > 10000) { // Arbitrary threshold
            // Remove 10% of entries
            int entriesToRemove = cache.size() / 10;
            Iterator<Map.Entry<String, Long>> iterator = cache.entrySet().iterator();
            
            for (int i = 0; i < entriesToRemove && iterator.hasNext(); i++) {
                Map.Entry<String, Long> entry = iterator.next();
                iterator.remove();
                currentCacheSize.addAndGet(-estimateMemoryUsage(entry.getKey(), entry.getValue()));
            }
        }
    }
    
    private long estimateMemoryUsage(String key, long value) {
        // Rough estimation of memory usage
        return key.length() * 2 + 8 + 16; // String overhead + Long + HashMap entry overhead
    }
}
```

### Compression and Serialization

```java
// From RocksDBStorage.java
public class CompressedStorage {
    private final RocksDB db;
    private final CompressionCodec codec;
    
    public CompressedStorage() {
        this.codec = new LZ4CompressionCodec();
        initializeDatabase();
    }
    
    public void put(String key, String value) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            
            // Compress value if it's large enough
            byte[] compressedValue = valueBytes.length > 1024 ? 
                codec.compress(valueBytes) : valueBytes;
            
            db.put(keyBytes, compressedValue);
            
        } catch (Exception e) {
            throw new StorageException("Failed to write compressed data", e);
        }
    }
    
    public String get(String key) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = db.get(keyBytes);
            
            if (valueBytes == null) {
                return null;
            }
            
            // Decompress if necessary
            byte[] decompressedValue = codec.isCompressed(valueBytes) ? 
                codec.decompress(valueBytes) : valueBytes;
            
            return new String(decompressedValue, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            throw new StorageException("Failed to read compressed data", e);
        }
    }
}
```

---

*This chapter examined the sophisticated storage layer implementation using RocksDB and in-memory caching. In the next chapter, we'll analyze performance characteristics and optimization strategies for the distributed sharded counter system.* 