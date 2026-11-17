# Chaos Engineering & CAP Theorem Test Suite

Bu klasör, **Toxiproxy** üzerinden deterministik ağ hataları (gecikme, paket kaybı, bağlantı kopması) enjekte ederek sistemin **CAP Teoremi** davranışlarını doğrulayan otomatik test scriptlerini içerir.

## Hızlı Başlangıç

```bash
# 1. Tüm altyapıyı ayağa kaldırın (10 konteyner)
docker compose up -d

# 2A. Bash ile çalıştırın (Linux / macOS / Git Bash / WSL)
chmod +x ./chaos/simulate_chaos.sh
./chaos/simulate_chaos.sh

# 2B. PowerShell ile çalıştırın (Windows - Prometheus metrik raporlamalı)
powershell -ExecutionPolicy Bypass -File .\chaos\run_chaos.ps1
```

## Test Senaryoları

| # | Senaryo | Enjekte Edilen Toxic | Beklenen Sistem Tepkisi | CAP Doğrulaması |
|---|---------|----------------------|-------------------------|-----------------|
| 1 | **Redis Partition (AP)** | `timeout=0` (Kesinti) | `GET /api/stocks/{id}` -> `HTTP 200` (MySQL Fallback) | **AP** ✅ |
| 2 | **Network Latency** | `latency=300–2000ms` | Yanıt süresi artar ancak hata dönmez (`HTTP 200`) | **AP Degraded** ✅ |
| 3 | **Kafka Partition (Outbox)** | `timeout=0` (Kesinti) | `POST /api/stocks` -> `HTTP 201` (Olay MySQL Outbox'a yazılır, bağlantı gelince %0 kayıpla Kafka'ya akar) | **At-Least-Once** ✅ |
| 4 | **MySQL Partition (CP)** | `timeout=0` (Kesinti) | `POST /api/stocks` -> `HTTP 500` (Atomik Rollback, bozuk veri yazılmaz) | **CP** ✅ |
| 5 | **Recovery** | Tüm toxic'ler silinir | Önbellek ısınır, Outbox kuyruğu eritilir, gecikme `<12ms` seviyesine döner | **Tam Kurtarma** ✅ |
