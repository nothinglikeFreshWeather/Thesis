# Stock Tracking System - CAP Theorem Testing

Bu proje, CAP teoremini test etmek için Kafka ve MySQL kullanan basit bir stok takip sistemidir. Toxiproxy kullanarak network partitionları simüle edebilir ve sistemin davranışını gözlemleyebilirsiniz.

## Teknolojiler

- **Spring Boot 3.5.9** - Ana uygulama framework'ü
- **Apache Kafka (Kraft Mode)** - Mesaj kuyruğu (Zookeeper olmadan)
- **MySQL 8.0** - İlişkisel veritabanı
- **Toxiproxy** - Network partition simülasyonu
- **Docker & Docker Compose** - Konteynerizasyon
- **Lombok** - Boilerplate kod azaltma
- **Gradle** - Build tool

## Mimari

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   Client    │─────>│   Spring    │─────>│   MySQL     │
│   (curl)    │      │   Boot App  │      │  (via Proxy)│
└─────────────┘      └─────────────┘      └─────────────┘
                            │
                            │ Kafka Events
                            ▼
                     ┌─────────────┐
                     │    Kafka    │
                     │ (via Proxy) │
                     └─────────────┘
                            ▲
                            │
                     ┌─────────────┐
                     │ Toxiproxy   │
                     │ (Partition  │
                     │  Simulator) │
                     └─────────────┘
```

## Kurulum ve Çalıştırma

### Önkoşullar

- Docker ve Docker Compose yüklü olmalı
- Port 8080, 8666, 9092, 3306, 29093, 3307 portları kullanılabilir olmalı

### Adım 1: Projeyi Build Edin

```bash
# Gradle ile build
./gradlew clean build

# Veya Windows'ta
gradlew.bat clean build
```

### Adım 2: Docker Container'ları Başlatın

```bash
# Tüm servisleri başlat
docker-compose up -d

# Logları takip edin
docker-compose logs -f

# Sadece uygulama loglarını görmek için
docker-compose logs -f app
```

### Adım 3: Servislerin Durumunu Kontrol Edin

```bash
# Tüm servislerin çalıştığını doğrulayın
docker-compose ps

# Toxiproxy API'sinin çalıştığını kontrol edin
curl http://localhost:8666/proxies
```

## API Kullanımı

### Stok Oluşturma

```bash
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{
    "productName": "Laptop",
    "quantity": 10,
    "price": 999.99
  }'
```

### Tüm Stokları Listeleme

```bash
curl http://localhost:8080/api/stocks
```

### Stok Detayı Görüntüleme

```bash
curl http://localhost:8080/api/stocks/1
```

### Stok Güncelleme

```bash
curl -X PUT http://localhost:8080/api/stocks/1 \
  -H "Content-Type: application/json" \
  -d '{
    "productName": "Laptop Pro",
    "quantity": 15,
    "price": 1299.99
  }'
```

### Stok Silme

```bash
curl -X DELETE http://localhost:8080/api/stocks/1
```

## CAP Teoremi Testleri

### Test 1: Normal Çalışma (Baseline)

```bash
# Stok oluştur
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Mouse","quantity":50,"price":29.99}'

# Logları kontrol et - mesajın Kafka'ya gönderildiğini göreceksiniz
docker-compose logs app | grep "Stock event sent successfully"
```

### Test 2: Kafka Network Partition (CP vs AP)

```bash
# Kafka bağlantısını kes (timeout ekle)
curl -X POST http://localhost:8666/proxies/kafka/toxics \
  -H "Content-Type: application/json" \
  -d '{
    "name": "kafka_down",
    "type": "timeout",
    "attributes": {
      "timeout": 0
    }
  }'

# Şimdi stok oluşturmayı dene
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Keyboard","quantity":25,"price":79.99}'

# Veritabanına kaydedildi mi kontrol et
curl http://localhost:8080/api/stocks

# Uygulama loglarını kontrol et - Kafka hatası göreceksiniz
docker-compose logs app | tail -20

# Bağlantıyı restore et
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_down
```

### Test 3: MySQL Network Partition

```bash
# MySQL bağlantısını kes
curl -X POST http://localhost:8666/proxies/mysql/toxics \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mysql_down",
    "type": "timeout",
    "attributes": {
      "timeout": 0
    }
  }'

# Stok oluşturmayı dene - başarısız olmalı
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Monitor","quantity":8,"price":299.99}'

# Bağlantıyı restore et
curl -X DELETE http://localhost:8666/proxies/mysql/toxics/mysql_down
```

### Test 4: Network Latency (Yavaşlama)

```bash
# Kafka'ya 5 saniyelik gecikme ekle
curl -X POST http://localhost:8666/proxies/kafka/toxics \
  -H "Content-Type: application/json" \
  -d '{
    "name": "kafka_latency",
    "type": "latency",
    "attributes": {
      "latency": 5000
    }
  }'

# Stok oluştur ve süreyi ölç
time curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Webcam","quantity":30,"price":89.99}'

# Latency'yi kaldır
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_latency
```

### Test 5: Bandwidth Limit

```bash
# Kafka'ya bandwidth limiti ekle (1KB/s)
curl -X POST http://localhost:8666/proxies/kafka/toxics \
  -H "Content-Type: application/json" \
  -d '{
    "name": "kafka_bandwidth",
    "type": "bandwidth",
    "attributes": {
      "rate": 1
    }
  }'

# Stok oluştur
curl -X POST http://localhost:8080/api/stocks \
  -H "Content-Type: application/json" \
  -d '{"productName":"Headphones","quantity":40,"price":149.99}'

# Bandwidth limitini kaldır
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_bandwidth
```

## Toxiproxy Komutları

### Tüm Proxy'leri Listele

```bash
curl http://localhost:8666/proxies
```

### Belirli Bir Proxy'nin Toxic'lerini Listele

```bash
curl http://localhost:8666/proxies/kafka/toxics
curl http://localhost:8666/proxies/mysql/toxics
```

### Tüm Toxic'leri Temizle

```bash
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_down
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_latency
curl -X DELETE http://localhost:8666/proxies/kafka/toxics/kafka_bandwidth
```

### Proxy'yi Disable/Enable Et

```bash
# Disable
curl -X POST http://localhost:8666/proxies/kafka \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'

# Enable
curl -X POST http://localhost:8666/proxies/kafka \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'
```

## Sistemin Temizlenmesi

```bash
# Tüm container'ları durdur ve kaldır
docker-compose down

# Volume'ları da sil (veritabanı verilerini temizler)
docker-compose down -v

# Docker image'larını da temizle
docker-compose down --rmi all -v
```

## Proje Yapısı

```
.
├── src/
│   └── main/
│       ├── java/com/example/Thesis/
│       │   ├── ThesisApplication.java
│       │   ├── config/
│       │   │   └── KafkaProducerConfig.java
│       │   ├── controller/
│       │   │   └── StockController.java
│       │   ├── dto/
│       │   │   ├── StockRequestDto.java
│       │   │   ├── StockResponseDto.java
│       │   │   └── StockEventDto.java
│       │   ├── model/
│       │   │   └── Stock.java
│       │   ├── repository/
│       │   │   └── StockRepository.java
│       │   └── service/
│       │       ├── StockService.java
│       │       └── KafkaProducerService.java
│       └── resources/
│           └── application.yaml
├── build.gradle
├── Dockerfile
├── docker-compose.yml
├── toxiproxy-config.json
└── README.md
```

## Notlar

- **Kraft Mode**: Kafka'nın en güncel sürümünü kullanıyor, Zookeeper'a ihtiyaç yok
- **Toxiproxy**: Network partition, latency, bandwidth limiting gibi senaryoları test etmek için kullanılıyor
- **Idempotent Producer**: Kafka producer'ı idempotent olarak yapılandırıldı (tekrar eden mesajları önler)
- **Transaction Management**: Spring'in @Transactional annotation'ı ile veritabanı işlemleri yönetiliyor

## Sorun Giderme

### Port zaten kullanımda hatası

```bash
# Portları kullanan process'leri bul ve kapat
netstat -ano | findstr :8080
netstat -ano | findstr :3306
```

### Container'lar başlamıyor

```bash
# Logları kontrol et
docker-compose logs

# Tüm container'ları yeniden başlat
docker-compose restart
```

### Kafka'ya bağlanamıyor

```bash
# Kafka container'ının loglarını kontrol et
docker-compose logs kafka

# Kafka'nın hazır olup olmadığını kontrol et
docker-compose exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092
```
