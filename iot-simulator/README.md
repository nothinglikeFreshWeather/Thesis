# IoT Warehouse Sensor Simulator

Bu klasör, depo sensörlerinden mock veri üretip Kafka'ya gönderen IoT simülatörünü içerir.

## Özellikleri

- **Mock Veri Üretimi**: Her saniyede rastgele sıcaklık verisi (15-30°C) üretir
- **Kafka İntegrasyonu**: Verileri `warehouse-metrics` topic'ine gönderir
- **JSON Format**: Standart sensör veri formatında mesajlar
- **Docker Desteği**: Konteyner ortamında kolayca çalışır

## Veri Formatı

Simülatör şu formatta JSON verileri üretir:

```json
{
  "cihazId": "depo-sensor-1",
  "sicaklik": 24.5,
  "zaman": "2026-04-05T15:43:00Z"
}
```

- **cihazId**: Sensör cihazının benzersiz kimliği
- **sicaklik**: Sıcaklık değeri (Celsius derece)
- **zaman**: ISO 8601 formatında UTC zamanı

## Docker Compose İçinde Kullanım

```yaml
iot-simulator:
  build:
    context: ./iot-simulator
    dockerfile: Dockerfile
  container_name: iot-sensor-simulator
  environment:
    - KAFKA_BROKER=kafka:9092
    - KAFKA_TOPIC=warehouse-metrics
  networks:
    - stock-network
  depends_on:
    kafka:
      condition: service_healthy
  restart: always
```

## Spring Boot Backend Tarafında

Spring Boot uygulaması şu işlemleri gerçekleştirir:

1. **Dinleme**: `warehouse-metrics` topic'ini dinler
2. **Redis Depolama**: Güncel sensör verilerini Redis'e yazar
3. **Uyarı Üretimi**: 30°C'yi aşan sıcaklıklar için uyarı oluşturur

### API Endpoint'leri

```bash
# Güncel sensör okuması al
GET /api/sensors/current/{deviceId}

# Sensör uyarılarını al
GET /api/sensors/alerts/{deviceId}

# Uyarıları temizle
GET /api/sensors/alerts/{deviceId}/clear

# Sensör sağlık durumunu kontrol et
GET /api/sensors/health/{deviceId}
```

### Örnek İstekler

```bash
curl http://localhost:8080/api/sensors/current/depo-sensor-1
curl http://localhost:8080/api/sensors/alerts/depo-sensor-1
curl http://localhost:8080/api/sensors/health/depo-sensor-1
```

## Yerel Testleme

Eğer Docker olmadan yerel olarak test etmek isterseniz:

```bash
# Python paketlerini yükle
pip install kafka-python

# Simülatörü çalıştır (Kafka localhost:9092'de çalışıyor olmalıdır)
python sensor_simulator.py
```

## Konfigürasyon

`sensor_simulator.py` dosyasında şu değerleri değiştirebilirsiniz:

```python
KAFKA_BROKER = "kafka:9092"          # Kafka broker adresi
KAFKA_TOPIC = "warehouse-metrics"    # Target topic
DEVICE_ID = "depo-sensor-1"          # Sensör cihaz ID
TEMP_MIN = 15.0                      # Minimum sıcaklık
TEMP_MAX = 30.0                      # Maksimum sıcaklık
INTERVAL = 1                         # Veri üretim aralığı (saniye)
```

## Troubleshooting

### Kafka bağlantension hatası

```
Failed to create Kafka producer: NoBrokersAvailable()
```

**Çözüm**: Kafka servisinin çalıştığını kontrol edin:

```bash
docker-compose ps
docker-compose logs kafka
```

### Veri alınmıyor

Redis'teki verileri kontrol edin:

```bash
redis-cli
> KEYS sensor:*
> GET sensor:depo-sensor-1
```

## Performance Tuning

- `INTERVAL`: Daha küçük değer = daha sık veri (örn. 0.1 = 100ms)
- `KAFKA_BROKER`: Cluster modunda birden fazla broker belirtin
- `BATCH_SIZE`: Kafka producer config'unde batch boyutunu ayarlayın
