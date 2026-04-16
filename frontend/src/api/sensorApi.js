const API_BASE = '/api/sensors';

export const sensorApi = {
    async getCurrentReading(deviceId) {
        const response = await fetch(`${API_BASE}/current/${deviceId}`);
        if (!response.ok) {
            if (response.status === 404) return null;
            throw new Error('Failed to fetch sensor data');
        }
        return response.json();
    },

    async getAlerts(deviceId) {
        const response = await fetch(`${API_BASE}/alerts/${deviceId}`);
        if (!response.ok) throw new Error('Failed to fetch alerts');
        return response.json();
    },

    async getHealth(deviceId) {
        const response = await fetch(`${API_BASE}/health/${deviceId}`);
        if (!response.ok) throw new Error('Failed to fetch health status');
        return response.json();
    },

    async clearAlerts(deviceId) {
        const response = await fetch(`${API_BASE}/alerts/${deviceId}/clear`);
        if (!response.ok) throw new Error('Failed to clear alerts');
        return response.json();
    },

    /**
     * Creates an SSE connection to stream real-time sensor data
     * @param {string} deviceId
     * @param {function} onData - callback for each data event
     * @param {function} onError - callback on error
     * @returns {EventSource} - call .close() to disconnect
     */
    streamSensorData(deviceId, onData, onError) {
        const eventSource = new EventSource(`${API_BASE}/stream/${deviceId}`);

        eventSource.addEventListener('sensor-data', (event) => {
            try {
                const data = JSON.parse(event.data);
                onData(data);
            } catch (e) {
                console.error('Failed to parse SSE data:', e);
            }
        });

        eventSource.onerror = (event) => {
            console.error('SSE connection error:', event);
            if (onError) onError(event);
        };

        return eventSource;
    }
};
