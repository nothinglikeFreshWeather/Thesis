import React, { useState } from 'react';

export default function GrafanaDashboard() {
    const [activeDash, setActiveDash] = useState('stock');

    const dashboards = {
        stock: "http://localhost:3001/d/stock-metrics/stock-tracking-metrics?orgId=1&refresh=5s&kiosk",
        iot: "http://localhost:3001/d/iot-sensor-metrics/iot-sensor-metrics?orgId=1&refresh=5s&kiosk"
    };

    return (
        <div className="dashboard-container">
            <h2>📊 Metrics Dashboard</h2>
            <p className="dashboard-description">
                Real-time Prometheus metrics visualization - Auto-refresh every 5 seconds
            </p>
            
            <div className="dashboard-tabs" style={{ marginBottom: '15px', display: 'flex', gap: '10px', justifyContent: 'center' }}>
                <button 
                    className={`btn ${activeDash === 'stock' ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => setActiveDash('stock')}
                >
                    Core System Metrics
                </button>
                <button 
                    className={`btn ${activeDash === 'iot' ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => setActiveDash('iot')}
                >
                    IoT Sensor Metrics
                </button>
            </div>

            <div className="iframe-wrapper">
                <iframe
                    src={dashboards[activeDash]}
                    title="Grafana Dashboard"
                    style={{
                        width: '100%',
                        height: '800px',
                        border: 'none',
                        borderRadius: '8px'
                    }}
                />
            </div>
        </div>
    );
}
