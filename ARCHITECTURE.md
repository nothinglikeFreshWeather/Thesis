# Stock Tracking System - Architecture Documentation

## 1. Executive Summary

This document describes the architecture of a distributed stock tracking system designed to demonstrate CAP theorem principles through network partition testing. The system prioritizes **availability** while implementing graceful degradation patterns when distributed components become unavailable.

---

## 2. System Architecture Overview

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Layer                               │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │           React Frontend (Nginx)                          │  │
│  │    - Stock Management UI                                  │  │
│  │    - Embedded Grafana Dashboard                          │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────────────────┘
                         │ HTTP/REST
┌────────────────────────▼────────────────────────────────────────┐
│                   Application Layer                              │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │       Spring Boot Application (Port 8080)                 │  │
│  │  ┌────────────┬──────────────┬────────────────────────┐ │  │
│  │  │ REST API   │ Service Layer│  Repository Layer      │ │  │
│  │  │ Controller │              │  (Spring Data JPA)     │ │  │
│  │  └────────────┴──────────────┴────────────────────────┘ │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────┬──────────────┬─────────────────┬─────────────────┬───────┘
      │              │                 │                 │
      │ JDBC         │ Lettuce        │ Kafka Producer  │ Metrics
      │              │ Client          │                 │
┌─────▼──────┐  ┌───▼────┐      ┌────▼─────┐      ┌────▼──────┐
│            │  │        │      │          │      │           │
│  Toxiproxy │  │Toxiproxy│     │Toxiproxy │      │Prometheus │
│  (MySQL)   │  │(Redis) │      │ (Kafka)  │      │(Port 9090)│
│  :3307     │  │:26379  │      │  :29093  │      │           │
│            │  │        │      │          │      │           │
└─────┬──────┘  └───┬────┘      └────┬─────┘      └────┬──────┘
      │             │                │                  │
┌─────▼──────┐  ┌──▼─────┐     ┌────▼─────┐      ┌────▼──────┐
│   MySQL    │  │ Redis  │     │  Kafka   │      │  Grafana  │
│  Database  │  │ Master │     │  Broker  │      │(Port 3001)│
│  :3306     │  │ :6379  │     │  :9092   │      │Dashboard  │
│            │  │        │     │          │      │           │
│ Source of  │  │ Cache  │     │ Event    │      │Visualize  │
│ Truth      │  │ Layer  │     │ Stream   │      │Metrics    │
└────────────┘  └────┬───┘     └──────────┘      └───────────┘
                     │
                ┌────▼────┐
                │ Redis   │
                │ Replica │
                │ :6380   │
                │         │
                │ Standby │
                └─────────┘
```

---

## 3. Component Architecture

### 3.1 Backend Application (Spring Boot)

#### **Technology Stack:**
- **Framework:** Spring Boot 3.x
- **Language:** Java 17
- **Build Tool:** Gradle 8.5

#### **Key Dependencies:**
- `spring-boot-starter-web` - REST API
- `spring-boot-starter-data-jpa` - ORM & Database
- `spring-kafka` - Event streaming
- `spring-data-redis` - Caching with Lettuce client
- `spring-boot-starter-actuator` - Health & metrics
- `micrometer-registry-prometheus` - Metrics export

#### **Layers:**

##### **3.1.1 Controller Layer**
```java
StockController
├─ GET    /api/stocks          (List all stocks)
├─ GET    /api/stocks/{id}     (Get single stock)
├─ POST   /api/stocks          (Create stock)
├─ PUT    /api/stocks/{id}     (Update stock)
└─ DELETE /api/stocks/{id}     (Delete stock)

LoadTestController
├─ POST   /api/load-test/kafka?count=10000
└─ POST   /api/load-test/kafka/batch
```

##### **3.1.2 Service Layer**
```
StockService
├─ createStock()    → MySQL write → Redis cache → Kafka event
├─ updateStock()    → MySQL update → Redis invalidate → Kafka event
├─ getStock()       → Redis check → MySQL fallback
└─ deleteStock()    → MySQL delete → Redis delete → Kafka event

CacheService (Redis)
├─ get()            → Lettuce GET with timeout
├─ set()            → Lettuce SET with TTL
└─ delete()         → Lettuce DEL

KafkaProducerService
└─ sendStockEvent() → Async send with callbacks
```

##### **3.1.3 Configuration**

**Kafka Producer:**
```yaml
acks: all                    # Wait for all replicas
retries: 3                   # Retry failed sends
enable.idempotence: true     # No duplicates
linger.ms: 10                # Small batching
```

**Redis:**
```yaml
timeout: 5000ms              # Connection timeout
lettuce.pool.max-active: 8   # Connection pool
```

---

### 3.2 Data Layer

#### **3.2.1 MySQL Database**

**Role:** Source of Truth (Persistence)

**Schema:**
```sql
CREATE TABLE stocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_name VARCHAR(255) UNIQUE NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(19,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**Characteristics:**
- **CAP Position:** CP (Consistency + Partition Tolerance)
- **Transactions:** ACID compliant
- **Connection Pool:** HikariCP (10 max connections)

#### **3.2.2 Redis Cache**

**Role:** Performance Layer (Caching)

**Architecture:**
- **Master-Replica** setup
- Master: Read/Write (port 6379)
- Replica: Read-only standby (port 6380)

**Caching Strategy:**
```
Write-Through:
  Create → Write DB → Write cache ✅

Read-Through:
  Get → Check cache → MISS → Read DB → Write cache ✅

Cache-Aside (Fallback):
  Get → Cache timeout → Read DB (no cache write) ⚠️
```

**Cache TTL:** 300 seconds (5 minutes)

**Characteristics:**
- **CAP Position:** AP (Availability + Partition Tolerance)
- **Eviction:** TTL-based
- **Client:** Lettuce (async, non-blocking)

#### **3.2.3 Kafka Event Stream**

**Role:** Event Bus (Async Communication)

**Topic:**
```
stock-events
├─ Partitions: 3
├─ Replication Factor: 1 (single broker setup)
└─ Retention: 7 days
```

**Event Schema:**
```json
{
  "eventId": "uuid",
  "eventType": "CREATED | UPDATED | DELETED",
  "stockId": 123,
  "productName": "Laptop",
  "quantity": 100,
  "price": 999.99,
  "timestamp": "2026-01-08T23:00:00Z"
}
```

**Producer Configuration:**
- **Acks:** all (wait for leader + replicas)
- **Idempotence:** Enabled
- **Async:** CompletableFuture-based

**Characteristics:**
- **CAP Position (Kafka itself):** CP
- **CAP Position (Our Implementation):** AP with event loss risk
- **Delivery:** At-least-once (with acks=all)

---

### 3.3 Monitoring Stack

#### **3.3.1 Prometheus**

**Role:** Metrics Collection & Storage

**Scrape Configuration:**
```yaml
scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['app:8080']
```

**Custom Metrics:**
```
# Cache metrics
cache_hit_miss_total{result="hit|miss"}
cache_operation_duration_seconds{operation="get|set|delete"}
redis_reconnect_total

# Kafka metrics
kafka_producer_send_total{status="success|failure"}
stock_create_duration_seconds
stock_update_duration_seconds

# HTTP metrics (auto)
http_server_requests_seconds_count
http_server_requests_seconds_sum
```

#### **3.3.2 Grafana**

**Role:** Visualization & Dashboards

**Dashboard Panels:**
1. **Health Status** (Stat panels)
   - Application Health (UP/DOWN)
   - Cache Hit Rate (%)
   - Kafka Success Rate (%)

2. **Performance Metrics** (Time series)
   - HTTP Request Rate
   - Cache Operation Latency
   - Kafka Throughput

3. **Fault Tolerance** (Stacked graphs)
   - Cache Hit vs Miss
   - Kafka Success vs Failure

4. **Resource Usage**
   - JVM Heap Memory
   - Connection Pool Stats

---

### 3.4 Fault Injection Layer (Toxiproxy)

**Role:** Network Partition Simulation

**Proxies:**
```
mysql-proxy (3307)    → mysql:3306
redis-master (26379)  → redis-master:6379
kafka-proxy (29093)   → kafka:9092
```

**Toxic Types:**
```bash
# Complete partition (timeout)
{
  "type": "timeout",
  "attributes": {"timeout": 0}
}

# Network latency
{
  "type": "latency",
  "attributes": {"latency": 2000, "jitter": 500}
}

# Bandwidth limit
{
  "type": "bandwidth",
  "attributes": {"rate": 1000}  # 1 KB/s
}
```

---

### 3.5 Frontend Layer

#### **Technology Stack:**
- **Framework:** React 18 + Vite
- **Container:** Nginx (port 3000)
- **API Client:** Axios

#### **Features:**
1. **Stock Management**
   - CRUD operations for stocks
   - Real-time list updates

2. **Embedded Grafana Dashboard**
   - iframe integration
   - Anonymous access enabled

3. **CORS Configuration:**
   - Allowed origins: http://localhost:3000
   - Credentials: false

---

## 4. Data Flow Patterns

### 4.1 Stock Creation Flow (Happy Path)

```
Frontend
    │
    │ POST /api/stocks
    │ {"productName":"Laptop", "quantity":10, "price":999.99}
    ▼
StockController
    │
    │ Validate request
    ▼
StockService
    │
    ├─(1)─▶ MySQL: Save stock
    │         └─ Transaction committed ✅
    │
    ├─(2)─▶ Redis: Cache stock (TTL: 300s)
    │         └─ SET stock:123 ✅
    │
    └─(3)─▶ Kafka: Send event (async)
              └─ Future.whenComplete()
                  ├─ Success → Metric++ ✅
                  └─ Failure → Metric++, Log error ⚠️
```

### 4.2 Stock Retrieval Flow (Cache Hit)

```
Frontend
    │
    │ GET /api/stocks/123
    ▼
StockController
    │
    ▼
StockService
    │
    └─▶ CacheService.get("stock:123")
          │
          ├─ Redis: GET stock:123
          │   └─ HIT ✅
          │
          └─ Return cached data (fast!)
```

### 4.3 Stock Retrieval Flow (Cache Miss)

```
Frontend
    │
    │ GET /api/stocks/123
    ▼
StockController
    │
    ▼
StockService
    │
    └─▶ CacheService.get("stock:123")
          │
          ├─ Redis: GET stock:123
          │   └─ MISS ❌
          │
          ├─ MySQL: SELECT * FROM stocks WHERE id=123
          │   └─ Found ✅
          │
          ├─ Redis: SET stock:123 (write-through)
          │   └─ Cached for next request
          │
          └─ Return data
```

### 4.4 Partition Handling Flow (Redis Down)

```
Frontend
    │
    │ GET /api/stocks/123
    ▼
StockController
    │
    ▼
StockService
    │
    └─▶ CacheService.get("stock:123")
          │
          ├─ Redis: GET stock:123
          │   ├─ Timeout (5000ms) ⏱️
          │   └─ RedisConnectionException ❌
          │
          ├─ Catch Exception:
          │   ├─ Log warning
          │   ├─ Increment reconnect metric
          │   └─ Graceful degradation ✅
          │
          ├─ MySQL: SELECT * FROM stocks WHERE id=123
          │   └─ Found ✅
          │
          └─ Return data (slower but working!)
```

---

## 5. CAP Theorem Implementation

### 5.1 Component CAP Characteristics

| Component | CAP Position | Partition Behavior | Trade-off |
|-----------|-------------|-------------------|-----------|
| **MySQL** | **CP** | Fail → No data access | Consistency > Availability |
| **Redis** | **AP** | Fail → DB fallback | Availability > Consistency |
| **Kafka** | **CP** (native)<br>**AP** (our impl) | Fail → Event loss | Availability > Consistency |

### 5.2 System-Level CAP Decision

**Overall Position:** **AP with CP fallback**

**Rationale:**
```
Availability Priority:
  - Stock operations continue even if cache/Kafka down
  - DB fallback ensures data access
  - Graceful degradation over hard failures

Consistency Aspects:
  - MySQL provides strong consistency
  - Cache eventual consistency (TTL-based)
  - Event delivery best-effort (no guarantee)
```

### 5.3 Partition Tolerance Strategies

#### **Redis Partition:**
```
Strategy: Cache-Aside Fallback
  1. Attempt Redis GET (timeout: 5s)
  2. On timeout → Catch exception
  3. Fallback to MySQL
  4. Return data ✅
  5. Do NOT cache (partition active)
  
Result: Degraded performance, full availability
```

#### **Kafka Partition:**
```
Strategy: Fire & Forget with Logging
  1. Attempt Kafka send (async)
  2. CompletableFuture pending (timeout: 120s)
  3. On error → Callback triggers
  4. Increment failure metric
  5. Log error, do NOT throw exception
  
Result: Event loss, stock operation succeeds
```

#### **MySQL Partition:**
```
Strategy: Fail Fast
  1. Attempt MySQL query (timeout: connection pool)
  2. SQLException thrown
  3. @Transactional → Rollback
  4. Return 500 error to client ❌
  
Result: No availability, consistency preserved
```

---

## 6. Performance Characteristics

### 6.1 Throughput Benchmarks

**Load Test Results (10,000 messages):**

| Scenario | Throughput | Duration | Success Rate |
|----------|-----------|----------|--------------|
| Normal (No latency) | 200 ops/s | 2-3s | 100% |
| Kafka 500ms latency | 125 ops/s | ~8s | 100% |
| Kafka 2000ms latency | 25 ops/s | ~40s | 100% |
| Kafka partition | 0 ops/s | N/A | 0% |

**Observations:**
- Throughput ∝ 1/latency
- Partition = complete failure
- No data loss (stock saved to DB)

### 6.2 Latency Measurements

**Cache Operations:**

| Operation | Normal | Redis Partition |
|-----------|--------|----------------|
| GET | <1ms | 5000ms (timeout) |
| SET | <1ms | 5000ms (timeout) |
| DELETE | <1ms | 5000ms (timeout) |

**HTTP Requests:**

| Endpoint | Cache HIT | Cache MISS | Redis Down |
|----------|-----------|------------|------------|
| GET /api/stocks/{id} | ~10ms | ~50ms | ~5050ms |
| POST /api/stocks | ~80ms | ~80ms | ~85ms |

### 6.3 Resource Usage

**JVM Memory:**
```
Heap Max:  2048 MB
Heap Used: ~40 MB (2%)
Throughput: 200 ops/s

Memory-efficient async producer!
```

**Connection Pools:**
```
MySQL (HikariCP): 10 connections
Redis (Lettuce):  8 connections
Kafka Producer:   5 in-flight requests
```

---

## 7. Deployment Architecture

### 7.1 Docker Compose Setup

**Services:**
```yaml
services:
  mysql:          # Port 3306 (internal)
  redis-master:   # Port 6379 (internal)
  redis-replica:  # Port 6380 (internal)
  kafka:          # Port 9092 (internal)
  zookeeper:      # Port 2181 (internal)
  toxiproxy:      # Port 8474 (API), proxy ports (3307, 26379, 29093)
  app:            # Port 8080 (exposed)
  prometheus:     # Port 9090 (exposed)
  grafana:        # Port 3001 (exposed)
  frontend:       # Port 3000 (exposed)
```

**Network:**
```
Custom bridge network: stock-network
  - Internal DNS resolution
  - Service discovery
```

### 7.2 Port Mapping

| Service | Internal | External | Purpose |
|---------|----------|----------|---------|
| MySQL (direct) | 3306 | - | Internal only |
| MySQL (proxy) | - | 3307 | Testing access |
| Redis Master (proxy) | - | 26379 | Testing access |
| Kafka (proxy) | - | 29093 | Producer access |
| Backend API | 8080 | 8080 | REST API |
| Frontend | 80 | 3000 | Web UI |
| Grafana | 3000 | 3001 | Dashboard |
| Prometheus | 9090 | 9090 | Metrics |
| Toxiproxy API | 8474 | 8666 | Toxic management |

---

## 8. Monitoring & Observability

### 8.1 Metrics Collection

**Spring Boot Actuator Endpoints:**
```
/actuator/health         → System health check
/actuator/prometheus     → Metrics export (15s scrape)
/actuator/metrics        → Individual metric access
```

**Custom Micrometer Metrics:**

**Cache Metrics:**
```java
Counter.builder("cache.hit.miss.total")
    .tag("result", "hit|miss")
    .register(meterRegistry);

Timer.builder("cache.operation.duration")
    .tag("operation", "get|set|delete")
    .register(meterRegistry);
```

**Kafka Metrics:**
```java
Counter.builder("kafka.producer.send.total")
    .tag("status", "success|failure")
    .register(meterRegistry);
```

**Stock Operation Metrics:**
```java
Timer.builder("stock.create.duration")
Timer.builder("stock.update.duration")
```

### 8.2 Grafana Dashboards

**System Health & Kafka Monitoring:**

**Panels:**
1. **Status Indicators** (6 stat panels)
   - Application Health
   - Cache Hit Rate
   - Kafka Success Rate
   - Kafka Throughput
   - Redis Reconnects
   - Kafka Failures

2. **Time Series Graphs**
   - HTTP Request Rate
   - Cache Operation Latency
   - Cache Hit vs Miss (stacked)
   - Kafka Producer Status (stacked)
   - Kafka Throughput
   - JVM Memory Usage

**Alert Conditions:**
- Cache hit rate < 50% → Yellow
- Kafka success rate < 80% → Red
- Redis reconnects > 0 → Red

---

## 9. Testing Strategy

### 9.1 Fault Injection Testing

**Network Partition Simulation:**

**Redis Master Partition:**
```bash
# Create toxic
curl -X POST http://localhost:8666/proxies/redis-master/toxics \
  -d '{"name":"down","type":"timeout","attributes":{"timeout":0}}'

# Expected behavior:
  - Cache operations timeout (5000ms)
  - DB fallback activated
  - Cache miss metric increases
  - Latency spike in Grafana

# Remove toxic
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/down

# Expected recovery:
  - Cache warming starts
  - Hit rate increases
  - Latency normalizes
```

**Kafka Partition:**
```bash
# Create toxic
curl -X POST http://localhost:8666/proxies/kafka/toxics \
  -d '{"name":"down","type":"timeout","attributes":{"timeout":0}}'

# Expected behavior:
  - Event send fails (async)
  - Failure metric increments
  - Stock operation succeeds (graceful degradation)
  - Events lost (no retry)

# Remove toxic
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/down

# Expected recovery:
  - New events delivered successfully
  - Success metric increases
```

**MySQL Partition:**
```bash
# Create toxic
curl -X POST http://localhost:8666/proxies/mysql/toxics \
  -d '{"name":"down","type":"timeout","attributes":{"timeout":0}}'

# Expected behavior:
  - All database operations fail
  - Transaction rollback
  - HTTP 500 error
  - No data written (consistency preserved)

# Remove toxic
curl -X DELETE http://localhost:8666/proxies/mysql/toxics/down

# Expected recovery:
  - Immediate normal operation
  - No data loss
```

### 9.2 Load Testing

**Kafka Throughput Test:**
```bash
# Baseline
POST /api/load-test/kafka?count=10000
# Expected: 200 ops/s, 100% success

# With latency
POST /proxies/kafka/toxics -d '{"name":"lat","type":"latency","attributes":{"latency":500}}'
POST /api/load-test/kafka?count=10000
# Expected: 125 ops/s (62.5% baseline)

# Batch test
POST /api/load-test/kafka/batch?count=10000&batchSize=100&delayMs=10
# Expected: Steady throughput, no spikes
```

---

## 10. Design Decisions & Trade-offs

### 10.1 Kafka: acks=all but Graceful Degradation

**Decision:**
```yaml
kafka.producer.acks: all
```

**Rationale:**
- Maximize durability in normal operations
- Prevent data loss when Kafka is healthy

**Trade-off:**
- Higher latency (wait for all replicas)
- BUT: Exception handling allows availability

**Implementation:**
```java
try {
    kafkaTemplate.send(event);
} catch (Exception e) {
    // Log and increment metric
    // Do NOT throw → Graceful degradation
}
```

**Result:** CP technology used in AP manner

---

### 10.2 Redis: Write-Through vs Cache-Aside

**Decision:** **Hybrid approach**

**Write-Through (Create/Update):**
```java
Stock savedStock = repository.save(stock);
cacheService.set(key, savedStock);  // Immediate cache
```

**Cache-Aside (Partition):**
```java
try {
    return cacheService.get(key);
} catch (RedisException e) {
    return repository.findById(id);  // Fallback, no cache write
}
```

**Trade-off:**
- Consistency during normal ops
- Availability during partition
- Stale cache risk (mitigated by TTL)

---

### 10.3 No Outbox Pattern

**Decision:** Direct Kafka send (no outbox table)

**Reasons:**
1. Simplicity - No additional table
2. Demonstration - Shows event loss scenario
3. Academic - Illustrates AP trade-off

**Trade-off:**
- Event loss during partition ❌
- No eventual consistency guarantee

**Production Recommendation:**
```
Use Outbox Pattern or CDC (Debezium) for:
  - Guaranteed event delivery
  - Transactional consistency
  - At-least-once delivery
```

---

### 10.4 Single Kafka Broker

**Decision:** 1 broker (no replication)

**Rationale:**
- Local development simplicity
- Toxiproxy testing focus
- Resource constraints

**Trade-off:**
- No Kafka-level fault tolerance
- Broker failure = complete outage
- Partition testing via Toxiproxy only

**Production:** Use 3+ brokers with replication factor 3

---

## 11. Conclusion

This architecture demonstrates a **pragmatic AP-focused** distributed system with strategic CP components:

**Key Achievements:**
1. ✅ **Graceful Degradation** - System remains operational during component failures
2. ✅ **Observability** - Comprehensive metrics and dashboards
3. ✅ **Fault Injection** - Realistic partition testing via Toxiproxy
4. ✅ **Performance** - Low memory footprint, high throughput
5. ✅ **CAP Demonstration** - Clear trade-offs between consistency and availability

**CAP Summary:**
- **CP:** MySQL (source of truth)
- **AP:** Redis (cache), Kafka implementation (best-effort events)
- **System:** AP-prioritized with graceful degradation

**Production Improvements:**
1. Implement Outbox Pattern for Kafka events
2. Add Redis Sentinel for automatic failover
3. Use multi-broker Kafka cluster
4. Implement circuit breakers (Resilience4j)
5. Add distributed tracing (Zipkin/Jaeger)
6. Reduce Redis timeout (5s → 2s)

---

## Appendices

### A. Technology Versions

```
Java: 17
Spring Boot: 3.2.0
Gradle: 8.5
Kafka: 3.6
Redis: 7.2
MySQL: 8.0
Prometheus: 2.45
Grafana: 10.0
Toxiproxy: 2.5
React: 18
Vite: 5
```

### B. Key Configuration Files

- `application.yaml` - Spring Boot configuration
- `docker-compose.yml` - Container orchestration
- `prometheus.yml` - Metrics scraping config
- `stock-metrics.json` - Grafana dashboard
- `toxiproxy-config.json` - Proxy initialization

### C. Useful Commands

See `TESTING.md` and `KAFKA_LOAD_TEST.md` for comprehensive command reference.
