import React from 'react';

export default function GrafanaDashboard() {
    return (
        <div className="dashboard-container">
            <h2>📊 Metrics Dashboard</h2>
            <p className="dashboard-description">
                Real-time Prometheus metrics visualization - Auto-refresh every 5 seconds
            </p>
            <div className="iframe-wrapper">
                <iframe
                    src="http://localhost:3001/d/stock-metrics/stock-tracking-metrics?orgId=1&refresh=5s&kiosk"
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
