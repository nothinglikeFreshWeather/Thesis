# System Testing Guide

Bu dokümantasyon stock tracking sisteminin tüm bileşenlerini test etmek için hazırlanmıştır.

## 📋 Ön Kontroller

### 1. Tüm Servislerin Durumu
```cmd
docker-compose ps
```

Tüm servisler **Up** durumunda olmalı.

### 2. Frontend Erişimi
- URL: http://localhost:3000
- Beklenen: Stock Management UI görünmeli

### 3. Grafana Erişimi
- URL: http://localhost:3001
- Beklenen: Dashboard yüklenmeli

### 4. Prometheus Erişimi
- URL: http://localhost:9090
- Beklenen: Prometheus UI açılmalı

---

## 🧪 Backend API Testleri

### Stock Oluşturma
```cmd
curl -X POST http://localhost:8080/api/stocks -H "Content-Type: application/json" -d "{\"productName\":\"Laptop\",\"quantity\":10,\"price\":999.99}"
```

### Tüm Stockları Listeleme
```cmd
curl http://localhost:8080/api/stocks
```

### Belirli Stock Sorgulama (ID=1)
```cmd
curl http://localhost:8080/api/stocks/1
```

### Stock Güncelleme (ID=1)
```cmd
curl -X PUT http://localhost:8080/api/stocks/1 -H "Content-Type: application/json" -d "{\"productName\":\"Laptop Pro\",\"quantity\":15,\"price\":1299.99}"
```

### Stock Silme (ID=1)
```cmd
curl -X DELETE http://localhost:8080/api/stocks/1
```

### Prometheus Metrikleri
```cmd
curl http://localhost:8080/actuator/prometheus
```

---

## 🎯 Toxiproxy Testleri

### Mevcut Proxy'leri Listele
```cmd
curl http://localhost:8666/proxies
```

---

## 🔴 Redis Toxiproxy Testleri

### Redis Master - Latency Ekle (1000ms gecikme)
```cmd
curl -X POST http://localhost:8666/proxies/redis-master/toxics -H "Content-Type: application/json" -d "{\"name\":\"latency_test\",\"type\":\"latency\",\"attributes\":{\"latency\":1000}}"
```

### Redis Master - Bağlantıyı Kes (Partition Simülasyonu)
```cmd
curl -X POST http://localhost:8666/proxies/redis-master/toxics -H "Content-Type: application/json" -d "{\"name\":\"down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
```

### Redis Master - Bandwidth Sınırla (50 KB/s)
```cmd
curl -X POST http://localhost:8666/proxies/redis-master/toxics -H "Content-Type: application/json" -d "{\"name\":\"slow_bandwidth\",\"type\":\"bandwidth\",\"attributes\":{\"rate\":50}}"
```

### Redis Master - Toxic'leri Görüntüle
```cmd
curl http://localhost:8666/proxies/redis-master/toxics
```

### Redis Master - Latency Toxic'i Sil
```cmd
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/latency_test
```

### Redis Master - Tüm Toxic'leri Sil (Bağlantıyı Onar)
```cmd
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/down
```

---

### Redis Replica - Latency Ekle
```cmd
curl -X POST http://localhost:8666/proxies/redis-replica/toxics -H "Content-Type: application/json" -d "{\"name\":\"replica_latency\",\"type\":\"latency\",\"attributes\":{\"latency\":500}}"
```

### Redis Replica - Bağlantıyı Kes
```cmd
curl -X POST http://localhost:8666/proxies/redis-replica/toxics -H "Content-Type: application/json" -d "{\"name\":\"replica_down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
```

### Redis Replica - Toxic Sil
```cmd
curl -X DELETE http://localhost:8666/proxies/redis-replica/toxics/replica_latency
```

---

## 📨 Kafka Toxiproxy Testleri

### Kafka - Latency Ekle (2000ms gecikme)
```cmd
curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"kafka_latency\",\"type\":\"latency\",\"attributes\":{\"latency\":2000}}"
```

### Kafka - Bağlantıyı Kes
```cmd
curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"kafka_down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
```

### Kafka - Paket Kaybı Simülasyonu (%30 packet loss)
```cmd
curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"kafka_loss\",\"type\":\"limit_data\",\"attributes\":{\"bytes\":1000}}"
```

### Kafka - Toxic'leri Görüntüle
```cmd
curl http://localhost:8666/proxies/kafka/toxics
```

### Kafka - Toxic Sil
```cmd
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_latency
```

### Kafka - Bağlantıyı Onar
```cmd
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_down
```

---

## 🗄️ MySQL Toxiproxy Testleri

### MySQL - Latency Ekle
```cmd
curl -X POST http://localhost:8666/proxies/mysql/toxics -H "Content-Type: application/json" -d "{\"name\":\"mysql_latency\",\"type\":\"latency\",\"attributes\":{\"latency\":500}}"
```

### MySQL - Bağlantıyı Kes
```cmd
curl -X POST http://localhost:8666/proxies/mysql/toxics -H "Content-Type: application/json" -d "{\"name\":\"mysql_down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
```

### MySQL - Toxic Sil
```cmd
curl -X DELETE http://localhost:8666/proxies/mysql/toxics/mysql_latency
```

---

## 🧪 Test Senaryoları

### Senaryo 1: Redis Cache Test (Normal)

1. **Stock oluştur:**
   ```cmd
   curl -X POST http://localhost:8080/api/stocks -H "Content-Type: application/json" -d "{\"productName\":\"Mouse\",\"quantity\":50,\"price\":29.99}"
   ```

2. **İlk sorgulama (Cache MISS):**
   ```cmd
   curl http://localhost:8080/api/stocks/1
   ```

3. **İkinci sorgulama (Cache HIT):**
   ```cmd
   curl http://localhost:8080/api/stocks/1
   ```

4. **Grafana'da cache hit rate kontrol et:**
   - http://localhost:3001 → Cache Hit Rate % paneli

---

### Senaryo 2: Redis Master Partition

1. **Redis Master'i kes:**
   ```cmd
   curl -X POST http://localhost:8666/proxies/redis-master/toxics -H "Content-Type: application/json" -d "{\"name\":\"down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
   ```

2. **Stock oluştur (Cache bypass - DB'ye direk gider):**
   ```cmd
   curl -X POST http://localhost:8080/api/stocks -H "Content-Type: application/json" -d "{\"productName\":\"Keyboard\",\"quantity\":25,\"price\":79.99}"
   ```

3. **Stock sorgula (DB'den gelir, cache yok):**
   ```cmd
   curl http://localhost:8080/api/stocks/2
   ```

4. **Backend loglarını kontrol et:**
   ```cmd
   docker-compose logs app | findstr "Redis"
   ```

5. **Redis bağlantısını onar:**
   ```cmd
   curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/down
   ```

---

### Senaryo 3: Kafka Partition

1. **Kafka bağlantısını kes:**
   ```cmd
   curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"kafka_down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
   ```

2. **Stock oluştur (Kafka event gönderilmez ama stock oluşur):**
   ```cmd
   curl -X POST http://localhost:8080/api/stocks -H "Content-Type: application/json" -d "{\"productName\":\"Monitor\",\"quantity\":10,\"price\":299.99}"
   ```

3. **Backend loglarını kontrol et:**
   ```cmd
   docker-compose logs app | findstr "Kafka"
   ```

4. **Grafana'da Kafka failure metriğini kontrol et:**
   - http://localhost:3001 → Kafka Producer Metrics paneli

5. **Kafka bağlantısını onar:**
   ```cmd
   curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_down
   ```

---

### Senaryo 4: Redis Latency Test

1. **Redis'e 1000ms latency ekle:**
   ```cmd
   curl -X POST http://localhost:8666/proxies/redis-master/toxics -H "Content-Type: application/json" -d "{\"name\":\"latency_test\",\"type\":\"latency\",\"attributes\":{\"latency\":1000}}"
   ```

2. **Stock sorgula ve süreyi gözlemle:**
   ```cmd
   curl -w "\nTime: %{time_total}s\n" http://localhost:8080/api/stocks/1
   ```

3. **Grafana'da cache operation latency kontrol et:**
   - http://localhost:3001 → Cache Operation Latency paneli

4. **Latency toxic'i sil:**
   ```cmd
   curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/latency_test
   ```

---

## 📊 Grafana Dashboard Kontrolleri

### Dashboard Erişimi
1. http://localhost:3001 adresini aç
2. Dashboards → Browse → **Stock Tracking Metrics** seç

### Panel Kontrolleri

**HTTP Request Rate:**
- Stock oluştur/sorgula
- Request rate artmalı

**Cache Hit Rate %:**
- Aynı stock'u tekrar sorgula
- Hit rate yükselmeli

**Kafka Producer Metrics:**
- Stock oluştur
- Success counter artmalı
- Kafka kesikken failure counter artmalı

**JVM Memory Usage:**
- Heap kullanımı görünmeli

---

## 🔍 Log Kontrolleri

### Tüm Servislerin Logları
```cmd
docker-compose logs --tail=50
```

### Sadece Backend
```cmd
docker-compose logs --tail=100 app
```

### Sadece Redis Master
```cmd
docker-compose logs --tail=50 redis-master
```

### Sadece Kafka
```cmd
docker-compose logs --tail=50 kafka-kraft
```

### Sadece Toxiproxy
```cmd
docker-compose logs --tail=50 toxiproxy
```

### Error Logları
```cmd
docker-compose logs app | findstr "ERROR"
```

---

## ✅ Başarı Kriterleri

### Frontend
- ✅ Stock ekleme başarılı
- ✅ Stock listesi görünüyor
- ✅ Stock güncelleme çalışıyor
- ✅ Stock silme çalışıyor
- ✅ Grafana iframe yükleniyor

### Backend
- ✅ API endpoint'leri çalışıyor
- ✅ Redis cache hit/miss çalışıyor
- ✅ Kafka event'ler gönderiliyor
- ✅ MySQL bağlantısı sağlıklı
- ✅ Toxiproxy ile graceful degradation

### Metrics
- ✅ Prometheus metrics toplanuyor
- ✅ Grafana dashboard'lar yükleniyor
- ✅ Custom metrikler görünüyor
- ✅ JVM, HikariCP, Lettuce metrikleri mevcut

---

## 🚨 Sorun Giderme

### Frontend 404 Hatası
```cmd
docker-compose restart frontend
```

### Backend 500 Hatası
```cmd
docker-compose logs app | findstr "Exception"
```

### Redis Bağlantı Sorunu
```cmd
docker-compose logs redis-master
docker exec redis-master redis-cli ping
```

### Kafka Bağlantı Sorunu
```cmd
docker-compose logs kafka-kraft
```

### Toxiproxy Toxic Kaldırma (Hepsini)
```cmd
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/down
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_down
curl -X DELETE http://localhost:8666/proxies/mysql/toxics/mysql_down
```

### Tüm Servisleri Yeniden Başlat
```cmd
docker-compose restart
```

---

## 📝 Notlar

- **Unique Constraint:** Aynı productName ile iki stock oluşturamazsın.
- **Cache TTL:** Redis cache varsayılan TTL yok (kalıcı).
- **Kafka Events:** Stock CRUD operasyonları Kafka'ya event olarak gönderilir.
- **Toxiproxy:** Toxic'ler container restart'ta kaybolur (config'e yazılmaz).
- **Grafana:** Dashboard refresh 5 saniyede bir otomatik.

---

## 🎯 Hızlı Test Komutları

### Tam Test Döngüsü
```cmd
REM 1. Stock oluştur
curl -X POST http://localhost:8080/api/stocks -H "Content-Type: application/json" -d "{\"productName\":\"TestProduct\",\"quantity\":100,\"price\":99.99}"

REM 2. Listele
curl http://localhost:8080/api/stocks

REM 3. Redis kes
curl -X POST http://localhost:8666/proxies/redis-master/toxics -H "Content-Type: application/json" -d "{\"name\":\"down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"

REM 4. Yeni stock ekle (cache bypass)
curl -X POST http://localhost:8080/api/stocks -H "Content-Type: application/json" -d "{\"productName\":\"NoCache\",\"quantity\":50,\"price\":49.99}"

REM 5. Redis onar
curl -X DELETE http://localhost:8666/proxies/redis-master/toxics/down

REM 6. Listele (cache'den)
curl http://localhost:8080/api/stocks
```

İyi testler! 🚀
