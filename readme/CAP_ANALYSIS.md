# CAP Teoremi Analizi - Kafka ve Spring Boot

## Mevcut Durumun Analizi

### 🔴 Şu Anki Davranış: **AP (Availability + Partition Tolerance)**

Mevcut kodumuzda Kafka network partition olduğunda:

```java
// StockService.java - createStock metodu
@Transactional
public StockResponseDto createStock(StockRequestDto requestDto) {
    // 1. Database'e kayıt - TRANSACTION İÇİNDE
    Stock savedStock = stockRepository.save(stock);
    
    // 2. Kafka'ya mesaj gönder - ASENKRON
    kafkaProducerService.sendStockEvent(event);  // Hata fırlatmaz!
    
    return mapToResponseDto(savedStock);
    // Transaction COMMIT oluyor - DB'ye yazıldı ✅
}
```

```java
// KafkaProducerService.java
public void sendStockEvent(StockEventDto event) {
    CompletableFuture<SendResult<String, StockEventDto>> future = 
        kafkaTemplate.send(stockEventsTopic, event.getStockId().toString(), event);
    
    // ASENKRON - exception fırlatmaz, sadece log'a yazar
    future.whenComplete((result, ex) -> {
        if (ex != null) {
            log.error("Failed to send: {}", ex.getMessage());
            // ❌ Transaction zaten commit oldu, geri alınamaz!
        }
    });
}
```

**Sonuç:**
- ✅ MySQL'e yazma **BAŞARILI** (Availability korunuyor)
- ❌ Kafka'ya mesaj **GÖNDERİLEMİYOR**
- ⚠️ **Veri tutarsızlığı**: DB'de var, Kafka'da yok (Consistency kaybediliyor)

---

## CP Davranışı Nasıl Sağlanır?

### ✅ Consistency Öncelikli: **CP (Consistency + Partition Tolerance)**

Kafka başarısız olduğunda transaction'ı geri almak için **senkron gönderim** gerekir:

### Seçenek 1: Senkron Kafka Send (Önerilen)

```java
// KafkaProducerService.java - Senkron versiyon
public void sendStockEventSync(StockEventDto event) {
    log.info("Sending stock event synchronously: {}", event.getEventId());
    
    try {
        // SENKRON BEKLEME - get() çağrısı exception fırlatır
        SendResult<String, StockEventDto> result = kafkaTemplate
            .send(stockEventsTopic, event.getStockId().toString(), event)
            .get(30, TimeUnit.SECONDS); // Timeout ile bekle
        
        log.info("Event sent successfully: offset={}", 
                 result.getRecordMetadata().offset());
                 
    } catch (ExecutionException | InterruptedException | TimeoutException e) {
        log.error("Failed to send Kafka event: {}", e.getMessage());
        // Exception fırlatarak transaction'ı rollback yap
        throw new RuntimeException("Kafka send failed - rolling back transaction", e);
    }
}
```

```java
// StockService.java
@Transactional
public StockResponseDto createStock(StockRequestDto requestDto) {
    // 1. Database'e kayıt
    Stock savedStock = stockRepository.save(stock);
    
    // 2. Kafka'ya SENKRON gönder
    try {
        kafkaProducerService.sendStockEventSync(event);
    } catch (RuntimeException e) {
        // Exception yakalanır, Spring transaction'ı ROLLBACK yapar
        throw e; // Re-throw to rollback transaction
    }
    
    // Buraya sadece her ikisi de başarılıysa gelir
    return mapToResponseDto(savedStock);
}
```

**Sonuç:**
- ❌ Kafka partition varsa: Transaction **ROLLBACK** (Consistency korunuyor)
- ✅ Her ikisi de başarılı olmalı
- ⚠️ Kafka down ise sistem **KULLANILMAZ** (Availability kaybediliyor)

---

### Seçenek 2: Transactional Outbox Pattern (En İyi Çözüm)

Gerçek dünya uygulamalarında kullanılan pattern:

```java
// Yeni tablo: OUTBOX_EVENTS
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    @GeneratedValue
    private Long id;
    
    private String aggregateId;
    private String eventType;
    
    @Column(columnDefinition = "JSON")
    private String payload;
    
    private LocalDateTime createdAt;
    private boolean processed;
}
```

```java
// StockService.java - Outbox pattern ile
@Transactional
public StockResponseDto createStock(StockRequestDto requestDto) {
    // 1. Database'e kayıt
    Stock savedStock = stockRepository.save(stock);
    
    // 2. Event'i OUTBOX tablosuna yaz (aynı transaction'da!)
    OutboxEvent outboxEvent = new OutboxEvent();
    outboxEvent.setAggregateId(savedStock.getId().toString());
    outboxEvent.setEventType("STOCK_CREATED");
    outboxEvent.setPayload(objectMapper.writeValueAsString(event));
    outboxEventRepository.save(outboxEvent);
    
    // Transaction COMMIT - Hem stock hem outbox event atomik olarak yazıldı ✅
    return mapToResponseDto(savedStock);
}
```

```java
// Ayrı bir scheduled job Kafka'ya gönderir
@Scheduled(fixedDelay = 5000)
public void processOutboxEvents() {
    List<OutboxEvent> pendingEvents = outboxEventRepository
        .findByProcessedFalse();
    
    for (OutboxEvent event : pendingEvents) {
        try {
            // Kafka'ya gönder
            kafkaTemplate.send(topic, event.getPayload()).get();
            
            // Başarılı - işaretle
            event.setProcessed(true);
            outboxEventRepository.save(event);
        } catch (Exception e) {
            // Başarısız - sonra tekrar denenecek
            log.warn("Kafka still down, will retry later");
        }
    }
}
```

**Avantajlar:**
- ✅ **Strong Consistency**: DB ve event aynı transaction'da
- ✅ **Eventual Delivery**: Kafka sonra açılsa bile event gönderilir
- ✅ **At-least-once Delivery**: Guaranteed mesaj iletimi
- ✅ **Availability**: Kafka down olsa bile API çalışır

---

## AP Davranışı Nasıl Optimize Edilir?

Mevcut AP davranışını kabul edip iyileştirebiliriz:

### Retry Mekanizması Ekleme

```java
// KafkaProducerService.java - Retry ile AP
public void sendStockEventWithRetry(StockEventDto event) {
    RetryTemplate retryTemplate = RetryTemplate.builder()
        .maxAttempts(3)
        .fixedBackoff(1000)
        .retryOn(KafkaException.class)
        .build();
    
    try {
        retryTemplate.execute(context -> {
            kafkaTemplate.send(stockEventsTopic, event).get(5, TimeUnit.SECONDS);
            return null;
        });
    } catch (Exception e) {
        // Retry'lar da başarısız
        // Dead Letter Queue'ya at veya DB'ye yaz
        saveToDeadLetterStore(event);
        log.error("All retries failed, saved to DLQ");
    }
}
```

---

## Kafka Yapılandırmasıyla CP/AP Ayarlama

### CP İçin Kafka Producer Ayarları

```yaml
spring:
  kafka:
    producer:
      # Güçlü consistency için
      acks: all                           # Tüm replica'lar onaylamalı
      enable-idempotence: true            # Duplicate prevention
      max-in-flight-requests-per-connection: 1  # Sıralı gönderim
      retries: 0                          # Retry yok - hemen fail
      
      # Kısa timeout'lar - fail fast
      properties:
        request.timeout.ms: 5000          # 5 saniye
        delivery.timeout.ms: 10000        # 10 saniye
        max.block.ms: 5000                # Send blocking süresi
```

**Davranış:**
- Kafka partition varsa: **HEMEN** exception fırlatır
- Transaction rollback olur
- **CP davranışı**: Availability düşer, Consistency korunur

### AP İçin Kafka Producer Ayarları (Şu anki)

```yaml
spring:
  kafka:
    producer:
      acks: 1                             # Sadece leader onayı (daha hızlı)
      enable-idempotence: false           # Daha esnek
      retries: 10                         # Çok retry
      
      # Uzun timeout'lar - availability için
      properties:
        request.timeout.ms: 30000         # 30 saniye
        delivery.timeout.ms: 120000       # 120 saniye
        retry.backoff.ms: 1000            # Retry arası bekleme
```

**Davranış:**
- Kafka geçici down: **RETRY** eder, eventual consistency
- Transaction commit olur
- **AP davranışı**: Availability yüksek, eventual consistency

---

## Karşılaştırma Tablosu

| Özellik | AP (Mevcut) | CP (Senkron) | Outbox Pattern |
|---------|-------------|--------------|----------------|
| **Kafka down durumunda API** | ✅ Çalışır | ❌ Fail eder | ✅ Çalışır |
| **DB-Kafka tutarlılığı** | ⚠️ Eventual | ✅ Immediate | ✅ Guaranteed |
| **Mesaj kaybı riski** | ⚠️ Yüksek | ✅ Yok | ✅ Yok |
| **Performans** | ✅ Hızlı | ⚠️ Yavaş | ✅ Hızlı |
| **Karmaşıklık** | ✅ Basit | ✅ Basit | ⚠️ Orta |
| **Production-ready** | ❌ Risk var | ⚠️ Kullanılabilir | ✅ En iyi |

---

## Hangisini Seçmeliyiz?

### Test/Demo İçin: **AP (Mevcut)**
- ✅ Kolay anlaşılır
- ✅ CAP trade-off'larını göstermek için ideal
- ⚠️ Production'da kullanılmamalı

### Kritik Sistemler İçin: **CP (Senkron)**
- ✅ Veri kaybı kabul edilemez
- ✅ Finansal sistemler, envanter yönetimi
- ⚠️ Kafka downtime'da sistem kullanılamaz

### Production İçin: **Transactional Outbox**
- ✅ En iyi consistency guarantee
- ✅ High availability
- ✅ Saga pattern ile uyumlu
- ✅ Microservice architectures için ideal

---

## Önerilen Değişiklik: Configuration-Based Seçim

Her iki davranışı da destekleyelim:

```yaml
# application.yaml
app:
  kafka:
    consistency-mode: AP  # AP veya CP
```

```java
@Service
public class KafkaProducerService {
    
    @Value("${app.kafka.consistency-mode:AP}")
    private String consistencyMode;
    
    public void sendStockEvent(StockEventDto event) {
        if ("CP".equals(consistencyMode)) {
            sendStockEventSync(event);  // Exception fırlatır
        } else {
            sendStockEventAsync(event); // Fire and forget
        }
    }
    
    private void sendStockEventSync(StockEventDto event) {
        try {
            kafkaTemplate.send(topic, event).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Kafka unavailable - CP mode", e);
        }
    }
    
    private void sendStockEventAsync(StockEventDto event) {
        kafkaTemplate.send(topic, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Async send failed: {}", ex.getMessage());
            }
        });
    }
}
```

Bu şekilde her iki davranışı da test edebilir ve karşılaştırabilirsiniz! 🎯
