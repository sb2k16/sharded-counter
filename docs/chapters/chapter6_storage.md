# Chapter 6: Storage Layer: RocksDB and In-Memory Caching

## Dual-Layer Storage Architecture

The distributed sharded counter system implements a sophisticated dual-layer storage architecture that combines high-performance in-memory caching with persistent RocksDB storage. This architecture provides the best of both worlds: fast access to frequently used data and reliable persistence for data durability.

### Storage Layer Components

The storage layer consists of two main components:

1. **In-Memory Cache**: Fast access to counter values
2. **RocksDB Storage**: Persistent storage for data durability

The RocksDB storage implementation provides the foundation for persistent data storage:

```java
public class RocksDBStorage implements AutoCloseable {
    private final RocksDB db;
    private final Map<String, Long> inMemoryCache;
    
    public RocksDBStorage(String dbPath) throws RocksDBException {
        this.inMemoryCache = new ConcurrentHashMap<>();
        // Configure and open RocksDB
        Options options = new Options();
        options.setCreateIfMissing(true);
        this.db = RocksDB.open(options, dbPath);
        loadDataIntoMemory();
    }
}
```

For the complete implementation including error handling, logging, and advanced configuration options, see **Listing 6.1** in the appendix.

## RocksDB Configuration and Setup

RocksDB is configured for optimal performance in the distributed counter use case:

### Database Configuration

The RocksDB configuration is optimized for high-throughput counter operations:

```java
Options options = new Options();
options.setCreateIfMissing(true);
options.setMaxBackgroundJobs(4);
options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
```

For the complete configuration including all performance tuning options, see **Listing 6.2** in the appendix.

This configuration provides:
- **High Write Throughput**: Large write buffers for batch operations
- **Background Processing**: Multiple background jobs for compaction
- **Memory Efficiency**: Optimized buffer sizes for the workload
- **Durability**: Automatic data persistence and recovery

### Data Loading on Startup

When a shard starts up, it loads all existing data from RocksDB into the in-memory cache:

The data loading process ensures all existing counters are available in memory:

```java
private void loadDataIntoMemory() {
    try (RocksIterator iterator = db.newIterator()) {
        iterator.seekToFirst();
        while (iterator.isValid()) {
            String key = new String(iterator.key(), StandardCharsets.UTF_8);
            String value = new String(iterator.value(), StandardCharsets.UTF_8);
            Long count = Long.parseLong(value);
            inMemoryCache.put(key, count);
            iterator.next();
        }
    }
}
```

For the complete implementation with error handling and progress reporting, see **Listing 6.3** in the appendix.

This ensures that:
- **Fast Recovery**: All data is immediately available in memory
- **Consistency**: No data loss during restarts
- **Performance**: Sub-millisecond access to all counter values

## Key-Value Operations

The storage layer provides atomic key-value operations for counter management:

### Increment Operations

The increment operation updates both memory and persistent storage:

```java
public long increment(String counterId, long delta) throws RocksDBException {
    // Update in-memory cache
    long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        return current + delta;
    });
    
    // Persist to RocksDB
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true); // Ensure durability
    db.put(writeOptions, counterId.getBytes(), String.valueOf(newValue).getBytes());
    
    return newValue;
}
```

For the complete implementation with comprehensive error handling and logging, see **Listing 6.4** in the appendix.

### Decrement Operations

The decrement operation follows the same pattern as increment:

```java
public long decrement(String counterId, long delta) throws RocksDBException {
    // Update in-memory cache
    long newValue = inMemoryCache.compute(counterId, (key, oldValue) -> {
        long current = (oldValue != null) ? oldValue : 0;
        return current - delta;
    });
    
    // Persist to RocksDB
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true);
    db.put(writeOptions, counterId.getBytes(), String.valueOf(newValue).getBytes());
    
    return newValue;
}
```

For the complete implementation with validation and error handling, see **Listing 6.5** in the appendix.

### Read Operations

The read operation prioritizes cache access for performance:

```java
public long get(String counterId) throws RocksDBException {
    // First check in-memory cache
    Long cachedValue = inMemoryCache.get(counterId);
    if (cachedValue != null) {
        return cachedValue;
    }
    
    // Fall back to RocksDB
    byte[] value = db.get(counterId.getBytes());
    if (value != null) {
        long dbValue = Long.parseLong(new String(value));
        inMemoryCache.put(counterId, dbValue); // Update cache
        return dbValue;
    }
    
    return 0; // Default value for non-existent counters
}
```

For the complete implementation with cache warming and optimization strategies, see **Listing 6.6** in the appendix.

## Batch Operations

For high-throughput scenarios, the system supports batch operations:

Batch operations provide high-throughput updates:

```java
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
        batch.put(counterId.getBytes(), String.valueOf(newValue).getBytes());
    }
    
    // Execute batch
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true);
    db.write(writeOptions, batch);
}
```

For the complete implementation with error handling and retry logic, see **Listing 6.7** in the appendix.

## In-Memory Cache Management

The in-memory cache provides fast access to frequently used counter values:

### Cache Implementation

The in-memory cache provides fast access to counter values:

```java
private final Map<String, Long> inMemoryCache;

public RocksDBStorage(String dbPath) throws RocksDBException {
    this.inMemoryCache = new ConcurrentHashMap<>();
    // ... other initialization
}
```

For the complete cache implementation with eviction policies and monitoring, see **Listing 6.8** in the appendix.

The cache provides:
- **Thread Safety**: ConcurrentHashMap ensures thread-safe access
- **Fast Access**: O(1) lookup time for cached values
- **Memory Efficiency**: Only stores active counter values
- **Automatic Loading**: All data loaded on startup

### Cache Performance Monitoring

The system provides monitoring capabilities for cache performance:

Cache monitoring provides insights into performance:

```java
public int getCacheSize() {
    return inMemoryCache.size();
}

public Map<String, Long> getAllCounters() {
    return new HashMap<>(inMemoryCache);
}
```

For the complete monitoring implementation with metrics and alerts, see **Listing 6.9** in the appendix.

## Persistence Strategies

The system implements several persistence strategies to balance performance and durability:

### Write-Through Strategy

The default strategy ensures immediate persistence:

The write-through strategy ensures immediate persistence:

```java
WriteOptions writeOptions = new WriteOptions();
writeOptions.setSync(true); // Ensure durability

db.put(writeOptions, 
       counterId.getBytes(),
       String.valueOf(newValue).getBytes());
```

For the complete write-through implementation with error handling, see **Listing 6.10** in the appendix.

This provides:
- **Strong Durability**: Data is immediately persisted to disk
- **Crash Recovery**: No data loss on system crashes
- **Consistency**: ACID properties for all operations

### Write-Behind Strategy

For high-performance scenarios, the system can implement write-behind:

<details>
<summary>Write-Behind Strategy Implementation</summary>

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
</details>

### Adaptive Persistence

The system can adapt persistence strategy based on workload:

<details>
<summary>Adaptive Persistence Implementation</summary>

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
</details>

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

Incremental recovery handles large datasets efficiently:

```java
public class IncrementalRecovery {
    private final Set<String> recoveredKeys = new ConcurrentHashSet<>();
    
    public void recoverIncrementally() {
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = new String(iterator.key());
                if (!recoveredKeys.contains(key)) {
                    String value = new String(iterator.value());
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

For the complete incremental recovery implementation with progress tracking, see **Listing 6.12** in the appendix.

## Storage Performance Optimization

The system implements several performance optimizations:

### Memory Management

Memory management is optimized for high throughput:

```java
Options options = new Options();
options.setWriteBufferSize(64 * 1024 * 1024); // 64MB
options.setMaxWriteBufferNumber(3);
```

For the complete memory management configuration with all tuning options, see **Listing 6.13** in the appendix.

### Compression and Serialization

Compression reduces storage requirements:

```java
public class CompressedStorage {
    private final CompressionType compressionType = CompressionType.LZ4_COMPRESSION;
    
    public void putCompressed(String key, long value) throws RocksDBException {
        byte[] compressedValue = compress(String.valueOf(value));
        WriteOptions writeOptions = new WriteOptions();
        writeOptions.setCompressionType(compressionType);
        db.put(writeOptions, key.getBytes(), compressedValue);
    }
}
```

For the complete compression implementation with multiple algorithms, see **Listing 6.14** in the appendix.

### Background Maintenance

Background maintenance ensures optimal performance:

```java
public void compact() throws RocksDBException {
    db.compactRange();
}

public void flush() throws RocksDBException {
    db.flush(new FlushOptions());
}
```

For the complete background maintenance implementation with scheduling and monitoring, see **Listing 6.15** in the appendix.

## Storage Monitoring and Metrics

The system provides comprehensive monitoring capabilities:

### Performance Metrics

Performance metrics track system behavior:

```java
public class StorageMetrics {
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    
    public double getCacheHitRate() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }
}
```

For the complete metrics implementation with comprehensive monitoring, see **Listing 6.16** in the appendix.

### Health Monitoring

Health monitoring ensures system reliability:

```java
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
```

For the complete health monitoring implementation with comprehensive checks, see **Listing 6.17** in the appendix.

---

*This chapter explored the sophisticated storage layer architecture that combines in-memory caching with persistent RocksDB storage. In the next chapter, we'll examine performance analysis and optimization strategies for the distributed sharded counter system.* 