# Chapter 9: Deployment and Operations

Deploying and operating a distributed sharded counter system in production requires careful planning, robust monitoring, and well-defined operational procedures. This chapter covers deployment strategies, monitoring and observability, maintenance procedures, security considerations, and operational best practices.

## Deployment Architecture

### 1. Multi-Node Deployment Strategies

#### Physical Infrastructure Deployment
```java
public class InfrastructureDeployment {
    private final Map<String, ServerSpec> serverSpecs;
    private final NetworkTopology networkTopology;
    
    public void deployPhysicalInfrastructure() {
        // Deploy coordinator nodes
        for (String coordinatorId : getCoordinatorIds()) {
            ServerSpec spec = serverSpecs.get("coordinator");
            deployCoordinatorNode(coordinatorId, spec);
        }
        
        // Deploy shard nodes
        for (String shardId : getShardIds()) {
            ServerSpec spec = serverSpecs.get("shard");
            deployShardNode(shardId, spec);
        }
        
        // Configure network connectivity
        configureNetworkTopology();
    }
    
    private void deployCoordinatorNode(String nodeId, ServerSpec spec) {
        // Provision server with required specifications
        Server server = provisionServer(spec);
        
        // Install required software
        installJava(server, "11");
        installMonitoringTools(server);
        installSecurityTools(server);
        
        // Deploy coordinator application
        deployApplication(server, "coordinator");
        
        // Configure networking
        configureFirewall(server);
        configureLoadBalancer(server);
    }
    
    private void deployShardNode(String nodeId, ServerSpec spec) {
        // Provision server with storage-optimized specifications
        Server server = provisionServer(spec);
        
        // Install storage software
        installRocksDB(server);
        installMonitoringTools(server);
        
        // Deploy shard application
        deployApplication(server, "shard");
        
        // Configure storage
        configureStorage(server);
        configureBackup(server);
    }
}
```

#### Container-Based Deployment
```java
public class ContainerDeployment {
    private final DockerManager dockerManager;
    private final KubernetesManager k8sManager;
    
    public void deployWithDocker() {
        // Build container images
        buildCoordinatorImage();
        buildShardImage();
        
        // Deploy coordinator containers
        for (String coordinatorId : getCoordinatorIds()) {
            deployCoordinatorContainer(coordinatorId);
        }
        
        // Deploy shard containers
        for (String shardId : getShardIds()) {
            deployShardContainer(shardId);
        }
    }
    
    public void deployWithKubernetes() {
        // Create Kubernetes namespaces
        k8sManager.createNamespace("distributed-counter");
        
        // Deploy coordinator deployment
        k8sManager.deployCoordinatorDeployment();
        
        // Deploy shard stateful sets
        k8sManager.deployShardStatefulSet();
        
        // Create services
        k8sManager.createCoordinatorService();
        k8sManager.createShardService();
        
        // Configure ingress
        k8sManager.configureIngress();
    }
    
    private void buildCoordinatorImage() {
        Dockerfile dockerfile = new Dockerfile()
            .from("openjdk:11-jre-slim")
            .copy("coordinator.jar", "/app/")
            .copy("config/", "/app/config/")
            .expose(8080)
            .cmd("java", "-jar", "/app/coordinator.jar");
        
        dockerManager.buildImage("coordinator:latest", dockerfile);
    }
}
```

### 2. Configuration Management

#### Centralized Configuration
```java
public class ConfigurationManager {
    private final ConfigService configService;
    private final Map<String, Configuration> nodeConfigs = new ConcurrentHashMap<>();
    
    public void deployConfiguration() {
        // Deploy coordinator configurations
        for (String coordinatorId : getCoordinatorIds()) {
            Configuration config = createCoordinatorConfig(coordinatorId);
            configService.deployConfig(coordinatorId, config);
        }
        
        // Deploy shard configurations
        for (String shardId : getShardIds()) {
            Configuration config = createShardConfig(shardId);
            configService.deployConfig(shardId, config);
        }
    }
    
    private Configuration createCoordinatorConfig(String coordinatorId) {
        return Configuration.builder()
            .nodeId(coordinatorId)
            .nodeType(NodeType.COORDINATOR)
            .port(8080)
            .shardNodes(getShardNodeList())
            .replicationFactor(3)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .timeoutMs(5000)
            .maxRetries(3)
            .build();
    }
    
    private Configuration createShardConfig(String shardId) {
        return Configuration.builder()
            .nodeId(shardId)
            .nodeType(NodeType.SHARD)
            .port(8081)
            .storagePath("/data/shards/" + shardId)
            .backupPath("/backup/shards/" + shardId)
            .maxMemoryMb(8192)
            .build();
    }
}
```

#### Environment-Specific Configurations
```java
public class EnvironmentConfigManager {
    private final Map<Environment, Configuration> envConfigs = new HashMap<>();
    
    public void setupEnvironmentConfigs() {
        // Development environment
        envConfigs.put(Environment.DEV, createDevConfig());
        
        // Staging environment
        envConfigs.put(Environment.STAGING, createStagingConfig());
        
        // Production environment
        envConfigs.put(Environment.PROD, createProdConfig());
    }
    
    private Configuration createDevConfig() {
        return Configuration.builder()
            .replicationFactor(1)
            .consistencyLevel(ConsistencyLevel.EVENTUAL)
            .timeoutMs(1000)
            .maxRetries(1)
            .enableMetrics(false)
            .logLevel(LogLevel.DEBUG)
            .build();
    }
    
    private Configuration createProdConfig() {
        return Configuration.builder()
            .replicationFactor(3)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .timeoutMs(5000)
            .maxRetries(3)
            .enableMetrics(true)
            .logLevel(LogLevel.INFO)
            .enableSecurity(true)
            .build();
    }
}
```

## Monitoring and Observability

### 1. Metrics Collection

#### Application Metrics
```java
public class MetricsCollector {
    private final MetricRegistry metricRegistry;
    private final Map<String, Timer> operationTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> operationCounters = new ConcurrentHashMap<>();
    
    public void setupMetrics() {
        // Request metrics
        Timer requestTimer = metricRegistry.timer("request.latency");
        Counter requestCounter = metricRegistry.counter("request.count");
        Counter errorCounter = metricRegistry.counter("request.errors");
        
        // Shard-specific metrics
        for (String shardId : getShardIds()) {
            Timer shardTimer = metricRegistry.timer("shard." + shardId + ".latency");
            Counter shardCounter = metricRegistry.counter("shard." + shardId + ".count");
            operationTimers.put(shardId, shardTimer);
            operationCounters.put(shardId, shardCounter);
        }
        
        // System metrics
        Gauge<Long> memoryUsage = () -> getMemoryUsage();
        Gauge<Long> diskUsage = () -> getDiskUsage();
        Gauge<Integer> activeConnections = () -> getActiveConnections();
        
        metricRegistry.register("system.memory", memoryUsage);
        metricRegistry.register("system.disk", diskUsage);
        metricRegistry.register("system.connections", activeConnections);
    }
    
    public void recordOperation(String shardId, long durationMs, boolean success) {
        Timer timer = operationTimers.get(shardId);
        Counter counter = operationCounters.get(shardId);
        
        if (timer != null) {
            timer.update(durationMs, TimeUnit.MILLISECONDS);
        }
        
        if (counter != null) {
            counter.inc();
        }
        
        if (!success) {
            metricRegistry.counter("request.errors").inc();
        }
    }
}
```

#### Business Metrics
```java
public class BusinessMetricsCollector {
    private final MetricRegistry metricRegistry;
    
    public void setupBusinessMetrics() {
        // Counter-specific metrics
        Gauge<Long> totalCount = () -> getTotalCount();
        Gauge<Long> activeCounters = () -> getActiveCounters();
        Gauge<Double> averageIncrement = () -> getAverageIncrement();
        
        metricRegistry.register("business.total_count", totalCount);
        metricRegistry.register("business.active_counters", activeCounters);
        metricRegistry.register("business.avg_increment", averageIncrement);
        
        // Performance metrics
        Timer incrementTimer = metricRegistry.timer("business.increment.latency");
        Timer readTimer = metricRegistry.timer("business.read.latency");
        
        // Throughput metrics
        Meter incrementThroughput = metricRegistry.meter("business.increment.throughput");
        Meter readThroughput = metricRegistry.meter("business.read.throughput");
    }
    
    private long getTotalCount() {
        long total = 0;
        for (String shardId : getShardIds()) {
            total += getShardCount(shardId);
        }
        return total;
    }
    
    private long getActiveCounters() {
        return getShardIds().stream()
            .mapToLong(shardId -> getActiveCountersForShard(shardId))
            .sum();
    }
}
```

### 2. Logging and Tracing

#### Structured Logging
```java
public class StructuredLogger {
    private final Logger logger = LoggerFactory.getLogger(StructuredLogger.class);
    private final String nodeId;
    private final String nodeType;
    
    public StructuredLogger(String nodeId, String nodeType) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
    }
    
    public void logOperation(String operation, String shardId, long durationMs, boolean success) {
        LogEntry entry = LogEntry.builder()
            .timestamp(System.currentTimeMillis())
            .nodeId(nodeId)
            .nodeType(nodeType)
            .operation(operation)
            .shardId(shardId)
            .durationMs(durationMs)
            .success(success)
            .build();
        
        logger.info("Operation completed: {}", entry);
    }
    
    public void logError(String operation, String shardId, Exception error) {
        LogEntry entry = LogEntry.builder()
            .timestamp(System.currentTimeMillis())
            .nodeId(nodeId)
            .nodeType(nodeType)
            .operation(operation)
            .shardId(shardId)
            .error(error.getMessage())
            .stackTrace(getStackTrace(error))
            .build();
        
        logger.error("Operation failed: {}", entry);
    }
    
    public void logHealthCheck(String component, boolean healthy, String details) {
        LogEntry entry = LogEntry.builder()
            .timestamp(System.currentTimeMillis())
            .nodeId(nodeId)
            .nodeType(nodeType)
            .operation("health_check")
            .component(component)
            .healthy(healthy)
            .details(details)
            .build();
        
        if (healthy) {
            logger.debug("Health check passed: {}", entry);
        } else {
            logger.warn("Health check failed: {}", entry);
        }
    }
}
```

#### Distributed Tracing
```java
public class TracingManager {
    private final Tracer tracer;
    private final Map<String, Span> activeSpans = new ConcurrentHashMap<>();
    
    public Span startTrace(String operation, String shardId) {
        Span span = tracer.buildSpan(operation)
            .withTag("shard_id", shardId)
            .withTag("node_id", getNodeId())
            .withTag("node_type", getNodeType())
            .start();
        
        activeSpans.put(operation + "_" + shardId, span);
        return span;
    }
    
    public void endTrace(String operation, String shardId, boolean success) {
        String key = operation + "_" + shardId;
        Span span = activeSpans.remove(key);
        
        if (span != null) {
            span.setTag("success", success);
            span.finish();
        }
    }
    
    public void addTraceEvent(String operation, String shardId, String event, Map<String, String> attributes) {
        String key = operation + "_" + shardId;
        Span span = activeSpans.get(key);
        
        if (span != null) {
            span.log(event, attributes);
        }
    }
}
```

### 3. Health Monitoring

#### Health Check Implementation
```java
public class HealthMonitor {
    private final Map<String, HealthChecker> healthCheckers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public void setupHealthChecks() {
        // System health checks
        healthCheckers.put("system", new SystemHealthChecker());
        healthCheckers.put("storage", new StorageHealthChecker());
        healthCheckers.put("network", new NetworkHealthChecker());
        healthCheckers.put("application", new ApplicationHealthChecker());
        
        // Start periodic health checks
        scheduler.scheduleAtFixedRate(this::runHealthChecks, 0, 30, TimeUnit.SECONDS);
    }
    
    private void runHealthChecks() {
        Map<String, HealthStatus> results = new HashMap<>();
        
        for (Map.Entry<String, HealthChecker> entry : healthCheckers.entrySet()) {
            String component = entry.getKey();
            HealthChecker checker = entry.getValue();
            
            try {
                HealthStatus status = checker.checkHealth();
                results.put(component, status);
            } catch (Exception e) {
                results.put(component, HealthStatus.unhealthy(e.getMessage()));
            }
        }
        
        // Update health status
        updateHealthStatus(results);
        
        // Send alerts if needed
        checkAndSendAlerts(results);
    }
    
    private void updateHealthStatus(Map<String, HealthStatus> results) {
        boolean overallHealthy = results.values().stream()
            .allMatch(HealthStatus::isHealthy);
        
        HealthStatus overallStatus = overallHealthy ? 
            HealthStatus.healthy() : 
            HealthStatus.unhealthy("One or more components unhealthy");
        
        // Update metrics
        metricRegistry.gauge("health.overall", () -> overallHealthy ? 1 : 0);
        
        for (Map.Entry<String, HealthStatus> entry : results.entrySet()) {
            String component = entry.getKey();
            HealthStatus status = entry.getValue();
            metricRegistry.gauge("health." + component, () -> status.isHealthy() ? 1 : 0);
        }
    }
}
```

## Maintenance Procedures

### 1. Rolling Updates

#### Zero-Downtime Deployment
```java
public class RollingUpdateManager {
    private final NodeManager nodeManager;
    private final LoadBalancer loadBalancer;
    private final HealthMonitor healthMonitor;
    
    public void performRollingUpdate(String newVersion) {
        List<String> nodeIds = getAllNodeIds();
        
        for (String nodeId : nodeIds) {
            // Step 1: Drain traffic from node
            drainNode(nodeId);
            
            // Step 2: Wait for active requests to complete
            waitForActiveRequests(nodeId);
            
            // Step 3: Update node
            updateNode(nodeId, newVersion);
            
            // Step 4: Verify node health
            if (!verifyNodeHealth(nodeId)) {
                rollbackNode(nodeId);
                throw new UpdateFailedException("Node health check failed after update");
            }
            
            // Step 5: Re-enable traffic
            enableNode(nodeId);
            
            // Step 6: Wait for stabilization
            waitForStabilization(nodeId);
        }
    }
    
    private void drainNode(String nodeId) {
        logger.info("Draining traffic from node: {}", nodeId);
        loadBalancer.drainNode(nodeId);
        
        // Wait for active connections to drain
        while (getActiveConnections(nodeId) > 0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void updateNode(String nodeId, String newVersion) {
        logger.info("Updating node {} to version {}", nodeId, newVersion);
        
        // Stop the application
        stopApplication(nodeId);
        
        // Backup current version
        backupApplication(nodeId);
        
        // Deploy new version
        deployApplication(nodeId, newVersion);
        
        // Start the application
        startApplication(nodeId);
    }
    
    private boolean verifyNodeHealth(String nodeId) {
        // Wait for node to become healthy
        for (int i = 0; i < 30; i++) {
            if (healthMonitor.isNodeHealthy(nodeId)) {
                return true;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }
}
```

### 2. Scaling Operations

#### Horizontal Scaling
```java
public class ScalingManager {
    private final NodeManager nodeManager;
    private final ShardManager shardManager;
    private final LoadBalancer loadBalancer;
    
    public void scaleOut(int additionalNodes) {
        logger.info("Scaling out by {} nodes", additionalNodes);
        
        // Step 1: Provision new nodes
        List<String> newNodeIds = provisionNodes(additionalNodes);
        
        // Step 2: Deploy applications on new nodes
        for (String nodeId : newNodeIds) {
            deployApplication(nodeId);
        }
        
        // Step 3: Rebalance shards
        rebalanceShards(newNodeIds);
        
        // Step 4: Update load balancer
        updateLoadBalancer(newNodeIds);
        
        // Step 5: Verify system health
        verifySystemHealth();
        
        logger.info("Scale out completed successfully");
    }
    
    public void scaleIn(List<String> nodeIdsToRemove) {
        logger.info("Scaling in nodes: {}", nodeIdsToRemove);
        
        // Step 1: Rebalance shards away from nodes to be removed
        rebalanceShardsFromNodes(nodeIdsToRemove);
        
        // Step 2: Drain traffic from nodes
        for (String nodeId : nodeIdsToRemove) {
            drainNode(nodeId);
        }
        
        // Step 3: Remove nodes from load balancer
        removeNodesFromLoadBalancer(nodeIdsToRemove);
        
        // Step 4: Stop and decommission nodes
        for (String nodeId : nodeIdsToRemove) {
            stopApplication(nodeId);
            decommissionNode(nodeId);
        }
        
        // Step 5: Verify system health
        verifySystemHealth();
        
        logger.info("Scale in completed successfully");
    }
    
    private void rebalanceShards(List<String> newNodeIds) {
        // Get current shard distribution
        Map<String, String> currentShardAssignment = getCurrentShardAssignment();
        
        // Calculate optimal shard distribution
        Map<String, String> optimalAssignment = calculateOptimalShardAssignment(newNodeIds);
        
        // Migrate shards to achieve optimal distribution
        for (Map.Entry<String, String> entry : optimalAssignment.entrySet()) {
            String shardId = entry.getKey();
            String targetNodeId = entry.getValue();
            String currentNodeId = currentShardAssignment.get(shardId);
            
            if (!targetNodeId.equals(currentNodeId)) {
                migrateShard(shardId, currentNodeId, targetNodeId);
            }
        }
    }
}
```

### 3. Backup and Restore

#### Automated Backup System
```java
public class BackupManager {
    private final StorageManager storageManager;
    private final BackupService backupService;
    private final ScheduledExecutorService scheduler;
    
    public void setupAutomatedBackups() {
        // Full backup every 24 hours
        scheduler.scheduleAtFixedRate(this::performFullBackup, 0, 24, TimeUnit.HOURS);
        
        // Incremental backup every 6 hours
        scheduler.scheduleAtFixedRate(this::performIncrementalBackup, 0, 6, TimeUnit.HOURS);
        
        // Verify backups every 12 hours
        scheduler.scheduleAtFixedRate(this::verifyBackups, 0, 12, TimeUnit.HOURS);
    }
    
    private void performFullBackup() {
        try {
            logger.info("Starting full backup");
            
            for (String shardId : getShardIds()) {
                String backupId = backupService.createFullBackup(shardId);
                logger.info("Full backup completed for shard {}: {}", shardId, backupId);
            }
            
            // Clean up old backups
            cleanupOldBackups(Duration.ofDays(7));
            
        } catch (Exception e) {
            logger.error("Full backup failed", e);
            sendBackupFailureAlert(e);
        }
    }
    
    private void performIncrementalBackup() {
        try {
            logger.info("Starting incremental backup");
            
            for (String shardId : getShardIds()) {
                String backupId = backupService.createIncrementalBackup(shardId);
                logger.info("Incremental backup completed for shard {}: {}", shardId, backupId);
            }
            
        } catch (Exception e) {
            logger.error("Incremental backup failed", e);
            sendBackupFailureAlert(e);
        }
    }
    
    private void verifyBackups() {
        try {
            logger.info("Starting backup verification");
            
            for (String shardId : getShardIds()) {
                List<String> backupIds = backupService.listBackups(shardId);
                
                for (String backupId : backupIds) {
                    if (!backupService.verifyBackup(shardId, backupId)) {
                        logger.error("Backup verification failed for shard {}: {}", shardId, backupId);
                        sendBackupVerificationFailureAlert(shardId, backupId);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Backup verification failed", e);
        }
    }
}
```

## Security Considerations

### 1. Network Security

#### TLS/SSL Configuration
```java
public class SecurityManager {
    private final SSLContext sslContext;
    private final KeyManagerFactory keyManagerFactory;
    private final TrustManagerFactory trustManagerFactory;
    
    public void setupSecurity() {
        // Configure SSL context
        setupSSLContext();
        
        // Configure network security
        setupNetworkSecurity();
        
        // Configure access control
        setupAccessControl();
    }
    
    private void setupSSLContext() {
        try {
            // Load keystore
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(new FileInputStream("keystore.jks"), "password".toCharArray());
            
            // Setup key manager
            keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, "password".toCharArray());
            
            // Load truststore
            KeyStore trustStore = KeyStore.getInstance("JKS");
            trustStore.load(new FileInputStream("truststore.jks"), "password".toCharArray());
            
            // Setup trust manager
            trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            
            // Create SSL context
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            
        } catch (Exception e) {
            throw new SecurityConfigurationException("Failed to setup SSL context", e);
        }
    }
    
    private void setupNetworkSecurity() {
        // Configure firewall rules
        configureFirewall();
        
        // Configure network segmentation
        configureNetworkSegmentation();
        
        // Configure VPN access
        configureVPNAccess();
    }
    
    private void configureFirewall() {
        // Allow only necessary ports
        FirewallRule coordinatorRule = FirewallRule.builder()
            .port(8080)
            .protocol("TCP")
            .source("coordinator-nodes")
            .action("ALLOW")
            .build();
        
        FirewallRule shardRule = FirewallRule.builder()
            .port(8081)
            .protocol("TCP")
            .source("shard-nodes")
            .action("ALLOW")
            .build();
        
        firewallManager.addRule(coordinatorRule);
        firewallManager.addRule(shardRule);
    }
}
```

### 2. Access Control

#### Authentication and Authorization
```java
public class AccessControlManager {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final Map<String, Permission> permissions = new ConcurrentHashMap<>();
    
    public void setupAccessControl() {
        // Setup roles
        setupRoles();
        
        // Setup permissions
        setupPermissions();
        
        // Setup users
        setupUsers();
    }
    
    private void setupRoles() {
        // Admin role
        Role adminRole = Role.builder()
            .name("admin")
            .permissions(Arrays.asList("read", "write", "admin"))
            .build();
        
        // Operator role
        Role operatorRole = Role.builder()
            .name("operator")
            .permissions(Arrays.asList("read", "write"))
            .build();
        
        // Read-only role
        Role readOnlyRole = Role.builder()
            .name("readonly")
            .permissions(Arrays.asList("read"))
            .build();
        
        roles.put("admin", adminRole);
        roles.put("operator", operatorRole);
        roles.put("readonly", readOnlyRole);
    }
    
    public boolean authenticate(String username, String password) {
        User user = users.get(username);
        if (user == null) {
            return false;
        }
        
        return user.verifyPassword(password);
    }
    
    public boolean authorize(String username, String permission) {
        User user = users.get(username);
        if (user == null) {
            return false;
        }
        
        Role role = roles.get(user.getRole());
        if (role == null) {
            return false;
        }
        
        return role.hasPermission(permission);
    }
}
```

### 3. Audit Logging

#### Comprehensive Audit Trail
```java
public class AuditLogger {
    private final Logger auditLogger = LoggerFactory.getLogger("audit");
    private final String nodeId;
    
    public AuditLogger(String nodeId) {
        this.nodeId = nodeId;
    }
    
    public void logAccess(String username, String operation, String resource, boolean success) {
        AuditEntry entry = AuditEntry.builder()
            .timestamp(System.currentTimeMillis())
            .nodeId(nodeId)
            .username(username)
            .operation(operation)
            .resource(resource)
            .success(success)
            .ipAddress(getClientIP())
            .userAgent(getUserAgent())
            .build();
        
        auditLogger.info("Access: {}", entry);
    }
    
    public void logConfigurationChange(String username, String parameter, String oldValue, String newValue) {
        AuditEntry entry = AuditEntry.builder()
            .timestamp(System.currentTimeMillis())
            .nodeId(nodeId)
            .username(username)
            .operation("configuration_change")
            .parameter(parameter)
            .oldValue(oldValue)
            .newValue(newValue)
            .build();
        
        auditLogger.info("Configuration change: {}", entry);
    }
    
    public void logSecurityEvent(String event, String details, SecurityLevel level) {
        AuditEntry entry = AuditEntry.builder()
            .timestamp(System.currentTimeMillis())
            .nodeId(nodeId)
            .operation("security_event")
            .event(event)
            .details(details)
            .securityLevel(level)
            .build();
        
        switch (level) {
            case LOW:
                auditLogger.info("Security event: {}", entry);
                break;
            case MEDIUM:
                auditLogger.warn("Security event: {}", entry);
                break;
            case HIGH:
                auditLogger.error("Security event: {}", entry);
                break;
        }
    }
}
```

## Operational Best Practices

### 1. Incident Response

#### Incident Management
```java
public class IncidentManager {
    private final Map<String, Incident> activeIncidents = new ConcurrentHashMap<>();
    private final NotificationService notificationService;
    private final EscalationManager escalationManager;
    
    public void handleIncident(Incident incident) {
        // Create incident record
        activeIncidents.put(incident.getId(), incident);
        
        // Send initial notification
        notificationService.sendIncidentNotification(incident);
        
        // Start incident response
        startIncidentResponse(incident);
    }
    
    private void startIncidentResponse(Incident incident) {
        switch (incident.getSeverity()) {
            case CRITICAL:
                handleCriticalIncident(incident);
                break;
            case HIGH:
                handleHighIncident(incident);
                break;
            case MEDIUM:
                handleMediumIncident(incident);
                break;
            case LOW:
                handleLowIncident(incident);
                break;
        }
    }
    
    private void handleCriticalIncident(Incident incident) {
        // Immediate escalation
        escalationManager.escalate(incident, EscalationLevel.IMMEDIATE);
        
        // Start incident response team
        startIncidentResponseTeam(incident);
        
        // Implement emergency procedures
        implementEmergencyProcedures(incident);
    }
    
    private void implementEmergencyProcedures(Incident incident) {
        switch (incident.getType()) {
            case SYSTEM_OUTAGE:
                implementSystemOutageProcedures();
                break;
            case DATA_CORRUPTION:
                implementDataCorruptionProcedures();
                break;
            case SECURITY_BREACH:
                implementSecurityBreachProcedures();
                break;
        }
    }
}
```

### 2. Performance Optimization

#### Performance Monitoring and Tuning
```java
public class PerformanceOptimizer {
    private final PerformanceMonitor performanceMonitor;
    private final Map<String, PerformanceThreshold> thresholds = new ConcurrentHashMap<>();
    
    public void setupPerformanceMonitoring() {
        // Setup performance thresholds
        setupThresholds();
        
        // Start performance monitoring
        startPerformanceMonitoring();
        
        // Setup automatic tuning
        setupAutomaticTuning();
    }
    
    private void setupThresholds() {
        // Latency thresholds
        thresholds.put("request_latency", new PerformanceThreshold(1000, 5000));
        thresholds.put("shard_latency", new PerformanceThreshold(500, 2000));
        
        // Throughput thresholds
        thresholds.put("request_throughput", new PerformanceThreshold(1000, 5000));
        thresholds.put("shard_throughput", new PerformanceThreshold(500, 2000));
        
        // Resource thresholds
        thresholds.put("cpu_usage", new PerformanceThreshold(70, 90));
        thresholds.put("memory_usage", new PerformanceThreshold(80, 95));
        thresholds.put("disk_usage", new PerformanceThreshold(80, 95));
    }
    
    public void optimizePerformance() {
        // Check current performance
        PerformanceMetrics metrics = performanceMonitor.getCurrentMetrics();
        
        // Identify bottlenecks
        List<PerformanceBottleneck> bottlenecks = identifyBottlenecks(metrics);
        
        // Apply optimizations
        for (PerformanceBottleneck bottleneck : bottlenecks) {
            applyOptimization(bottleneck);
        }
    }
    
    private void applyOptimization(PerformanceBottleneck bottleneck) {
        switch (bottleneck.getType()) {
            case HIGH_LATENCY:
                optimizeLatency(bottleneck);
                break;
            case LOW_THROUGHPUT:
                optimizeThroughput(bottleneck);
                break;
            case HIGH_RESOURCE_USAGE:
                optimizeResourceUsage(bottleneck);
                break;
        }
    }
    
    private void optimizeLatency(PerformanceBottleneck bottleneck) {
        String shardId = bottleneck.getShardId();
        
        // Check if shard is overloaded
        if (isShardOverloaded(shardId)) {
            // Rebalance load
            rebalanceShardLoad(shardId);
        }
        
        // Optimize cache settings
        optimizeCacheSettings(shardId);
        
        // Optimize network settings
        optimizeNetworkSettings(shardId);
    }
}
```

### 3. Capacity Planning

#### Capacity Management
```java
public class CapacityManager {
    private final CapacityPlanner capacityPlanner;
    private final ResourceMonitor resourceMonitor;
    private final ScalingManager scalingManager;
    
    public void setupCapacityPlanning() {
        // Setup capacity monitoring
        setupCapacityMonitoring();
        
        // Setup capacity alerts
        setupCapacityAlerts();
        
        // Setup automatic scaling
        setupAutomaticScaling();
    }
    
    private void setupCapacityMonitoring() {
        // Monitor current capacity
        resourceMonitor.monitorCPU();
        resourceMonitor.monitorMemory();
        resourceMonitor.monitorDisk();
        resourceMonitor.monitorNetwork();
        
        // Monitor business metrics
        resourceMonitor.monitorRequestRate();
        resourceMonitor.monitorDataGrowth();
        resourceMonitor.monitorUserGrowth();
    }
    
    public void planCapacity() {
        // Analyze current usage
        CapacityUsage currentUsage = analyzeCurrentUsage();
        
        // Predict future usage
        CapacityUsage predictedUsage = predictFutureUsage();
        
        // Calculate required capacity
        CapacityRequirements requirements = calculateCapacityRequirements(predictedUsage);
        
        // Plan capacity expansion
        planCapacityExpansion(requirements);
    }
    
    private CapacityUsage analyzeCurrentUsage() {
        return CapacityUsage.builder()
            .cpuUsage(getAverageCPUUsage())
            .memoryUsage(getAverageMemoryUsage())
            .diskUsage(getAverageDiskUsage())
            .networkUsage(getAverageNetworkUsage())
            .requestRate(getAverageRequestRate())
            .dataSize(getTotalDataSize())
            .userCount(getActiveUserCount())
            .build();
    }
    
    private CapacityUsage predictFutureUsage() {
        // Use historical data to predict future usage
        return capacityPlanner.predictUsage(
            getHistoricalData(),
            Duration.ofDays(30)
        );
    }
    
    private void planCapacityExpansion(CapacityRequirements requirements) {
        // Calculate required nodes
        int requiredNodes = calculateRequiredNodes(requirements);
        
        // Plan scaling timeline
        ScalingTimeline timeline = planScalingTimeline(requiredNodes);
        
        // Schedule scaling operations
        scheduleScalingOperations(timeline);
    }
}
```

## Summary

Deployment and operations are critical aspects of maintaining a distributed sharded counter system in production. This chapter covered comprehensive deployment strategies, monitoring and observability, maintenance procedures, security considerations, and operational best practices.

Key takeaways from this chapter:
- **Deployment Strategies**: Physical infrastructure, container-based, and cloud-native deployments
- **Configuration Management**: Centralized configuration with environment-specific settings
- **Monitoring and Observability**: Metrics collection, structured logging, distributed tracing, and health monitoring
- **Maintenance Procedures**: Rolling updates, scaling operations, and backup/restore procedures
- **Security Considerations**: Network security, access control, and audit logging
- **Operational Best Practices**: Incident response, performance optimization, and capacity planning

In the final chapter, we'll explore real-world use cases and best practices for implementing distributed sharded counters in various application domains.

---

*This chapter covers deployment, monitoring, and operational best practices for distributed sharded counters. The final chapter will present real-world use cases and best practices.* 