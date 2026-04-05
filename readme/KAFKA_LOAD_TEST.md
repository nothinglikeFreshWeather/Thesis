# Kafka Load Testing Guide

Bu doküman Kafka producer performansını test etmek ve Grafana'da izlemek için hazırlanmıştır.

## 🚀 Load Test Endpoints

### 1. Hızlı Load Test (10,000 Mesaj)

**URL:** `POST /api/load-test/kafka?count=10000`

**Açıklama:** En hızlı şekilde belirtilen sayıda mesaj gönderir.

**Örnek:**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=10000"
```

**Response:**
```json
{
  "totalMessages": 10000,
  "successCount": 10000,
  "failureCount": 0,
  "durationMs": 2500,
  "throughputMsgPerSec": 4000,
  "status": "completed"
}
```

---

### 2. Batch Load Test (Kontrollü Gönderim)

**URL:** `POST /api/load-test/kafka/batch?count=10000&batchSize=100&delayMs=10`

**Parametreler:**
- `count`: Toplam mesaj sayısı (default: 10000)
- `batchSize`: Her batch'te kaç mesaj (default: 100)
- `delayMs`: Batch'ler arası bekleme süresi ms (default: 0)

**Örnek:**
```cmd
REM 10K mesaj, 100'er batch'te, batch arası 10ms
curl -X POST "http://localhost:8080/api/load-test/kafka/batch?count=10000&batchSize=100&delayMs=10"
```

---

## 📊 Grafana'da İzleme

### Adım 1: Grafana Dashboard'u Aç
```
http://localhost:3001/d/stock-metrics/system-health-kafka-monitoring
```

### Adım 2: Test Öncesi Hazırlık

1. **Dashboard'u temizle:**
   - Time range: "Last 5 minutes"
   - Refresh: "5s"

2. **İzlenecek Paneller:**
   - **Kafka Throughput (msgs/sec)** - Üst panel (4. sırada)
   - **Kafka Producer Status** - Büyük grafik (success/failure stacked)
   - **Kafka Success Rate** - Yüzdelik başarı oranı
   - **Kafka Failures (rate)** - Failure rate

### Adım 3: Test Çalıştır

**Terminal 1 - Test başlat:**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=10000"
```

**Terminal 2 - Log izle (opsiyonel):**
```cmd
docker-compose logs -f app | findstr "Load test\|Kafka"
```

**Grafana'da Gözlemlenecekler:**
- Throughput ani spike (örn: 4000-8000 msg/sec)
- Success counter artışı (yeşil çizgi)
- Kafka Success Rate %100
- HTTP Request Rate artışı

---

## 🧪 Test Senaryoları

### Senaryo 1: Normal Performans Test

**Amaç:** Maksimum throughput'u ölçmek

```cmd
REM 10K mesaj gönder
curl -X POST "http://localhost:8080/api/load-test/kafka?count=10000"
```

**Beklenen:**
- Throughput: 3000-8000 msg/sec
- Success rate: %100
- Duration: 1-3 saniye

---

### Senaryo 2: Kafka Partition Testi

**Amaç:** Partition durumunda failure davranışını görmek

**Adımlar:**

1. **Toxic ekle (Kafka'yı kes):**
```cmd
curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"kafka_down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"
```

2. **Load test başlat:**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=1000"
```

3. **Grafana'da gözlemle:**
   - Kafka Failures (rate) → KIRMIZI spike
   - Success Rate → Düşüş
   - Throughput → Düşük/0

4. **Toxic kaldır:**
```cmd
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_down
```

5. **Tekrar test:**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=1000"
```

6. **Grafana'da gözlemle:**
   - Failure rate → 0'a düşer
   - Success rate → %100'e çıkar

---

### Senaryo 3: Latency Test

**Amaç:** Kafka latency eklendiğinde throughput değişimini görmek

**Adımlar:**

1. **Normal test (baseline):**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=5000"
REM Throughput'u not et (örn: 5000 msg/sec)
```

2. **2000ms latency ekle:**
```cmd
curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"latency\",\"type\":\"latency\",\"attributes\":{\"latency\":2000}}"
```

3. **Latency ile test:**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=5000"
REM Throughput düşecek (örn: 500 msg/sec)
```

4. **Grafana'da gözlemle:**
   - Throughput düşüşü
   - Duration artışı
   - Ama success rate hala %100

5. **Latency kaldır:**
```cmd
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/latency
```

---

### Senaryo 4: Batch vs Burst Test

**Amaç:** Batch gönderim vs burst gönderim karşılaştırması

**Burst (default):**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka?count=10000"
REM Throughput: ~6000 msg/sec, spike
```

**Batch (100'lük paketler, 10ms batch arası):**
```cmd
curl -X POST "http://localhost:8080/api/load-test/kafka/batch?count=10000&batchSize=100&delayMs=10"
REM Throughput: ~800 msg/sec, düz çizgi
```

**Grafana'da Karşılaştır:**
- Burst: Ani spike, sonra düşüş
- Batch: Düzenli, sabit throughput

---

## 📈 Grafana Metric Formülleri

### Kafka Throughput
```
rate(kafka_producer_send_total[1m])
```
Messages per second over 1 minute

### Success Rate
```
sum(rate(kafka_producer_send_total{status="success"}[5m])) / sum(rate(kafka_producer_send_total[5m])) * 100
```

### Failure Rate
```
rate(kafka_producer_send_total{status="failure"}[5m])
```

---

## 🔍 Metrics Analizi

### İyi Performans Göstergeleri:
- ✅ Throughput: 3000+ msg/sec
- ✅ Success Rate: %100
- ✅ Failure Rate: 0
- ✅ Duration: <3 saniye (10K mesaj için)

### Sorun Göstergeleri:
- ❌ Throughput: <1000 msg/sec
- ❌ Success Rate: <%90
- ❌ Failure Rate: >0.1
- ❌ Duration: >10 saniye

---

## 🧪 Hızlı Test Komutları

### 1. Baseline Test
```cmd
REM Grafana aç: http://localhost:3001
curl -X POST "http://localhost:8080/api/load-test/kafka?count=10000"
```

### 2. Partition Test
```cmd
REM Toxic ekle
curl -X POST http://localhost:8666/proxies/kafka/toxics -H "Content-Type: application/json" -d "{\"name\":\"down\",\"type\":\"timeout\",\"attributes\":{\"timeout\":0}}"

REM Test
curl -X POST "http://localhost:8080/api/load-test/kafka?count=1000"

REM Toxic kaldır
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/down
```

### 3. Throughput Comparison
```cmd
REM Small batch
curl -X POST "http://localhost:8080/api/load-test/kafka?count=1000"

REM Large batch
curl -X POST "http://localhost:8080/api/load-test/kafka?count=10000"

REM Huge batch
curl -X POST "http://localhost:8080/api/load-test/kafka?count=50000"
```

---

## 📝 Log Çıktıları

### Başarılı Test:
```
🚀 Starting Kafka load test with 10000 messages
Progress: 1000/10000 messages sent
Progress: 2000/10000 messages sent
...
Progress: 10000/10000 messages sent
✅ Load test completed: 10000 messages in 2345ms (4266 msg/sec)
```

### Partition ile Test:
```
🚀 Starting Kafka load test with 1000 messages
Failed to send message 0: Timeout
Failed to send message 1: Timeout
...
✅ Load test completed: 1000 messages in 5234ms (191 msg/sec)
```

---

## 💡 Performans İyileştirme İpuçları

### Backend Tarafında:
1. `linger.ms` artır → Batch'leme iyileşir
2. `batch.size` artır → Daha büyük batch'ler
3. `compression.type: gzip` → Bant genişliği azalır

### Test Tarafında:
1. Batch size optimize et
2. Delay ile throughput kontrol et
3. Test boyutunu artırarak stress test yap

---

## 🎯 Tez İçin Önemli Noktalar

1. **Throughput Ölçümü:** Normal durumda kaç msg/sec?
2. **Partition Etkisi:** Kafka down olunca throughput nasıl değişiyor?
3. **Latency Etkisi:** 2000ms latency throughput'u % kaç düşürüyor?
4. **Recovery Time:** Toxic kaldırıldıktan sonra sistem ne kadar sürede normale dönüyor?

Hepsi Grafana'da görsel olarak izlenebilir! 🚀
