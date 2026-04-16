# Stock Tracking System - Architecture Documentation

## 1. Executive Summary

This document describes the architecture of a distributed stock tracking system designed to demonstrate CAP theorem principles through network partition testing. The system prioritizes **availability** while implementing graceful degradation patterns when distributed components become unavailable.

---

## 2. System Architecture Overview

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              User Layer                                 │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │              React Frontend (Nginx Reverse Proxy)                  │ │
│  │    - Stock Management UI      - IoT Sensor Dashboard (SSE)        │ │
│  │    - Embedded Grafana (Stock + IoT dashboards toggle)             │ │
│  └───────────────────────────┬───────────────────────────────────────┘ │
└──────────────────────────────┼─────────────────────────────────────────┘
                               │  Nginx /api/ → app:8080 (Reverse Proxy)
┌──────────────────────────────▼─────────────────────────────────────────┐
│                        Application Layer                                │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │            Spring Boot Application (Port 8080)                    │  │
│  │  ┌──────────────┬─────────────┬──────────────┬──────────────┐  │  │
│  │  │ StockCtrl    │ SensorCtrl  │ LoadTestCtrl │ Rate Limit   │  │  │
│  │  │ REST CRUD    │ REST + SSE  │ Kafka Bench  │ Interceptor  │  │  │
│  │  └──────┬───────┴──────┬──────┴──────┬───────┴──────────────┘  │  │
│  │  ┌──────▼──────────────▼─────────────▼──────────────────────┐  │  │
│  │  │ StockService │ WarehouseMetricsListener │ OutboxScheduler │  │  │
│  │  │ CacheService │ KafkaProducerService     │ RateLimiting    │  │  │
│  │  └──────────────┴──────────────────────────┴────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────┘  │
└─────┬──────────────┬─────────────────┬─────────────────┬──────────────┘
      │              │                 │                 │
      │ JDBC         │ Lettuce         │ Kafka           │ Actuator
      │              │ Client          │ Producer +      │ /prometheus
      │              │                 │ Consumer        │
┌─────▼──────┐  ┌───▼────┐      ┌─────▼────┐      ┌─────▼─────┐
│ Toxiproxy  │  │Toxiproxy│     │ Toxiproxy │      │Prometheus │
│ (MySQL)    │  │(Redis)  │     │ (Kafka)   │      │(Port 9090)│
│ :3307      │  │:26379   │     │ :29093    │      │           │
└─────┬──────┘  └───┬────┘      └─────┬────┘      └─────┬─────┘
      │             │                 │                  │
┌─────▼──────┐  ┌──▼─────┐     ┌─────▼────┐      ┌─────▼─────┐
│   MySQL    │  │ Redis  │     │  Kafka   │      │  Grafana  │
│  Database  │  │ Master │     │ (KRaft)  │      │(Port 3001)│
│  :3306     │  │ :6379  │     │  :9092   │      │ 2 Dash-   │
│            │  │        │     │          │      │ boards    │
│ Source of  │  │ Cache +│     │ Topics:  │      │ - Stock   │
│ Truth +    │  │ Sensor │     │ stock-   │      │ - IoT     │
│ Outbox     │  │ Data   │     │ events   │      │ Sensors   │
└────────────┘  └────┬───┘     │ warehouse│      └───────────┘
                     │         │ -metrics │
                ┌────▼────┐    └─────▲────┘
                │ Redis   │          │
                │ Replica │    ┌─────┴──────────┐
                │ :6380   │    │ IoT Simulator  │
                │ Standby │    │ (Python)       │
                └─────────┘    │ Sends temp     │
                               │ every 1s       │
                               └────────────────┘
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

SensorController
├─ GET    /api/sensors/stream/{deviceId}         (SSE real-time stream)
├─ GET    /api/sensors/current/{deviceId}        (Latest reading from Redis)
├─ GET    /api/sensors/alerts/{deviceId}         (Alert list)
├─ GET    /api/sensors/alerts/{deviceId}/clear   (Clear alerts)
└─ GET    /api/sensors/health/{deviceId}         (Sensor health)

LoadTestController
├─ POST   /api/load-test/kafka?count=10000
└─ POST   /api/load-test/kafka/batch
```

##### **3.1.2 Service Layer**
```
StockService
├─ createStock()    → MySQL write → Outbox insert → Redis cache
├─ updateStock()    → MySQL update → Outbox insert → Redis invalidate
├─ getStock()       → Redis check → MySQL fallback
└─ deleteStock()    → MySQL delete → Outbox insert → Redis delete

CacheService (Redis)
├─ get()            → Lettuce GET with timeout
├─ set()            → Lettuce SET with TTL
└─ delete()         → Lettuce DEL

KafkaProducerService
├─ sendStockEvent()     → Async send with callbacks
└─ sendStockEventSync() → Synchronous send (used by OutboxScheduler)

WarehouseMetricsListener (Kafka Consumer)
├─ handleSensorData()          → Receive from 'warehouse-metrics' topic
├─ storeSensorDataInRedis()    → SET sensor:{deviceId} (TTL: 5 min)
└─ generateTemperatureAlert()  → LPUSH alert:{deviceId} if temp > 30°C

OutboxScheduler (Transactional Outbox)
├─ processOutboxEvents()  → Poll PENDING events every 5s
├─ processEvent()         → Send to Kafka → mark SENT / FAILED
└─ Max retries: 5         → After 5 failures → status = FAILED

RateLimitingService (Token Bucket)
└─ tryConsume()  → Bucket4j global limiter (50 req/s default)
```

##### **3.1.3 Configuration**

**Kafka Producer:**
```yaml
acks: all                    # Wait for all replicas
retries: 3                   # Retry failed sends
enable.idempotence: true     # No duplicates
linger.ms: 10                # Small batching
```

**Kafka Consumer (warehouse-metrics):**
```yaml
value-deserializer: JsonDeserializer
default-type: SensorDataDto
auto-offset-reset: earliest
use-type-info-headers: false  # Python producer sends no type headers
concurrency: 3
ack-mode: RECORD              # Single-record processing (not batch)
```

**Redis:**
```yaml
timeout: 5000ms              # Connection timeout
lettuce.pool.max-active: 8   # Connection pool
```

**Rate Limiting (Bucket4j):**
```yaml
rate-limit.global:
  capacity: 50               # Max bucket size
  refill-tokens: 50          # Tokens per refill
  refill-duration-ms: 1000   # Refill interval
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

#### **3.2.3 Kafka Event Stream (KRaft Mode — No Zookeeper)**

**Role:** Event Bus (Async Communication)

**Topics:**
```
stock-events              (Stock CRUD events → Outbox-based delivery)
├─ Partitions: 3
├─ Replication Factor: 1 (single broker setup)
└─ Retention: 7 days

warehouse-metrics         (IoT sensor data → consumed by WarehouseMetricsListener)
├─ Partitions: 3
├─ Replication Factor: 1
├─ Producer: Python IoT Simulator (JSON, no type headers)
└─ Consumer Group: warehouse-metrics-group
```

**Stock Event Schema:**
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

**Sensor Data Schema (from IoT Simulator):**
```json
{
  "cihazId": "depo-sensor-1",
  "sicaklik": 24.5,
  "zaman": "2026-04-09T12:03:10.143759Z"
}
```

**Producer Configuration:**
- **Acks:** all (wait for leader + replicas)
- **Idempotence:** Enabled
- **Async:** CompletableFuture-based

**Characteristics:**
- **CAP Position (Kafka itself):** CP
- **Stock Events:** Reliable delivery via Outbox Pattern (at-least-once)
- **Sensor Data:** Direct Kafka produce from Python (at-most-once from simulator side)

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
- **Container:** Nginx reverse proxy (port 3000 → internal 80)
- **API Client:** Native `fetch` + `EventSource` (no Axios)
- **Charting:** Native HTML5 Canvas API (zero dependencies)

#### **Features:**
1. **📦 Stock Management**
   - CRUD operations for stocks
   - Real-time list updates

2. **🌡️ IoT Sensor Dashboard** (NEW)
   - Dynamic device ID input with connect/disconnect
   - Live SSE (Server-Sent Events) stream for real-time data
   - Stat cards: Temperature (color-coded), Status, Alert count, Data point count
   - Canvas-based temperature timeline chart (last 60 readings)
   - Alert management panel with clear function

3. **📊 Metrics Dashboard**
   - Dual Grafana dashboard toggle (Stock Metrics ↔ IoT Sensor Metrics)
   - iframe integration, anonymous access

#### **Nginx Reverse Proxy (`nginx.conf`):**
```nginx
location /api/ {
    proxy_pass http://app:8080/api/;  # Route to Spring Boot
    proxy_buffering off;              # SSE support
    proxy_cache off;
    proxy_read_timeout 300s;          # 5 min for SSE
}
```
This eliminates CORS entirely in Docker — all requests share the same origin.

#### **API Configuration:**
- All API URLs use **relative paths** (`/api/...`) — no hardcoded `localhost`
- Docker: Nginx proxies `/api/` → `app:8080`
- Dev mode: Vite proxy (`vite.config.js`) forwards `/api` → `localhost:8080`

#### **CORS Configuration (Backup for non-proxy access):**
- Allowed origins: `http://localhost:3000`, `http://127.0.0.1:3000`
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

**Services (11 containers):**
```yaml
services:
  mysql:          # Port 3306 (internal) — Source of truth + Outbox table
  redis-master:   # Port 6379 (internal) — Cache + Sensor data store
  redis-replica:  # Port 6380 (internal) — Read-only standby
  kafka:          # Port 9092 (internal) — KRaft mode (no Zookeeper)
  toxiproxy:      # Port 8474 (API), proxy ports (3307, 26379, 29093)
  iot-simulator:  # Python — sends temp data to warehouse-metrics every 1s
  app:            # Port 8080 (exposed) — Spring Boot backend
  prometheus:     # Port 9090 (exposed) — Metrics scraping
  grafana:        # Port 3001 (exposed) — 2 dashboards (Stock + IoT)
  frontend:       # Port 3000 (exposed) — Nginx + React SPA
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

### 10.3 Transactional Outbox Pattern (Implemented)

**Decision:** Outbox table for reliable Kafka delivery

**Implementation:**
```
StockService.create/update/delete()
  │
  ├─ @Transactional: MySQL save + Outbox INSERT (same TX)
  │
  └─ OutboxScheduler (every 5 seconds):
       ├─ SELECT * FROM outbox_events WHERE status = 'PENDING' LIMIT 50
       ├─ For each event → kafkaProducerService.sendStockEventSync()
       │   ├─ Success → status = SENT
       │   └─ Failure → retryCount++ (max 5 → status = FAILED)
       └─ Prometheus metrics: outbox_events_total{result=sent|failed|retried}
```

**Outbox Table Schema:**
```sql
CREATE TABLE outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) UNIQUE NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    stock_id BIGINT NOT NULL,
    payload TEXT NOT NULL,          -- JSON serialized StockEventDto
    status ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP
);
```

**Benefits:**
- **Zero event loss during Kafka partition** — events survive in MySQL
- **Transactional consistency** — stock write + outbox insert in same TX
- **At-least-once delivery** — retries with exponential backoff
- **Observability** — Prometheus gauge for pending event count

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
2. ✅ **Observability** - 30+ custom Prometheus metrics, 2 Grafana dashboards, real-time SSE frontend
3. ✅ **Fault Injection** - Realistic partition testing via Toxiproxy
4. ✅ **Performance** - Low memory footprint, high throughput
5. ✅ **CAP Demonstration** - Clear trade-offs between consistency and availability
6. ✅ **Reliable Delivery** - Transactional Outbox Pattern for zero event loss
7. ✅ **IoT Integration** - End-to-end sensor pipeline (Python → Kafka → Redis → SSE → React)
8. ✅ **Rate Limiting** - Token-bucket API protection with Bucket4j

**CAP Summary:**
- **CP:** MySQL (source of truth + outbox)
- **AP:** Redis (cache + sensor store), Kafka consumer (best-effort processing)
- **Outbox:** Converts stock events from AP to eventual consistency (at-least-once)
- **System:** AP-prioritized with graceful degradation

**Remaining Production Improvements:**
1. Add Redis Sentinel for automatic failover
2. Use multi-broker Kafka cluster
3. Implement circuit breakers (Resilience4j)
4. Add distributed tracing (Zipkin/Jaeger)
5. Reduce Redis timeout (5s → 2s)
6. Add dead-letter queue for permanently failed outbox events

---

## Appendices

### A. Technology Versions

```
Java: 17
Spring Boot: 3.x
Gradle: 8.5
Kafka: 4.1.1 (KRaft, no Zookeeper)
Redis: 7-alpine
MySQL: 8.0
Prometheus: latest
Grafana: latest
Toxiproxy: latest
React: 18
Vite: 6.x
Python: 3.11 (IoT Simulator)
Bucket4j: (Rate Limiting)
Nginx: alpine (Frontend reverse proxy)
```

### B. Key Configuration Files

- `application.yaml` - Spring Boot configuration
- `docker-compose.yml` - Container orchestration (11 services)
- `prometheus.yml` - Metrics scraping config
- `grafana/dashboards/stock-metrics.json` - Stock Grafana dashboard
- `grafana/dashboards/iot-sensors.json` - IoT Sensor Grafana dashboard
- `toxiproxy-config.json` - Proxy initialization
- `frontend/nginx.conf` - Nginx reverse proxy + SSE support
- `frontend/vite.config.js` - Dev server proxy config
- `iot-simulator/sensor_simulator.py` - Python IoT data generator

### C. Useful Commands

See `TESTING.md` and `KAFKA_LOAD_TEST.md` for comprehensive command reference.
