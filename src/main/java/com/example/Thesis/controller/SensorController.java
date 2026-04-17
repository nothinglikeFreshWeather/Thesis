package com.example.Thesis.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.Thesis.dto.SensorDataDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/sensors")
@RequiredArgsConstructor
public class SensorController {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String REDIS_KEY_PREFIX = "sensor:";
    private static final String ALERT_KEY_PREFIX = "alert:";
    
    private final ScheduledExecutorService sseScheduler = Executors.newScheduledThreadPool(2);
    
    /**
     * SSE stream endpoint - pushes sensor data every 1 second
     * @param deviceId The device/sensor ID
     * @return SseEmitter that streams sensor data
     */
    @GetMapping(value = "/stream/{deviceId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSensorData(@PathVariable String deviceId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 minute timeout
        
        var future = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                String redisKey = REDIS_KEY_PREFIX + deviceId;
                String sensorValue = redisTemplate.opsForValue().get(redisKey);
                
                Map<String, Object> data = new HashMap<>();
                data.put("deviceId", deviceId);
                data.put("timestamp", System.currentTimeMillis());
                
                if (sensorValue != null) {
                    data.put("status", "active");
                    data.putAll(parseRedisSensorValue(sensorValue));
                } else {
                    data.put("status", "inactive");
                    data.put("temperature", null);
                    data.put("sensorTime", null);
                }
                
                // Also include alert count
                String alertKey = ALERT_KEY_PREFIX + deviceId;
                Long alertCount = redisTemplate.opsForList().size(alertKey);
                data.put("alertCount", alertCount != null ? alertCount : 0);
                
                emitter.send(SseEmitter.event()
                        .name("sensor-data")
                        .data(data));
                        
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("Error streaming sensor data for {}: {}", deviceId, e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
        
        // Clean up on completion/timeout/error
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> {
            future.cancel(true);
            emitter.complete();
        });
        emitter.onError(e -> future.cancel(true));
        
        log.info("SSE stream started for device: {}", deviceId);
        return emitter;
    }
    
    /**
     * Get current sensor reading from Redis
     * @param deviceId The device/sensor ID
     * @return Current sensor reading or null if not found
     */
    @GetMapping("/current/{deviceId}")
    public ResponseEntity<Map<String, Object>> getCurrentReading(@PathVariable String deviceId) {
        try {
            String redisKey = REDIS_KEY_PREFIX + deviceId;
            String sensorValue = redisTemplate.opsForValue().get(redisKey);
            
            if (sensorValue == null) {
                return ResponseEntity.notFound().build();
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("deviceId", deviceId);
            response.putAll(parseRedisSensorValue(sensorValue));
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error retrieving sensor data for device {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get all alerts for a specific device
     * @param deviceId The device/sensor ID
     * @return List of stored alerts
     */
    @GetMapping("/alerts/{deviceId}")
    public ResponseEntity<Map<String, Object>> getDeviceAlerts(@PathVariable String deviceId) {
        try {
            String alertKey = ALERT_KEY_PREFIX + deviceId;
            List<String> alerts = redisTemplate.opsForList().range(alertKey, 0, -1);
            
            return ResponseEntity.ok(Map.of(
                    "deviceId", deviceId,
                    "alertCount", alerts != null ? alerts.size() : 0,
                    "alerts", alerts != null ? alerts : List.of(),
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Error retrieving alerts for device {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Clear alerts for a specific device
     * @param deviceId The device/sensor ID
     * @return Success message
     */
    @GetMapping("/alerts/{deviceId}/clear")
    public ResponseEntity<Map<String, String>> clearDeviceAlerts(@PathVariable String deviceId) {
        try {
            String alertKey = ALERT_KEY_PREFIX + deviceId;
            Boolean deleted = redisTemplate.delete(alertKey);
            
            return ResponseEntity.ok(Map.of(
                    "deviceId", deviceId,
                    "message", "Alerts cleared successfully",
                    "deletedAlerts", Boolean.TRUE.equals(deleted) ? "1" : "0"
            ));
        } catch (Exception e) {
            log.error("Error clearing alerts for device {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get health status - check if sensor is still sending data
     * @param deviceId The device/sensor ID
     * @return Health status
     */
    @GetMapping("/health/{deviceId}")
    public ResponseEntity<Map<String, Object>> getSensorHealth(@PathVariable String deviceId) {
        try {
            String redisKey = REDIS_KEY_PREFIX + deviceId;
            String sensorValue = redisTemplate.opsForValue().get(redisKey);
            
            boolean isHealthy = sensorValue != null;
            String status = isHealthy ? "active" : "inactive";
            
            Map<String, Object> response = new HashMap<>();
            response.put("deviceId", deviceId);
            response.put("status", status);
            response.put("isActive", isHealthy);
            response.put("lastData", sensorValue != null ? sensorValue : "N/A");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error checking sensor health for device {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Parses sensor value from Redis JSON into structured fields.
     * Redis now stores: {"cihazId":"depo-sensor-1","sicaklik":24.5,"zaman":"..."}
     */
    private Map<String, Object> parseRedisSensorValue(String json) {
        Map<String, Object> result = new HashMap<>();
        result.put("rawValue", json);
        try {
            SensorDataDto dto = objectMapper.readValue(json, SensorDataDto.class);
            result.put("temperature", dto.getSicaklik());
            result.put("temperatureUnit", "°C");
            result.put("sensorTime", dto.getZaman());
            result.put("deviceId", dto.getCihazId());
        } catch (Exception e) {
            log.warn("Failed to parse sensor JSON from Redis '{}': {}", json, e.getMessage());
            result.put("temperature", null);
        }
        return result;
    }
}
