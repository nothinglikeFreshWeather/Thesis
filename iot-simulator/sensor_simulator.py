#!/usr/bin/env python3
"""
IoT Sensor Simulator - Mock data generator for warehouse metrics
Generates random temperature readings and sends them to Kafka
"""

import json
import random
import time
from datetime import datetime
from kafka import KafkaProducer
import logging

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Configuration
KAFKA_BROKER = "kafka:9092"
KAFKA_TOPIC = "warehouse-metrics"
DEVICE_ID = "depo-sensor-1"
TEMP_MIN = 15.0
TEMP_MAX = 30.0
INTERVAL = 1  # seconds

def create_kafka_producer():
    """Create and return a Kafka producer"""
    try:
        producer = KafkaProducer(
            bootstrap_servers=KAFKA_BROKER,
            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
            acks='all',
            retries=3
        )
        logger.info(f"Kafka producer created. Broker: {KAFKA_BROKER}, Topic: {KAFKA_TOPIC}")
        return producer
    except Exception as e:
        logger.error(f"Failed to create Kafka producer: {e}")
        raise

def generate_sensor_data():
    """Generate random sensor data"""
    temperature = round(random.uniform(TEMP_MIN, TEMP_MAX), 1)
    timestamp = datetime.utcnow().isoformat() + "Z"
    
    data = {
        "cihazId": DEVICE_ID,
        "sicaklik": temperature,
        "zaman": timestamp
    }
    return data

def send_to_kafka(producer, data):
    """Send sensor data to Kafka topic"""
    try:
        future = producer.send(KAFKA_TOPIC, value=data)
        record_metadata = future.get(timeout=10)
        logger.info(
            f"Message sent to {record_metadata.topic} "
            f"[partition={record_metadata.partition}, offset={record_metadata.offset}]: {data}"
        )
    except Exception as e:
        logger.error(f"Failed to send message to Kafka: {e}")

def main():
    """Main simulation loop"""
    producer = create_kafka_producer()
    logger.info("IoT Sensor Simulator started. Generating data every {} second(s)...".format(INTERVAL))
    
    try:
        while True:
            # Generate sensor data
            sensor_data = generate_sensor_data()
            
            # Send to Kafka
            send_to_kafka(producer, sensor_data)
            
            # Wait before next reading
            time.sleep(INTERVAL)
    except KeyboardInterrupt:
        logger.info("Simulation stopped by user")
    except Exception as e:
        logger.error(f"Error in simulation loop: {e}")
    finally:
        producer.close()
        logger.info("Kafka producer closed")

if __name__ == "__main__":
    main()
