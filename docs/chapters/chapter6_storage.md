# Chapter 6: Storage Layer: RocksDB and In-Memory Caching

## Dual-Layer Storage Architecture

The distributed sharded counter system implements a sophisticated dual-layer storage architecture that combines high-performance in-memory caching with persistent RocksDB storage. This architecture provides the best of both worlds: fast access to frequently used data and reliable persistence for data durability.

### Storage Layer Components

The storage layer consists of two main components:

1. **In-Memory Cache**: Fast access to counter values
2. **RocksDB Storage**: Persistent storage for data durability

```java
// From RocksDBStorage.java
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
}
```

## RocksDB Configuration and Setup

RocksDB is configured for optimal performance in the distributed counter use case:

### Database Configuration

```java
// From RocksDBStorage.java
Options options = new Options();
options.setCreateIfMissing(true);
options.setMaxBackgroundJobs(4);
options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
options.setMaxWriteBufferNumber(3);
options.setTargetFileSizeBase(64 * 1024 * 1024); // 64MB
```

This configuration provides:
- **High Write Throughput**: Large write buffers for batch operations
- **Background Processing**: Multiple background jobs for compaction
- **Memory Efficiency**: Optimized buffer sizes for the workload
- **Durability**: Automatic data persistence and recovery

### Data Loading on Startup

When a shard starts up, it loads all existing data from RocksDB into the in-memory cache:

```java
// From RocksDBStorage.java
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
```

This ensures that:
- **Fast Recovery**: All data is immediately available in memory
- **Consistency**: No data loss during restarts
- **Performance**: Sub-millisecond access to all counter values

## Key-Value Operations

The storage layer provides atomic key-value operations for counter management:

### Increment Operations

```java
// From RocksDBStorage.java
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
```

### Decrement Operations

```java
// From RocksDBStorage.java
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
```

### Read Operations

```java
// From RocksDBStorage.java
public long get(String counterId) throws RocksDBException {
    // First check in-memory cache
    Long cachedValue = inMemoryCache.get(counterId);
    if (cachedValue != null) {
        return cachedValue;
    }
    
    // Fall back to RocksDB
    byte[] value = db.get(counterId.getBytes(StandardCharsets.UTF_8));
    if (value != null) {
        long dbValue = Long.parseLong(new String(value, StandardCharsets.UTF_8));
        // Update cache for future reads
        inMemoryCache.put(counterId, dbValue);
        return dbValue;
    }
    
    return 0; // Default value for non-existent counters
}
```

## Batch Operations

For high-throughput scenarios, the system supports batch operations:

```java
// Conceptual batch operations
public void batchIncrement(Map<String, Long> increments) throws RocksDBException {
    WriteBatch batch = new WriteBatch();
    
    for (Map.Entry<String, Long> entry : increments.entrySet()) {
        String counterId = entry.getKey();
        long delta = entry.getValue();
        
        // Update in-memory cache
        long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            return current + delta;
        });
        
        // Add to batch
        batch.put(counterId.getBytes(StandardCharsets.UTF_8),
                 String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));
    }
    
    // Execute batch
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true);
    db.write(writeOptions, batch);
}
```

## In-Memory Cache Management

The in-memory cache provides fast access to frequently used counter values:

### Cache Implementation

```java
// From RocksDBStorage.java
private final Map<String, Long> inMemoryCache;

public RocksDBStorage(String dbPath) throws RocksDBException {
    this.inMemoryCache = new ConcurrentHashMap<>();
    // ... other initialization
}
```

The cache provides:
- **Thread Safety**: ConcurrentHashMap ensures thread-safe access
- **Fast Access**: O(1) lookup time for cached values
- **Memory Efficiency**: Only stores active counter values
- **Automatic Loading**: All data loaded on startup

### Cache Performance Monitoring

The system provides monitoring capabilities for cache performance:

```java
// From RocksDBStorage.java
public int getCacheSize() {
    return inMemoryCache.size();
}

public Map<String, Long> getAllCounters() {
    return new HashMap<>(inMemoryCache);
}
```

## Persistence Strategies

The system implements several persistence strategies to balance performance and durability:

### Write-Through Strategy

The default strategy ensures immediate persistence:

```java
// From RocksDBStorage.java
WriteOptions writeOptions = new WriteOptions();
writeOptions.setSync(true); // Ensure durability

db.put(writeOptions, 
       counterId.getBytes(StandardCharsets.UTF_8),
       String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));
```

This provides:
- **Strong Durability**: Data is immediately persisted to disk
- **Crash Recovery**: No data loss on system crashes
- **Consistency**: ACID properties for all operations

### Write-Behind Strategy

For high-performance scenarios, the system can implement write-behind:

```java
// Conceptual write-behind implementation
public class WriteBehindStorage {
    private final Queue<WriteOperation> writeQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    public void incrementAsync(String counterId, long delta) {
        // Update cache immediately
        long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
            long current = (oldValue != null) ? oldValue : 0;
            return current + delta;
        });
        
        // Queue for background persistence
        writeQueue.offer(new WriteOperation(counterId, newValue));
    }
    
    private void startBackgroundWriter() {
        scheduler.scheduleAtFixedRate(() -> {
            List<WriteOperation> batch = new ArrayList<>();
            WriteOperation op;
            
            // Collect batch of operations
            while ((op = writeQueue.poll()) != null && batch.size() < 100) {
                batch.add(op);
            }
            
            if (!batch.isEmpty()) {
                persistBatch(batch);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }
}
```

### Adaptive Persistence

The system can adapt persistence strategy based on workload:

```java
// Conceptual adaptive persistence
public class AdaptiveStorage {
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong lastWriteTime = new AtomicLong(0);
    
    public void increment(String counterId, long delta) {
        long currentTime = System.currentTimeMillis();
        long writes = writeCount.incrementAndGet();
        
        // Switch to write-behind for high-frequency writes
        if (writes > 1000 && (currentTime - lastWriteTime.get()) < 1000) {
            incrementAsync(counterId, delta);
        } else {
            incrementSync(counterId, delta);
        }
        
        lastWriteTime.set(currentTime);
    }
}
```

## Data Recovery Mechanisms

The system implements robust data recovery mechanisms:

### Startup Recovery

```java
// From RocksDBStorage.java
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
```

### Incremental Recovery

For large datasets, the system can implement incremental recovery:

```java
// Conceptual incremental recovery
public class IncrementalRecovery {
    private final Set<String> recoveredKeys = new ConcurrentHashSet<>();
    
    public void recoverIncrementally() {
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            
            while (iterator.isValid()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                
                if (!recoveredKeys.contains(key)) {
                    String value = new String(iterator.value(), StandardCharsets.UTF_8);
                    Long count = Long.parseLong(value);
                    
                    inMemoryCache.put(key, count);
                    recoveredKeys.add(key);
                }
                
                iterator.next();
            }
        }
    }
}
```

## Storage Performance Optimization

The system implements several performance optimizations:

### Memory Management

```java
// From RocksDBStorage.java
Options options = new Options();
options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
options.setMaxWriteBufferNumber(3);
options.setTargetFileSizeBase(64 * 1024 * 1024); // 64MB
```

### Compression and Serialization

```java
// Conceptual compression implementation
public class CompressedStorage {
    private final CompressionType compressionType = CompressionType.LZ4_COMPRESSION;
    
    public void putCompressed(String key, long value) throws RocksDBException {
        // Compress value before storage
        byte[] compressedValue = compress(String.valueOf(value));
        
        WriteOptions writeOptions = new WriteOptions();
        writeOptions.setCompressionType(compressionType);
        
        db.put(writeOptions, key.getBytes(), compressedValue);
    }
    
    private byte[] compress(String data) {
        // Implementation of compression
        return data.getBytes(); // Simplified for example
    }
}
```

### Background Maintenance

```java
// From RocksDBStorage.java
public void compact() throws RocksDBException {
    db.compactRange();
}

public void flush() throws RocksDBException {
    db.flush(new FlushOptions());
}
```

## Storage Monitoring and Metrics

The system provides comprehensive monitoring capabilities:

### Performance Metrics

```java
// Conceptual metrics collection
public class StorageMetrics {
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    
    public void recordRead(String counterId) {
        readCount.incrementAndGet();
    }
    
    public void recordWrite(String counterId) {
        writeCount.incrementAndGet();
    }
    
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }
    
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }
    
    public double getCacheHitRate() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        
        return total > 0 ? (double) hits / total : 0.0;
    }
}
```

### Health Monitoring

```java
// From RocksDBStorage.java
@Override
public void close() throws Exception {
    try {
        if (db != null) {
            db.close();
        }
    } catch (Exception e) {
        logger.error("Error closing RocksDB", e);
        throw e;
    }
}

public String getDbPath() {
    return dbPath;
}
```

---

*This chapter explored the sophisticated storage layer architecture that combines in-memory caching with persistent RocksDB storage. In the next chapter, we'll examine performance analysis and optimization strategies for the distributed sharded counter system.* 