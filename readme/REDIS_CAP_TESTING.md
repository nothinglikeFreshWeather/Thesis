# Redis Master-Replica CAP Testing Guide

## Architecture

Redis Master-Replica setup to demonstrate **AP (Availability + Partition Tolerance)** behavior:

```
┌─────────┐     Write      ┌──────────────┐     ┌──────────────┐
│Spring   │───────────────>│Toxiproxy     │────>│Redis Master  │
│Boot App │                │:26379        │     │:6379         │
└─────────┘                └──────────────┘     └──────────────┘
     │                                                  │
     │                                                  │ Replication
     │ Read                                             ▼
     │                     ┌──────────────┐     ┌──────────────┐
     └────────────────────>│Toxiproxy     │────>│Redis Replica │
                           │:26380        │     │:6379         │
                           └──────────────┘     └──────────────┘
```

## Starting the Infrastructure

```bash
# Build application
./gradlew clean build

# Start all services
docker-compose up -d

# Verify services are running
docker-compose ps

# Check Redis replication status
docker-compose exec redis-master redis-cli INFO replication
docker-compose exec redis-replica redis-cli INFO replication
```

## Test Scenarios

### Scenario 1: Normal Operation (Baseline)

**Test cache-aside pattern:**

```bash
# 1. Create stock (writes to DB + Redis master)
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Gaming Laptop","quantity":5,"price":1999.99}'

# Response: {"id":1, "productName":"Gaming Laptop", ...}

# 2. Read stock (should hit cache)
curl http://localhost:8080/api/stocks/1

# 3. Check logs - should see "Cache HIT"
docker-compose logs app | grep "Cache HIT"

# 4. Read again (still from cache)
curl http://localhost:8080/api/stocks/1
```

**Expected:**
- ✅ First read: Cache MISS (reads from DB, caches result)
- ✅ Subsequent reads: Cache HIT (reads from cache)
- ✅ Fast response times

---

### Scenario 2: Redis Master Partition (AP - Write Degradation)

**Test system behavior when master is unavailable for writes:**

```bash
# 1. Partition Redis master
curl -X POST http://localhost:8666/proxies/redis-master/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"master_down","type":"timeout","attributes":{"timeout":0}}'

# 2. Try to create stock (DB write succeeds, cache write fails gracefully)
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Wireless Mouse","quantity":100,"price":49.99}'

# Response: Should succeed (200 OK)

# 3. Check logs - should see cache write failure
docker-compose logs app | grep "Failed to set cache"

# 4. Read the just-created stock (will be cache MISS, reads from DB)
curl http://localhost:8080/api/stocks/2

# 5. Restore Redis master
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/master_down
```

**Expected - AP Behavior:**
- ✅ **Availability**: System stays available for writes
- ✅ **Availability**: Reads fallback to database
- ⚠️ **Performance**: Slower (no cache), but functional
- 📝 **Graceful Degradation**: Cache failures logged, not propagated

---

### Scenario 3: Redis Replica Partition (AP - Read Degradation)

**Test read availability when replica is down:**

```bash
# 1. Create some stock first (with master working)
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{ "productName":"Mechanical Keyboard","quantity":25,"price":149.99}'

# 2. Partition Redis replica
curl -X POST http://localhost:8666/proxies/redis-replica/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"replica_down","type":"timeout","attributes":{"timeout":0}}'

# 3. Try to read stock (should fallback to DB)
curl http://localhost:8080/ api/stocks/3

# Response: Should succeed (data from DB)

# 4. Check logs
docker-compose logs app | grep "Cache MISS"

# 5. Create new stock (master still works)
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"USB-C Hub","quantity":50,"price":39.99}'

# 6. Restore replica
curl -X DELETE http://localhost:8666/proxies/redis-replica/toxics/replica_down
```

**Expected - AP Behavior:**
- ✅ **Availability**: Reads work (from DB)
- ✅ **Availability**: Writes work (to master)
- ⚠️ **Performance**: All reads go to DB (slower)
- 📝 Cache read failures handled gracefully

---

### Scenario 4: Replication Lag (Eventual Consistency)

**Simulate replication delay to observe eventual consistency:**

```bash
# 1. Add latency to replica
curl -X POST http://localhost:8666/proxies/redis-replica/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"replication_lag","type":"latency","attributes":{"latency":10000}}'

# 2. Create stock (writes to master immediately)
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{ "productName":"4K Monitor","quantity":10,"price":599.99}'

# Response: {"id":5, ...}

# 3. Immediately read from another terminal (might hit stale cache or miss)
curl http://localhost:8080/api/stocks/5

# 4. Wait 10 seconds, read again (now consistent)
sleep 10
curl http://localhost:8080/api/stocks/5

# 5. Remove latency
curl -X DELETE http://localhost:8666/proxies/redis-replica/toxics/replication_lag
```

**Expected - Eventual Consistency:**
- ⚠️ **Consistency**: Temporary inconsistency during lag
- ✅ **Eventual Consistency**: Data becomes consistent after replication
- 📝 Demonstrates CAP trade-off (AP system)

---

### Scenario 5: Both Redis Down (Complete Cache Failure)

**Test complete Redis outage:**

```bash
# 1. Partition both master and replica
curl -X POST http://localhost:8666/proxies/redis-master/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"master_down","type":"timeout","attributes":{"timeout":0}}'

curl -X POST http://localhost:8666/proxies/redis-replica/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"replica_down","type":"timeout","attributes":{"timeout":0}}'

# 2. Create stock (should still work - DB only)
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Webcam HD","quantity":30,"price":89.99}'

# 3. Read stock (should work - DB only)
curl http://localhost:8080/api/stocks/6

# 4. Update stock (should work - DB only)
curl -X PUT http://localhost:8080/api/stocks/6 \
  -H "Content-Type: application/json" \
  -d '{"productName":"Webcam HD Pro","quantity":35,"price":99.99}'

# 5. Restore both
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/master_down
curl -X DELETE http://localhost:8666/proxies/redis-replica/toxics/replica_down
```

**Expected - Maximum Availability:**
- ✅ **Availability**: System fully operational without cache
- ⚠️ **Performance**: Degraded (all DB queries)
- ✅ **Consistency**: Database provides strong consistency
- 📝 **Resilience**: Demonstrates graceful degradation

---

## Monitoring Commands

### Check Redis Replication

```bash
# Master status
docker-compose exec redis-master redis-cli INFO replication

# Replica status
docker-compose exec redis-replica redis-cli INFO replication
```

### Check Cache Contents

```bash
# View all keys in Redis master
docker-compose exec redis-master redis-cli KEYS "stock:*"

# Get specific stock from cache
docker-compose exec redis-master redis-cli GET "stock:1"

# Check replica
docker-compose exec redis-replica redis-cli KEYS "stock:*"
```

### Application Logs

```bash
# Follow all logs
docker-compose logs -f app

# Filter cache operations
docker-compose logs app | grep -E "Cache (HIT|MISS|SET|DELETE)"

# Filter errors
docker-compose logs app | grep ERROR
```

### Toxiproxy Status

```bash
# List all proxies
curl http://localhost:8666/proxies

# Check Redis master proxy
curl http://localhost:8666/proxies/redis-master

# Check active toxics
curl http://localhost:8666/proxies/redis-master/toxics
curl http://localhost:8666/proxies/redis-replica/toxics
```

---

## AP Behavior Summary

| Scenario | Availability | Consistency | Performance |
|----------|-------------|-------------|-------------|
| **Normal** | ✅ High | ✅ Eventual | ✅ Fast |
| **Master Down** | ✅ High | ⚠️ Degraded | ⚠️ Slower |
| **Replica Down** | ✅ High | ✅ From DB | ⚠️ Slower |
| **Replication Lag** | ✅ High | ⚠️ Eventual | ✅ Fast |
| **Both Down** | ✅ High | ✅ From DB | ❌ Slow |

**Key Observations:**
- System prioritizes **Availability** over **Consistency**
- Graceful degradation when cache unavailable
- Database provides fallback consistency
- Cache improves performance but is not critical for correctness

---

## Clean Up

```bash
# Stop all services
docker-compose down

# Remove volumes (database and cache data)
docker-compose down -v

# Remove all images
docker-compose down --rmi all -v
```

---

## Comparison: CP (Kafka) vs AP (Redis)

| Aspect | Kafka (CP) | Redis (AP) |
|--------|------------|------------|
| **Partition Behavior** | Fails if unavailable | Degrades gracefully |
| **Consistency** | Strong (acks=all) | Eventual (replication) |
| **Availability** | Lower during partition | Higher during partition |
| **Use Case** | Critical events | Performance optimization |
| **Data Loss Risk** | Very low | Medium (cache) |

Both demonstrate different trade-offs in the CAP theorem! 🎯
