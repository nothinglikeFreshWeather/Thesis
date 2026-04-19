package com.example.Thesis.sensor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for IoT sensor data received from the Kafka 'warehouse-metrics' topic.
 *
 * <p>The Python simulator publishes JSON with Turkish field names (cihazId, sicaklik, zaman).
 * {@code @JsonProperty} maps those wire names to English Java fields so the rest of
 * the codebase stays clean and professional.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SensorDataDto {

    /** Device identifier — wire name: "cihazId" */
    @JsonProperty("cihazId")
    private String deviceId;

    /** Temperature in Celsius — wire name: "sicaklik" */
    @JsonProperty("sicaklik")
    private Double temperature;

    /** ISO-8601 timestamp from the sensor — wire name: "zaman" */
    @JsonProperty("zaman")
    private String timestamp;
}
