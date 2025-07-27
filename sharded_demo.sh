#!/bin/bash

# Sharded Counter Demo Script
# Demonstrates deterministic routing with consistent hashing

echo "=== Sharded Counter Demo ==="
echo "This demo shows deterministic routing using consistent hashing"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_header() {
    echo -e "${BLUE}[HEADER]${NC} $1"
}

# Check if Java is installed
if ! command -v java &> /dev/null; then
    print_error "Java is not installed. Please install Java 11 or higher."
    exit 1
fi

# Check if Gradle is available
if [ ! -f "./gradlew" ]; then
    print_error "Gradle wrapper not found. Please run this script from the project root."
    exit 1
fi

print_header "Building the project..."
./gradlew build -q
if [ $? -ne 0 ]; then
    print_error "Build failed!"
    exit 1
fi
print_status "Build successful!"

# Create logs directory
mkdir -p logs

print_header "Starting Shard Nodes..."

# Start shard nodes in background
print_status "Starting Shard Node 1 on port 8081..."
java -cp build/libs/DistributedCounter-1.0.0.jar com.distributedcounter.ShardNode 8081 ./data/shard1 > logs/shard1.log 2>&1 &
SHARD1_PID=$!

print_status "Starting Shard Node 2 on port 8082..."
java -cp build/libs/DistributedCounter-1.0.0.jar com.distributedcounter.ShardNode 8082 ./data/shard2 > logs/shard2.log 2>&1 &
SHARD2_PID=$!

print_status "Starting Shard Node 3 on port 8083..."
java -cp build/libs/DistributedCounter-1.0.0.jar com.distributedcounter.ShardNode 8083 ./data/shard3 > logs/shard3.log 2>&1 &
SHARD3_PID=$!

# Wait for shards to start
sleep 3

print_header "Starting Sharded Counter Coordinator..."
print_status "Coordinator will use consistent hashing for deterministic routing"
java -cp build/libs/DistributedCounter-1.0.0.jar com.distributedcounter.ShardedCounterCoordinator 8080 localhost:8081 localhost:8082 localhost:8083 > logs/coordinator.log 2>&1 &
COORDINATOR_PID=$!

# Wait for coordinator to start
sleep 3

print_header "Running Sharded Counter Client Demo..."

# Run the client demo
java -cp build/libs/DistributedCounter-1.0.0.jar com.distributedcounter.client.ShardedCounterClient http://localhost:8080

print_header "Demo completed!"

print_status "Cleaning up processes..."
kill $COORDINATOR_PID 2>/dev/null
kill $SHARD1_PID 2>/dev/null
kill $SHARD2_PID 2>/dev/null
kill $SHARD3_PID 2>/dev/null

print_status "Demo script finished!"
echo ""
print_header "Key Points Demonstrated:"
echo "1. Deterministic routing using consistent hashing"
echo "2. Same counter always goes to same shard"
echo "3. Reads aggregate from all shards"
echo "4. Fault tolerance across multiple shards"
echo ""
print_header "Logs available in:"
echo "- Coordinator: logs/coordinator.log"
echo "- Shard 1: logs/shard1.log"
echo "- Shard 2: logs/shard2.log"
echo "- Shard 3: logs/shard3.log" 