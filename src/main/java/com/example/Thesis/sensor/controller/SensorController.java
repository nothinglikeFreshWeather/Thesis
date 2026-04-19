package com.example.Thesis.sensor.controller;

import com.example.Thesis.sensor.dto.SensorDataDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for IoT sensor data.
 *
 * <p>Reads sensor readings stored in Redis by {@link com.example.Thesis.sensor.listener.SensorDataListener}
 * and exposes them via REST and Server-Sent Events (SSE).</p>
 *
 * <ul>
 *   <li>{@code GET /api/sensors/stream/{deviceId}}  — SSE live stream (1 s interval)</li>
 *   <li>{@code GET /api/sensors/current/{deviceId}} — latest snapshot</li>
 *   <li>{@code GET /api/sensors/alerts/{deviceId}}  — alert list</li>
 *   <li>{@code GET /api/sensors/alerts/{deviceId}/clear} — clear alerts</li>
 *   <li>{@code GET /api/sensors/health/{deviceId}}  — sensor health</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/sensors")
@RequiredArgsConstructor
public class SensorController {

    private static final String REDIS_SENSOR_PREFIX = "sensor:";
    private static final String REDIS_ALERT_PREFIX  = "alert:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService sseScheduler = Executors.newScheduledThreadPool(2);

    // ── SSE ──────────────────────────────────────────────────────────────────

    @GetMapping(value = "/stream/{deviceId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSensorData(@PathVariable String deviceId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        var future = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_SENSOR_PREFIX + deviceId);
                Map<String, Object> payload = new HashMap<>();
                payload.put("deviceId", deviceId);
                payload.put("timestamp", System.currentTimeMillis());

                if (json != null) {
                    payload.put("status", "active");
                    payload.putAll(parseSensorJson(json));
                } else {
                    payload.put("status", "inactive");
                    payload.put("temperature", null);
                    payload.put("sensorTime", null);
                }

                Long alertCount = redisTemplate.opsForList().size(REDIS_ALERT_PREFIX + deviceId);
                payload.put("alertCount", alertCount != null ? alertCount : 0);

                emitter.send(SseEmitter.event().name("sensor-data").data(payload));
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("Error streaming sensor data for {}: {}", deviceId, e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);

        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> { future.cancel(true); emitter.complete(); });
        emitter.onError(e -> future.cancel(true));

        log.info("SSE stream started for device: {}", deviceId);
        return emitter;
    }

    // ── REST ─────────────────────────────────────────────────────────────────

    @GetMapping("/current/{deviceId}")
    public ResponseEntity<Map<String, Object>> getCurrentReading(@PathVariable String deviceId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_SENSOR_PREFIX + deviceId);
            if (json == null) return ResponseEntity.notFound().build();

            Map<String, Object> response = new HashMap<>(parseSensorJson(json));
            response.put("deviceId", deviceId);
            response.put("retrievedAt", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error retrieving sensor data for {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/alerts/{deviceId}")
    public ResponseEntity<Map<String, Object>> getDeviceAlerts(@PathVariable String deviceId) {
        try {
            List<String> alerts = redisTemplate.opsForList()
                    .range(REDIS_ALERT_PREFIX + deviceId, 0, -1);
            return ResponseEntity.ok(Map.of(
                    "deviceId",    deviceId,
                    "alertCount",  alerts != null ? alerts.size() : 0,
                    "alerts",      alerts != null ? alerts : List.of(),
                    "timestamp",   System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Error retrieving alerts for {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/alerts/{deviceId}/clear")
    public ResponseEntity<Map<String, String>> clearDeviceAlerts(@PathVariable String deviceId) {
        try {
            Boolean deleted = redisTemplate.delete(REDIS_ALERT_PREFIX + deviceId);
            return ResponseEntity.ok(Map.of(
                    "deviceId", deviceId,
                    "message",  "Alerts cleared successfully",
                    "cleared",  Boolean.TRUE.equals(deleted) ? "true" : "false"
            ));
        } catch (Exception e) {
            log.error("Error clearing alerts for {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/health/{deviceId}")
    public ResponseEntity<Map<String, Object>> getSensorHealth(@PathVariable String deviceId) {
        try {
            String json     = redisTemplate.opsForValue().get(REDIS_SENSOR_PREFIX + deviceId);
            boolean healthy = json != null;
            return ResponseEntity.ok(Map.of(
                    "deviceId", deviceId,
                    "status",   healthy ? "active" : "inactive",
                    "isActive", healthy,
                    "lastData", json != null ? json : "N/A"
            ));
        } catch (Exception e) {
            log.error("Error checking sensor health for {}: {}", deviceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Deserializes the JSON stored in Redis (written by SensorDataListener) into
     * a flat map for API responses. Uses English field names.
     */
    private Map<String, Object> parseSensorJson(String json) {
        Map<String, Object> result = new HashMap<>();
        result.put("rawValue", json);
        try {
            SensorDataDto dto = objectMapper.readValue(json, SensorDataDto.class);
            result.put("temperature",     dto.getTemperature());
            result.put("temperatureUnit", "°C");
            result.put("sensorTime",      dto.getTimestamp());
            result.put("deviceId",        dto.getDeviceId());
        } catch (Exception e) {
            log.warn("Failed to parse sensor JSON from Redis '{}': {}", json, e.getMessage());
            result.put("temperature", null);
        }
        return result;
    }
}
