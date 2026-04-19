package com.example.Thesis.sensor.listener;

import com.example.Thesis.sensor.dto.SensorDataDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kafka consumer for the 'warehouse-metrics' topic.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Consume sensor messages from Kafka.</li>
 *   <li>Store the latest reading per device in Redis as JSON (TTL: 5 min).</li>
 *   <li>Generate and store a temperature alert in Redis when threshold is exceeded.</li>
 *   <li>Update Prometheus metrics (received, alert, error counters + temperature gauge).</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class SensorDataListener {

    private static final String REDIS_SENSOR_PREFIX = "sensor:";
    private static final String REDIS_ALERT_PREFIX  = "alert:";
    private static final double TEMPERATURE_ALERT_THRESHOLD = 30.0;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Counter sensorReceivedCounter;
    private final Counter sensorAlertCounter;
    private final Counter sensorErrorCounter;
    private final AtomicReference<Double> lastTemperature;

    public SensorDataListener(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            Counter warehouseSensorReceivedCounter,
            Counter warehouseSensorAlertCounter,
            Counter warehouseSensorErrorCounter,
            MeterRegistry meterRegistry) {
        this.redisTemplate       = redisTemplate;
        this.objectMapper        = objectMapper;
        this.sensorReceivedCounter = warehouseSensorReceivedCounter;
        this.sensorAlertCounter    = warehouseSensorAlertCounter;
        this.sensorErrorCounter    = warehouseSensorErrorCounter;
        this.lastTemperature       = new AtomicReference<>(0.0);

        meterRegistry.gauge("warehouse_sensor_temperature_celsius",
                lastTemperature, AtomicReference::get);
    }

    @KafkaListener(
        topics = "warehouse-metrics",
        groupId = "warehouse-metrics-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleSensorData(SensorDataDto data) {
        try {
            sensorReceivedCounter.increment();
            log.info("Sensor data received: device={}, temp={}°C, time={}",
                    data.getDeviceId(), data.getTemperature(), data.getTimestamp());

            if (data.getTemperature() != null) {
                lastTemperature.set(data.getTemperature());
            }

            storeInRedis(data);

            if (data.getTemperature() != null && data.getTemperature() > TEMPERATURE_ALERT_THRESHOLD) {
                generateAlert(data);
            }

        } catch (Exception e) {
            sensorErrorCounter.increment();
            log.error("Error processing sensor data: {}", e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void storeInRedis(SensorDataDto data) {
        try {
            String key  = REDIS_SENSOR_PREFIX + data.getDeviceId();
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key, json);
            redisTemplate.expire(key, java.time.Duration.ofMinutes(5));
            log.debug("Sensor data stored in Redis: key={}", key);
        } catch (Exception e) {
            log.error("Failed to store sensor data in Redis: {}", e.getMessage(), e);
        }
    }

    private void generateAlert(SensorDataDto data) {
        try {
            sensorAlertCounter.increment();
            String alertKey = REDIS_ALERT_PREFIX + data.getDeviceId();
            String message  = String.format(
                "ALERT: Temperature %.1f°C exceeds threshold (%.1f°C) at %s",
                data.getTemperature(), TEMPERATURE_ALERT_THRESHOLD, Instant.now());

            redisTemplate.opsForList().leftPush(alertKey, message);
            redisTemplate.expire(alertKey, java.time.Duration.ofHours(1));
            log.warn("Temperature alert: device={}, msg={}", data.getDeviceId(), message);
        } catch (Exception e) {
            log.error("Failed to generate temperature alert: {}", e.getMessage(), e);
        }
    }
}
