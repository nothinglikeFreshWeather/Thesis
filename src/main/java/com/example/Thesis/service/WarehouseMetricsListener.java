package com.example.Thesis.service;

import com.example.Thesis.dto.SensorDataDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class WarehouseMetricsListener {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final Counter sensorReceivedCounter;
    private final Counter sensorAlertCounter;
    private final Counter sensorErrorCounter;
    private final AtomicReference<Double> lastTemperature;
    
    private static final String REDIS_KEY_PREFIX = "sensor:";
    private static final double TEMPERATURE_ALERT_THRESHOLD = 30.0;
    private static final String ALERT_KEY_PREFIX = "alert:";
    
    public WarehouseMetricsListener(
            RedisTemplate<String, String> redisTemplate,
            Counter warehouseSensorReceivedCounter,
            Counter warehouseSensorAlertCounter,
            Counter warehouseSensorErrorCounter,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.sensorReceivedCounter = warehouseSensorReceivedCounter;
        this.sensorAlertCounter = warehouseSensorAlertCounter;
        this.sensorErrorCounter = warehouseSensorErrorCounter;
        this.lastTemperature = new AtomicReference<>(0.0);
        
        // Register a gauge for the last temperature reading
        meterRegistry.gauge("warehouse_sensor_temperature_celsius", lastTemperature, AtomicReference::get);
    }
    
    /**
     * Listens to warehouse-metrics Kafka topic and processes sensor data
     * Stores latest reading in Redis and generates alerts if threshold exceeded
     */
    @KafkaListener(
        topics = "warehouse-metrics",
        groupId = "warehouse-metrics-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleSensorData(SensorDataDto sensorData) {
        try {
            // Increment received counter
            sensorReceivedCounter.increment();
            
            log.info("Received sensor data: Device={}, Temperature={}°C, Time={}",
                    sensorData.getCihazId(),
                    sensorData.getSicaklik(),
                    sensorData.getZaman());
            
            // Update temperature gauge
            if (sensorData.getSicaklik() != null) {
                lastTemperature.set(sensorData.getSicaklik());
            }
            
            // Store latest reading in Redis
            storeSensorDataInRedis(sensorData);
            
            // Check for temperature alert
            if (sensorData.getSicaklik() != null && sensorData.getSicaklik() > TEMPERATURE_ALERT_THRESHOLD) {
                generateTemperatureAlert(sensorData);
            }
            
        } catch (Exception e) {
            sensorErrorCounter.increment();
            log.error("Error processing sensor data: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Stores sensor data in Redis for real-time access
     */
    private void storeSensorDataInRedis(SensorDataDto sensorData) {
        try {
            String redisKey = REDIS_KEY_PREFIX + sensorData.getCihazId();
            String sensorValue = String.format(
                "Temperature=%.1f°C|Time=%s|Updated=%d",
                sensorData.getSicaklik(),
                sensorData.getZaman(),
                System.currentTimeMillis()
            );
            
            // Set with TTL of 5 minutes (300 seconds)
            redisTemplate.opsForValue().set(redisKey, sensorValue);
            redisTemplate.expire(redisKey, java.time.Duration.ofMinutes(5));
            
            log.debug("Sensor data stored in Redis: {} = {}", redisKey, sensorValue);
        } catch (Exception e) {
            log.error("Failed to store sensor data in Redis: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Generates and stores temperature alert when threshold is exceeded
     */
    private void generateTemperatureAlert(SensorDataDto sensorData) {
        try {
            // Increment alert counter
            sensorAlertCounter.increment();
            
            String alertKey = ALERT_KEY_PREFIX + sensorData.getCihazId();
            String alertMessage = String.format(
                "ALERT: Temperature %.1f°C exceeds threshold (%.1f°C) at %s",
                sensorData.getSicaklik(),
                TEMPERATURE_ALERT_THRESHOLD,
                Instant.now().toString()
            );
            
            // Store alert in Redis (keep for 1 hour)
            redisTemplate.opsForList().leftPush(alertKey, alertMessage);
            redisTemplate.expire(alertKey, java.time.Duration.ofHours(1));
            
            log.warn("Temperature Alert: {} - {}", sensorData.getCihazId(), alertMessage);
        } catch (Exception e) {
            log.error("Failed to generate temperature alert: {}", e.getMessage(), e);
        }
    }
}
