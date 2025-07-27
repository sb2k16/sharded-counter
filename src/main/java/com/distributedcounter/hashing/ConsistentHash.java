package com.distributedcounter.hashing;

import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ConsistentHash<T> {
    private final HashFunction hashFunction;
    private final int numberOfReplicas;
    private final SortedMap<Long, T> circle = new TreeMap<>();
    private final ConcurrentMap<T, Boolean> nodes = new ConcurrentHashMap<>();
    
    public ConsistentHash(HashFunction hashFunction, int numberOfReplicas, Collection<T> nodes) {
        this.hashFunction = hashFunction;
        this.numberOfReplicas = numberOfReplicas;
        for (T node : nodes) {
            add(node);
        }
    }
    
    public ConsistentHash(HashFunction hashFunction, int numberOfReplicas) {
        this.hashFunction = hashFunction;
        this.numberOfReplicas = numberOfReplicas;
    }
    
    public void add(T node) {
        nodes.put(node, true);
        for (int i = 0; i < numberOfReplicas; i++) {
            circle.put(hashFunction.hash(node.toString() + i), node);
        }
    }
    
    public void remove(T node) {
        nodes.remove(node);
        for (int i = 0; i < numberOfReplicas; i++) {
            circle.remove(hashFunction.hash(node.toString() + i));
        }
    }
    
    public T get(Object key) {
        if (circle.isEmpty()) {
            return null;
        }
        
        long hash = hashFunction.hash(key.toString());
        
        if (!circle.containsKey(hash)) {
            SortedMap<Long, T> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        
        return circle.get(hash);
    }
    
    public int getNodeCount() {
        return nodes.size();
    }
    
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
    
    public interface HashFunction {
        long hash(String key);
    }
    
    // Default hash function implementation using FNV-1a
    public static class FNVHashFunction implements HashFunction {
        private static final long FNV_64_INIT = 0xcbf29ce484222325L;
        private static final long FNV_64_PRIME = 0x100000001b3L;
        
        @Override
        public long hash(String key) {
            long hash = FNV_64_INIT;
            for (int i = 0; i < key.length(); i++) {
                hash ^= key.charAt(i);
                hash *= FNV_64_PRIME;
            }
            return hash;
        }
    }
} 