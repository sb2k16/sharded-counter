# Chapter 10: Real-World Use Cases and Best Practices

Distributed sharded counters find applications across numerous domains where high-throughput counting and aggregation are required. This chapter explores real-world use cases, implementation best practices, common pitfalls, and practical examples that demonstrate the versatility and power of distributed sharded counters.

## Social Media Applications

### 1. Like and Reaction Counters

#### Implementation Example
```java
public class SocialMediaCounterService {
    private final ShardedCounterClient counterClient;
    private final CacheManager cacheManager;
    
    public void handleLike(String postId, String userId) {
        String counterKey = "likes:" + postId;
        
        try {
            // Increment the like counter
            ShardedCounterResponse response = counterClient.increment(counterKey, 1);
            
            // Update cache
            cacheManager.updateCache(counterKey, response.getCount());
            
            // Log the action
            logUserAction(userId, "like", postId);
            
        } catch (Exception e) {
            logger.error("Failed to handle like for post: " + postId, e);
            throw new LikeOperationException("Failed to process like", e);
        }
    }
    
    public long getLikeCount(String postId) {
        String counterKey = "likes:" + postId;
        
        // Try cache first
        Long cachedCount = cacheManager.getFromCache(counterKey);
        if (cachedCount != null) {
            return cachedCount;
        }
        
        // Get from distributed counter
        ShardedCounterResponse response = counterClient.getCount(counterKey);
        long count = response.getCount();
        
        // Update cache
        cacheManager.updateCache(counterKey, count);
        
        return count;
    }
    
    public Map<String, Long> getLikeCounts(List<String> postIds) {
        Map<String, Long> results = new HashMap<>();
        
        // Batch read for efficiency
        List<String> counterKeys = postIds.stream()
            .map(postId -> "likes:" + postId)
            .collect(Collectors.toList());
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(counterKeys);
        
        for (String postId : postIds) {
            String counterKey = "likes:" + postId;
            ShardedCounterResponse response = responses.get(counterKey);
            results.put(postId, response != null ? response.getCount() : 0L);
        }
        
        return results;
    }
}
```

#### Performance Optimization
```java
public class SocialMediaOptimizer {
    private final ShardedCounterClient counterClient;
    private final CacheManager cacheManager;
    
    public void optimizeForSocialMedia() {
        // Setup read-heavy optimization
        setupReadHeavyOptimization();
        
        // Setup batch operations
        setupBatchOperations();
        
        // Setup cache warming
        setupCacheWarming();
    }
    
    private void setupReadHeavyOptimization() {
        // Configure for high read throughput
        counterClient.setReadConsistency(ConsistencyLevel.EVENTUAL);
        counterClient.setReadTimeout(100); // 100ms timeout for reads
        
        // Setup read replicas
        counterClient.setReadReplicas(3);
    }
    
    private void setupBatchOperations() {
        // Enable batch reads
        counterClient.enableBatchReads(true);
        counterClient.setBatchSize(100);
        
        // Enable batch writes
        counterClient.enableBatchWrites(true);
        counterClient.setWriteBatchSize(50);
    }
    
    private void setupCacheWarming() {
        // Warm cache for popular posts
        List<String> popularPosts = getPopularPosts();
        
        for (String postId : popularPosts) {
            String counterKey = "likes:" + postId;
            CompletableFuture.runAsync(() -> {
                try {
                    long count = counterClient.getCount(counterKey).getCount();
                    cacheManager.updateCache(counterKey, count);
                } catch (Exception e) {
                    logger.warn("Failed to warm cache for post: " + postId, e);
                }
            });
        }
    }
}
```

### 2. View Counters and Analytics

#### Implementation Example
```java
public class ViewCounterService {
    private final ShardedCounterClient counterClient;
    private final AnalyticsProcessor analyticsProcessor;
    
    public void recordView(String contentId, String userId, ViewContext context) {
        String viewKey = "views:" + contentId;
        String userViewKey = "user_views:" + contentId + ":" + userId;
        
        try {
            // Increment total views
            counterClient.increment(viewKey, 1);
            
            // Record unique user view (if not already viewed)
            if (!hasUserViewed(contentId, userId)) {
                counterClient.increment(userViewKey, 1);
                counterClient.increment("unique_views:" + contentId, 1);
            }
            
            // Record analytics data
            analyticsProcessor.recordView(contentId, userId, context);
            
        } catch (Exception e) {
            logger.error("Failed to record view for content: " + contentId, e);
        }
    }
    
    public ViewStatistics getViewStatistics(String contentId) {
        String viewKey = "views:" + contentId;
        String uniqueViewKey = "unique_views:" + contentId;
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(
            Arrays.asList(viewKey, uniqueViewKey)
        );
        
        long totalViews = responses.get(viewKey).getCount();
        long uniqueViews = responses.get(uniqueViewKey).getCount();
        
        return ViewStatistics.builder()
            .contentId(contentId)
            .totalViews(totalViews)
            .uniqueViews(uniqueViews)
            .engagementRate(calculateEngagementRate(totalViews, uniqueViews))
            .build();
    }
    
    private boolean hasUserViewed(String contentId, String userId) {
        String userViewKey = "user_views:" + contentId + ":" + userId;
        try {
            ShardedCounterResponse response = counterClient.getCount(userViewKey);
            return response.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

## E-commerce Platforms

### 1. Product View and Purchase Counters

#### Implementation Example
```java
public class EcommerceCounterService {
    private final ShardedCounterClient counterClient;
    private final InventoryManager inventoryManager;
    
    public void recordProductView(String productId, String userId) {
        String viewKey = "product_views:" + productId;
        String categoryViewKey = "category_views:" + getProductCategory(productId);
        
        try {
            // Increment product views
            counterClient.increment(viewKey, 1);
            counterClient.increment(categoryViewKey, 1);
            
            // Record user behavior
            recordUserBehavior(userId, "view", productId);
            
        } catch (Exception e) {
            logger.error("Failed to record product view: " + productId, e);
        }
    }
    
    public void recordPurchase(String productId, String userId, int quantity) {
        String purchaseKey = "product_purchases:" + productId;
        String revenueKey = "product_revenue:" + productId;
        
        try {
            // Get product price
            BigDecimal price = getProductPrice(productId);
            BigDecimal totalRevenue = price.multiply(BigDecimal.valueOf(quantity));
            
            // Increment purchase counters
            counterClient.increment(purchaseKey, quantity);
            counterClient.increment("total_purchases", quantity);
            
            // Increment revenue (in cents to avoid floating point issues)
            long revenueCents = totalRevenue.multiply(BigDecimal.valueOf(100)).longValue();
            counterClient.increment(revenueKey, revenueCents);
            
            // Update inventory
            inventoryManager.updateInventory(productId, -quantity);
            
            // Record user behavior
            recordUserBehavior(userId, "purchase", productId);
            
        } catch (Exception e) {
            logger.error("Failed to record purchase: " + productId, e);
            throw new PurchaseException("Failed to record purchase", e);
        }
    }
    
    public ProductAnalytics getProductAnalytics(String productId) {
        String viewKey = "product_views:" + productId;
        String purchaseKey = "product_purchases:" + productId;
        String revenueKey = "product_revenue:" + productId;
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(
            Arrays.asList(viewKey, purchaseKey, revenueKey)
        );
        
        long views = responses.get(viewKey).getCount();
        long purchases = responses.get(purchaseKey).getCount();
        long revenueCents = responses.get(revenueKey).getCount();
        
        BigDecimal revenue = BigDecimal.valueOf(revenueCents).divide(BigDecimal.valueOf(100));
        double conversionRate = views > 0 ? (double) purchases / views : 0.0;
        
        return ProductAnalytics.builder()
            .productId(productId)
            .views(views)
            .purchases(purchases)
            .revenue(revenue)
            .conversionRate(conversionRate)
            .build();
    }
}
```

### 2. Inventory Management

#### Implementation Example
```java
public class InventoryCounterService {
    private final ShardedCounterClient counterClient;
    private final AlertManager alertManager;
    
    public void updateInventory(String productId, int quantity) {
        String inventoryKey = "inventory:" + productId;
        String reservedKey = "reserved:" + productId;
        
        try {
            // Update available inventory
            counterClient.increment(inventoryKey, quantity);
            
            // Check if inventory is low
            checkLowInventory(productId);
            
        } catch (Exception e) {
            logger.error("Failed to update inventory: " + productId, e);
            throw new InventoryException("Failed to update inventory", e);
        }
    }
    
    public boolean reserveInventory(String productId, int quantity) {
        String inventoryKey = "inventory:" + productId;
        String reservedKey = "reserved:" + productId;
        
        try {
            // Check available inventory
            ShardedCounterResponse inventoryResponse = counterClient.getCount(inventoryKey);
            ShardedCounterResponse reservedResponse = counterClient.getCount(reservedKey);
            
            long available = inventoryResponse.getCount() - reservedResponse.getCount();
            
            if (available >= quantity) {
                // Reserve the inventory
                counterClient.increment(reservedKey, quantity);
                return true;
            } else {
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Failed to reserve inventory: " + productId, e);
            return false;
        }
    }
    
    public void releaseReservedInventory(String productId, int quantity) {
        String reservedKey = "reserved:" + productId;
        
        try {
            counterClient.increment(reservedKey, -quantity);
        } catch (Exception e) {
            logger.error("Failed to release reserved inventory: " + productId, e);
        }
    }
    
    private void checkLowInventory(String productId) {
        String inventoryKey = "inventory:" + productId;
        
        try {
            ShardedCounterResponse response = counterClient.getCount(inventoryKey);
            long currentInventory = response.getCount();
            
            if (currentInventory <= getLowInventoryThreshold(productId)) {
                alertManager.sendLowInventoryAlert(productId, currentInventory);
            }
            
        } catch (Exception e) {
            logger.error("Failed to check low inventory: " + productId, e);
        }
    }
}
```

## IoT and Sensor Data

### 1. Sensor Event Aggregation

#### Implementation Example
```java
public class SensorDataCounterService {
    private final ShardedCounterClient counterClient;
    private final TimeSeriesProcessor timeSeriesProcessor;
    
    public void recordSensorEvent(String sensorId, String metric, double value) {
        String metricKey = "sensor:" + sensorId + ":" + metric;
        String aggregateKey = "aggregate:" + metric;
        
        try {
            // Record individual sensor reading
            counterClient.increment(metricKey, 1);
            
            // Update running averages
            updateRunningAverage(metricKey, value);
            
            // Update aggregate statistics
            updateAggregateStatistics(aggregateKey, value);
            
            // Process time series data
            timeSeriesProcessor.processDataPoint(sensorId, metric, value);
            
        } catch (Exception e) {
            logger.error("Failed to record sensor event: " + sensorId, e);
        }
    }
    
    public SensorStatistics getSensorStatistics(String sensorId, String metric) {
        String metricKey = "sensor:" + sensorId + ":" + metric;
        String countKey = metricKey + ":count";
        String sumKey = metricKey + ":sum";
        String minKey = metricKey + ":min";
        String maxKey = metricKey + ":max";
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(
            Arrays.asList(countKey, sumKey, minKey, maxKey)
        );
        
        long count = responses.get(countKey).getCount();
        long sum = responses.get(sumKey).getCount();
        long min = responses.get(minKey).getCount();
        long max = responses.get(maxKey).getCount();
        
        double average = count > 0 ? (double) sum / count : 0.0;
        
        return SensorStatistics.builder()
            .sensorId(sensorId)
            .metric(metric)
            .count(count)
            .average(average)
            .min(min / 1000.0) // Convert from millis
            .max(max / 1000.0) // Convert from millis
            .build();
    }
    
    private void updateRunningAverage(String metricKey, double value) {
        String countKey = metricKey + ":count";
        String sumKey = metricKey + ":sum";
        
        // Convert value to millis to avoid floating point issues
        long valueMillis = (long) (value * 1000);
        
        counterClient.increment(countKey, 1);
        counterClient.increment(sumKey, valueMillis);
    }
    
    private void updateAggregateStatistics(String aggregateKey, double value) {
        String minKey = aggregateKey + ":min";
        String maxKey = aggregateKey + ":max";
        
        long valueMillis = (long) (value * 1000);
        
        // Update min and max using conditional updates
        updateMin(minKey, valueMillis);
        updateMax(maxKey, valueMillis);
    }
}
```

### 2. Real-time Monitoring

#### Implementation Example
```java
public class RealTimeMonitoringService {
    private final ShardedCounterClient counterClient;
    private final AlertManager alertManager;
    private final ThresholdManager thresholdManager;
    
    public void monitorSensorData(String sensorId, String metric, double value) {
        String thresholdKey = "threshold:" + sensorId + ":" + metric;
        String alertKey = "alert:" + sensorId + ":" + metric;
        
        try {
            // Check thresholds
            Threshold threshold = thresholdManager.getThreshold(sensorId, metric);
            
            if (threshold != null) {
                if (value > threshold.getMaxValue()) {
                    handleThresholdExceeded(sensorId, metric, value, "MAX");
                } else if (value < threshold.getMinValue()) {
                    handleThresholdExceeded(sensorId, metric, value, "MIN");
                }
            }
            
            // Update monitoring counters
            counterClient.increment("monitoring:" + sensorId + ":" + metric, 1);
            
        } catch (Exception e) {
            logger.error("Failed to monitor sensor data: " + sensorId, e);
        }
    }
    
    private void handleThresholdExceeded(String sensorId, String metric, double value, String type) {
        String alertKey = "alert:" + sensorId + ":" + metric + ":" + type;
        
        // Check if alert already sent recently
        ShardedCounterResponse response = counterClient.getCount(alertKey);
        long lastAlertTime = response.getCount();
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAlertTime > 300000) { // 5 minutes
            // Send alert
            alertManager.sendThresholdAlert(sensorId, metric, value, type);
            
            // Update alert timestamp
            counterClient.setCount(alertKey, currentTime);
        }
    }
    
    public MonitoringDashboard getMonitoringDashboard() {
        Map<String, Object> dashboard = new HashMap<>();
        
        // Get system health metrics
        dashboard.put("totalSensors", getTotalSensorCount());
        dashboard.put("activeSensors", getActiveSensorCount());
        dashboard.put("alerts", getActiveAlertCount());
        dashboard.put("systemHealth", getSystemHealthScore());
        
        // Get performance metrics
        dashboard.put("dataPointsPerSecond", getDataPointsPerSecond());
        dashboard.put("averageLatency", getAverageLatency());
        dashboard.put("errorRate", getErrorRate());
        
        return MonitoringDashboard.builder()
            .metrics(dashboard)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}
```

## Gaming and Leaderboards

### 1. Score Tracking and Leaderboards

#### Implementation Example
```java
public class GamingCounterService {
    private final ShardedCounterClient counterClient;
    private final LeaderboardManager leaderboardManager;
    
    public void recordScore(String playerId, String gameId, long score) {
        String scoreKey = "score:" + playerId + ":" + gameId;
        String gameScoreKey = "game_score:" + gameId;
        
        try {
            // Record player's score
            counterClient.setCount(scoreKey, score);
            
            // Update game total score
            counterClient.increment(gameScoreKey, score);
            
            // Update leaderboard
            leaderboardManager.updateLeaderboard(gameId, playerId, score);
            
            // Record achievement if applicable
            checkAchievements(playerId, gameId, score);
            
        } catch (Exception e) {
            logger.error("Failed to record score: " + playerId, e);
        }
    }
    
    public PlayerStatistics getPlayerStatistics(String playerId) {
        String totalScoreKey = "total_score:" + playerId;
        String gamesPlayedKey = "games_played:" + playerId;
        String achievementsKey = "achievements:" + playerId;
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(
            Arrays.asList(totalScoreKey, gamesPlayedKey, achievementsKey)
        );
        
        long totalScore = responses.get(totalScoreKey).getCount();
        long gamesPlayed = responses.get(gamesPlayedKey).getCount();
        long achievements = responses.get(achievementsKey).getCount();
        
        return PlayerStatistics.builder()
            .playerId(playerId)
            .totalScore(totalScore)
            .gamesPlayed(gamesPlayed)
            .achievements(achievements)
            .averageScore(gamesPlayed > 0 ? (double) totalScore / gamesPlayed : 0.0)
            .build();
    }
    
    public Leaderboard getLeaderboard(String gameId, int limit) {
        return leaderboardManager.getLeaderboard(gameId, limit);
    }
    
    private void checkAchievements(String playerId, String gameId, long score) {
        // Check for score-based achievements
        if (score >= 10000) {
            unlockAchievement(playerId, "HIGH_SCORER");
        }
        
        if (score >= 50000) {
            unlockAchievement(playerId, "MASTER_PLAYER");
        }
        
        // Check for game count achievements
        String gamesPlayedKey = "games_played:" + playerId;
        ShardedCounterResponse response = counterClient.getCount(gamesPlayedKey);
        long gamesPlayed = response.getCount();
        
        if (gamesPlayed >= 100) {
            unlockAchievement(playerId, "DEDICATED_PLAYER");
        }
    }
    
    private void unlockAchievement(String playerId, String achievement) {
        String achievementKey = "achievement:" + playerId + ":" + achievement;
        
        try {
            // Check if already unlocked
            ShardedCounterResponse response = counterClient.getCount(achievementKey);
            if (response.getCount() == 0) {
                // Unlock achievement
                counterClient.increment(achievementKey, 1);
                counterClient.increment("achievements:" + playerId, 1);
                
                // Send notification
                sendAchievementNotification(playerId, achievement);
            }
        } catch (Exception e) {
            logger.error("Failed to unlock achievement: " + achievement, e);
        }
    }
}
```

### 2. Real-time Rankings

#### Implementation Example
```java
public class RealTimeRankingService {
    private final ShardedCounterClient counterClient;
    private final RankingEngine rankingEngine;
    
    public void updateRanking(String playerId, String category, long score) {
        String rankingKey = "ranking:" + category + ":" + playerId;
        
        try {
            // Update player's score
            counterClient.setCount(rankingKey, score);
            
            // Update real-time ranking
            rankingEngine.updateRanking(category, playerId, score);
            
            // Update category statistics
            updateCategoryStatistics(category, score);
            
        } catch (Exception e) {
            logger.error("Failed to update ranking: " + playerId, e);
        }
    }
    
    public Ranking getPlayerRanking(String playerId, String category) {
        try {
            return rankingEngine.getPlayerRanking(category, playerId);
        } catch (Exception e) {
            logger.error("Failed to get player ranking: " + playerId, e);
            return null;
        }
    }
    
    public List<Ranking> getTopRankings(String category, int limit) {
        try {
            return rankingEngine.getTopRankings(category, limit);
        } catch (Exception e) {
            logger.error("Failed to get top rankings: " + category, e);
            return Collections.emptyList();
        }
    }
    
    private void updateCategoryStatistics(String category, long score) {
        String totalScoreKey = "category_total:" + category;
        String playerCountKey = "category_players:" + category;
        String maxScoreKey = "category_max:" + category;
        
        counterClient.increment(totalScoreKey, score);
        counterClient.increment(playerCountKey, 1);
        
        // Update max score if higher
        updateMax(maxScoreKey, score);
    }
}
```

## Analytics and Metrics

### 1. Event Tracking and Analytics

#### Implementation Example
```java
public class AnalyticsCounterService {
    private final ShardedCounterClient counterClient;
    private final AnalyticsProcessor analyticsProcessor;
    
    public void trackEvent(String eventType, String userId, Map<String, String> properties) {
        String eventKey = "event:" + eventType;
        String userEventKey = "user_event:" + userId + ":" + eventType;
        
        try {
            // Increment event counters
            counterClient.increment(eventKey, 1);
            counterClient.increment(userEventKey, 1);
            
            // Track event properties
            trackEventProperties(eventType, properties);
            
            // Process analytics
            analyticsProcessor.processEvent(eventType, userId, properties);
            
        } catch (Exception e) {
            logger.error("Failed to track event: " + eventType, e);
        }
    }
    
    public AnalyticsReport getAnalyticsReport(String eventType, Duration timeRange) {
        String eventKey = "event:" + eventType;
        String uniqueUsersKey = "unique_users:" + eventType;
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(
            Arrays.asList(eventKey, uniqueUsersKey)
        );
        
        long totalEvents = responses.get(eventKey).getCount();
        long uniqueUsers = responses.get(uniqueUsersKey).getCount();
        
        // Get time-based breakdown
        Map<String, Long> timeBreakdown = getTimeBreakdown(eventType, timeRange);
        
        return AnalyticsReport.builder()
            .eventType(eventType)
            .totalEvents(totalEvents)
            .uniqueUsers(uniqueUsers)
            .timeRange(timeRange)
            .timeBreakdown(timeBreakdown)
            .build();
    }
    
    private void trackEventProperties(String eventType, Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String propertyKey = "property:" + eventType + ":" + entry.getKey() + ":" + entry.getValue();
            counterClient.increment(propertyKey, 1);
        }
    }
    
    private Map<String, Long> getTimeBreakdown(String eventType, Duration timeRange) {
        Map<String, Long> breakdown = new HashMap<>();
        
        long endTime = System.currentTimeMillis();
        long startTime = endTime - timeRange.toMillis();
        
        // Get hourly breakdown
        for (long time = startTime; time <= endTime; time += 3600000) { // 1 hour
            String hourKey = "hourly:" + eventType + ":" + (time / 3600000);
            try {
                ShardedCounterResponse response = counterClient.getCount(hourKey);
                breakdown.put(String.valueOf(time / 3600000), response.getCount());
            } catch (Exception e) {
                breakdown.put(String.valueOf(time / 3600000), 0L);
            }
        }
        
        return breakdown;
    }
}
```

### 2. Performance Metrics

#### Implementation Example
```java
public class PerformanceMetricsService {
    private final ShardedCounterClient counterClient;
    private final PerformanceMonitor performanceMonitor;
    
    public void recordPerformanceMetric(String metricName, long value) {
        String metricKey = "performance:" + metricName;
        String sumKey = metricKey + ":sum";
        String countKey = metricKey + ":count";
        String minKey = metricKey + ":min";
        String maxKey = metricKey + ":max";
        
        try {
            // Update running statistics
            counterClient.increment(countKey, 1);
            counterClient.increment(sumKey, value);
            updateMin(minKey, value);
            updateMax(maxKey, value);
            
            // Check for performance alerts
            checkPerformanceAlerts(metricName, value);
            
        } catch (Exception e) {
            logger.error("Failed to record performance metric: " + metricName, e);
        }
    }
    
    public PerformanceStatistics getPerformanceStatistics(String metricName) {
        String sumKey = "performance:" + metricName + ":sum";
        String countKey = "performance:" + metricName + ":count";
        String minKey = "performance:" + metricName + ":min";
        String maxKey = "performance:" + metricName + ":max";
        
        Map<String, ShardedCounterResponse> responses = counterClient.getCounts(
            Arrays.asList(sumKey, countKey, minKey, maxKey)
        );
        
        long sum = responses.get(sumKey).getCount();
        long count = responses.get(countKey).getCount();
        long min = responses.get(minKey).getCount();
        long max = responses.get(maxKey).getCount();
        
        double average = count > 0 ? (double) sum / count : 0.0;
        
        return PerformanceStatistics.builder()
            .metricName(metricName)
            .count(count)
            .sum(sum)
            .average(average)
            .min(min)
            .max(max)
            .build();
    }
    
    private void checkPerformanceAlerts(String metricName, long value) {
        PerformanceThreshold threshold = performanceMonitor.getThreshold(metricName);
        
        if (threshold != null && value > threshold.getMaxValue()) {
            performanceMonitor.sendPerformanceAlert(metricName, value, threshold);
        }
    }
}
```

## Implementation Best Practices

### 1. Consistency Level Selection

#### Choosing the Right Consistency Level
```java
public class ConsistencyLevelSelector {
    
    public ConsistencyLevel selectConsistencyLevel(String useCase, String operation) {
        switch (useCase) {
            case "social_media":
                return selectSocialMediaConsistency(operation);
            case "ecommerce":
                return selectEcommerceConsistency(operation);
            case "gaming":
                return selectGamingConsistency(operation);
            case "analytics":
                return selectAnalyticsConsistency(operation);
            default:
                return ConsistencyLevel.STRONG;
        }
    }
    
    private ConsistencyLevel selectSocialMediaConsistency(String operation) {
        switch (operation) {
            case "like":
            case "share":
                return ConsistencyLevel.EVENTUAL; // High throughput, eventual consistency is acceptable
            case "user_profile":
                return ConsistencyLevel.STRONG; // User data needs strong consistency
            default:
                return ConsistencyLevel.EVENTUAL;
        }
    }
    
    private ConsistencyLevel selectEcommerceConsistency(String operation) {
        switch (operation) {
            case "inventory":
            case "purchase":
                return ConsistencyLevel.STRONG; // Critical for business operations
            case "product_view":
            case "analytics":
                return ConsistencyLevel.EVENTUAL; // Can tolerate eventual consistency
            default:
                return ConsistencyLevel.STRONG;
        }
    }
    
    private ConsistencyLevel selectGamingConsistency(String operation) {
        switch (operation) {
            case "score":
            case "leaderboard":
                return ConsistencyLevel.STRONG; // Important for fair competition
            case "achievement":
                return ConsistencyLevel.EVENTUAL; // Can be eventually consistent
            default:
                return ConsistencyLevel.STRONG;
        }
    }
}
```

### 2. Hotspot Management

#### Detecting and Managing Hotspots
```java
public class HotspotManager {
    private final ShardedCounterClient counterClient;
    private final LoadBalancer loadBalancer;
    
    public void monitorHotspots() {
        // Monitor shard load distribution
        Map<String, Long> shardLoads = getShardLoads();
        
        // Identify hotspots
        List<String> hotspots = identifyHotspots(shardLoads);
        
        // Rebalance if hotspots detected
        if (!hotspots.isEmpty()) {
            rebalanceHotspots(hotspots);
        }
    }
    
    private List<String> identifyHotspots(Map<String, Long> shardLoads) {
        List<String> hotspots = new ArrayList<>();
        
        // Calculate average load
        double averageLoad = shardLoads.values().stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
        
        // Identify shards with load > 2x average
        for (Map.Entry<String, Long> entry : shardLoads.entrySet()) {
            if (entry.getValue() > averageLoad * 2) {
                hotspots.add(entry.getKey());
            }
        }
        
        return hotspots;
    }
    
    private void rebalanceHotspots(List<String> hotspots) {
        for (String hotspot : hotspots) {
            // Get keys in hotspot shard
            List<String> keys = getKeysInShard(hotspot);
            
            // Redistribute keys to other shards
            for (String key : keys) {
                String newShard = findOptimalShard(key);
                if (!newShard.equals(hotspot)) {
                    migrateKey(key, hotspot, newShard);
                }
            }
        }
    }
    
    private String findOptimalShard(String key) {
        // Use consistent hashing to find optimal shard
        return consistentHash.getShard(key);
    }
}
```

### 3. Error Handling and Recovery

#### Robust Error Handling
```java
public class ErrorHandler {
    private final CircuitBreaker circuitBreaker;
    private final RetryManager retryManager;
    private final FallbackManager fallbackManager;
    
    public <T> T handleOperation(Supplier<T> operation, String operationName) {
        try {
            return circuitBreaker.execute(() -> {
                try {
                    return retryManager.executeWithRetry(operation, operationName);
                } catch (Exception e) {
                    return fallbackManager.getFallback(operationName);
                }
            });
        } catch (Exception e) {
            logger.error("Operation failed after all retries: " + operationName, e);
            return fallbackManager.getFallback(operationName);
        }
    }
    
    public void handleCounterOperation(String counterKey, long increment) {
        try {
            counterClient.increment(counterKey, increment);
        } catch (ShardUnavailableException e) {
            // Try alternative shard
            handleShardFailure(counterKey, increment);
        } catch (NetworkException e) {
            // Queue for retry
            retryManager.queueForRetry(counterKey, increment);
        } catch (Exception e) {
            // Log and use fallback
            logger.error("Counter operation failed: " + counterKey, e);
            fallbackManager.handleCounterFailure(counterKey, increment);
        }
    }
    
    private void handleShardFailure(String counterKey, long increment) {
        // Find alternative shard
        String alternativeShard = findAlternativeShard(counterKey);
        
        try {
            counterClient.incrementOnShard(counterKey, increment, alternativeShard);
        } catch (Exception e) {
            logger.error("Alternative shard also failed: " + alternativeShard, e);
            fallbackManager.handleCounterFailure(counterKey, increment);
        }
    }
}
```

## Anti-patterns to Avoid

### 1. Single Shard Bottlenecks

#### Problem: All traffic routed to one shard
```java
// ANTI-PATTERN: Poor hashing function
public class PoorHashingFunction {
    public String getShard(String key) {
        // This always returns the same shard for all keys
        return "shard-1";
    }
}

// SOLUTION: Proper consistent hashing
public class ConsistentHashingFunction {
    private final ConsistentHash consistentHash;
    
    public String getShard(String key) {
        return consistentHash.getShard(key);
    }
}
```

### 2. Ignoring Replication Lag

#### Problem: Not monitoring replication lag
```java
// ANTI-PATTERN: Ignoring replication status
public class IgnoreReplicationLag {
    public void writeData(String key, String value) {
        // Write to primary without checking replication
        primaryNode.write(key, value);
        // No monitoring of replication lag
    }
}

// SOLUTION: Monitor replication lag
public class MonitorReplicationLag {
    public void writeData(String key, String value) {
        // Write to primary
        primaryNode.write(key, value);
        
        // Monitor replication lag
        long lag = getReplicationLag();
        if (lag > maxAllowedLag) {
            sendReplicationLagAlert(lag);
        }
    }
}
```

### 3. Neglecting Recovery Procedures

#### Problem: No disaster recovery plan
```java
// ANTI-PATTERN: No backup strategy
public class NoBackupStrategy {
    public void storeData(String key, String value) {
        // Store data without backup
        storage.put(key, value);
        // No backup procedures
    }
}

// SOLUTION: Comprehensive backup strategy
public class ComprehensiveBackupStrategy {
    public void storeData(String key, String value) {
        // Store data
        storage.put(key, value);
        
        // Schedule backup
        backupManager.scheduleBackup(key, value);
        
        // Verify backup
        backupManager.verifyBackup(key);
    }
}
```

### 4. Poor Monitoring

#### Problem: Insufficient monitoring
```java
// ANTI-PATTERN: No monitoring
public class NoMonitoring {
    public void processRequest(String request) {
        // Process request without monitoring
        processRequest(request);
        // No metrics, no alerts, no visibility
    }
}

// SOLUTION: Comprehensive monitoring
public class ComprehensiveMonitoring {
    public void processRequest(String request) {
        Timer.Context timer = requestTimer.time();
        try {
            processRequest(request);
            successCounter.inc();
        } catch (Exception e) {
            errorCounter.inc();
            throw e;
        } finally {
            timer.stop();
        }
    }
}
```

## Performance Optimization Strategies

### 1. Caching Strategies

#### Multi-Level Caching
```java
public class MultiLevelCache {
    private final L1Cache l1Cache; // In-memory cache
    private final L2Cache l2Cache; // Distributed cache
    private final ShardedCounterClient counterClient;
    
    public long getCount(String key) {
        // Try L1 cache first
        Long l1Value = l1Cache.get(key);
        if (l1Value != null) {
            return l1Value;
        }
        
        // Try L2 cache
        Long l2Value = l2Cache.get(key);
        if (l2Value != null) {
            l1Cache.put(key, l2Value);
            return l2Value;
        }
        
        // Get from distributed counter
        ShardedCounterResponse response = counterClient.getCount(key);
        long value = response.getCount();
        
        // Update caches
        l1Cache.put(key, value);
        l2Cache.put(key, value);
        
        return value;
    }
    
    public void increment(String key, long increment) {
        // Update distributed counter
        counterClient.increment(key, increment);
        
        // Invalidate caches
        l1Cache.invalidate(key);
        l2Cache.invalidate(key);
    }
}
```

### 2. Batch Operations

#### Efficient Batch Processing
```java
public class BatchProcessor {
    private final ShardedCounterClient counterClient;
    private final Queue<BatchOperation> batchQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;
    
    public void setupBatchProcessing() {
        // Process batches every 100ms
        scheduler.scheduleAtFixedRate(this::processBatch, 0, 100, TimeUnit.MILLISECONDS);
    }
    
    public void addToBatch(String key, long increment) {
        batchQueue.offer(new BatchOperation(key, increment));
    }
    
    private void processBatch() {
        Map<String, Long> batch = new HashMap<>();
        
        // Collect operations
        BatchOperation operation;
        while ((operation = batchQueue.poll()) != null) {
            batch.merge(operation.getKey(), operation.getIncrement(), Long::sum);
        }
        
        // Execute batch
        if (!batch.isEmpty()) {
            try {
                counterClient.batchIncrement(batch);
            } catch (Exception e) {
                logger.error("Batch processing failed", e);
                // Re-queue failed operations
                for (Map.Entry<String, Long> entry : batch.entrySet()) {
                    batchQueue.offer(new BatchOperation(entry.getKey(), entry.getValue()));
                }
            }
        }
    }
}
```

## Summary

This chapter explored real-world use cases and best practices for distributed sharded counters. We covered various application domains including social media, e-commerce, IoT, gaming, and analytics, demonstrating the versatility of distributed sharded counters.

Key takeaways from this chapter:
- **Use Case Diversity**: Distributed sharded counters are applicable across many domains
- **Consistency Selection**: Choose consistency levels based on business requirements
- **Hotspot Management**: Monitor and manage hotspots to ensure even load distribution
- **Error Handling**: Implement robust error handling and recovery mechanisms
- **Anti-patterns**: Avoid common pitfalls like single shard bottlenecks and poor monitoring
- **Performance Optimization**: Use caching, batching, and other optimization strategies
- **Best Practices**: Follow established patterns for reliable and scalable implementations

The distributed sharded counter pattern provides a powerful foundation for building high-throughput, scalable counting and aggregation systems. By understanding the use cases, best practices, and common pitfalls, developers can effectively implement distributed sharded counters that meet the demands of modern applications.

---

*This chapter presented real-world use cases and best practices for distributed sharded counters, concluding the comprehensive guide.* 