FROM python:3.11-slim

WORKDIR /app

# Install Python dependencies
RUN pip install --no-cache-dir kafka-python

# Copy sensor simulator script
COPY sensor_simulator.py .

# Make the script executable
RUN chmod +x sensor_simulator.py

# Run the simulator
CMD ["python3", "sensor_simulator.py"]
