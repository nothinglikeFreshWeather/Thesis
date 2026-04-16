import React, { useState, useEffect, useRef, useCallback } from 'react';
import { sensorApi } from '../api/sensorApi';

const MAX_DATA_POINTS = 60;
const TEMP_WARN = 25.0;
const TEMP_ALERT = 30.0;

function getTemperatureColor(temp) {
    if (temp == null) return '#6b7280';
    if (temp >= TEMP_ALERT) return '#ef4444';
    if (temp >= TEMP_WARN) return '#f59e0b';
    return '#10b981';
}

function getTemperatureLabel(temp) {
    if (temp == null) return 'N/A';
    if (temp >= TEMP_ALERT) return 'CRITICAL';
    if (temp >= TEMP_WARN) return 'WARNING';
    return 'NORMAL';
}

// ─── Mini Canvas Chart ──────────────────────────────────────────────
function TemperatureChart({ dataPoints }) {
    const canvasRef = useRef(null);

    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        const dpr = window.devicePixelRatio || 1;

        const rect = canvas.getBoundingClientRect();
        canvas.width = rect.width * dpr;
        canvas.height = rect.height * dpr;
        ctx.scale(dpr, dpr);

        const W = rect.width;
        const H = rect.height;
        const padding = { top: 30, right: 20, bottom: 35, left: 50 };
        const chartW = W - padding.left - padding.right;
        const chartH = H - padding.top - padding.bottom;

        // Clear
        ctx.clearRect(0, 0, W, H);

        // Background
        ctx.fillStyle = '#0f172a';
        ctx.beginPath();
        ctx.roundRect(0, 0, W, H, 12);
        ctx.fill();

        if (dataPoints.length < 2) {
            ctx.fillStyle = '#64748b';
            ctx.font = '14px Inter, system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText('Waiting for data...', W / 2, H / 2);
            return;
        }

        // Calculate Y range
        const temps = dataPoints.map(d => d.temperature).filter(t => t != null);
        if (temps.length === 0) return;
        const minTemp = Math.floor(Math.min(...temps) - 2);
        const maxTemp = Math.ceil(Math.max(...temps) + 2);
        const tempRange = maxTemp - minTemp || 1;

        // Grid lines
        ctx.strokeStyle = '#1e293b';
        ctx.lineWidth = 1;
        const gridSteps = 5;
        for (let i = 0; i <= gridSteps; i++) {
            const y = padding.top + (chartH / gridSteps) * i;
            ctx.beginPath();
            ctx.moveTo(padding.left, y);
            ctx.lineTo(padding.left + chartW, y);
            ctx.stroke();

            // Y labels
            const tempVal = maxTemp - (tempRange / gridSteps) * i;
            ctx.fillStyle = '#64748b';
            ctx.font = '11px Inter, system-ui, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(`${tempVal.toFixed(1)}°`, padding.left - 8, y + 4);
        }

        // Threshold lines
        // Warning threshold
        if (TEMP_WARN >= minTemp && TEMP_WARN <= maxTemp) {
            const yWarn = padding.top + chartH - ((TEMP_WARN - minTemp) / tempRange) * chartH;
            ctx.strokeStyle = 'rgba(245, 158, 11, 0.4)';
            ctx.setLineDash([6, 4]);
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(padding.left, yWarn);
            ctx.lineTo(padding.left + chartW, yWarn);
            ctx.stroke();
            ctx.setLineDash([]);
        }

        // Alert threshold
        if (TEMP_ALERT >= minTemp && TEMP_ALERT <= maxTemp) {
            const yAlert = padding.top + chartH - ((TEMP_ALERT - minTemp) / tempRange) * chartH;
            ctx.strokeStyle = 'rgba(239, 68, 68, 0.4)';
            ctx.setLineDash([6, 4]);
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(padding.left, yAlert);
            ctx.lineTo(padding.left + chartW, yAlert);
            ctx.stroke();
            ctx.setLineDash([]);
        }

        // Data line
        const xStep = chartW / (MAX_DATA_POINTS - 1);
        const startIdx = Math.max(0, MAX_DATA_POINTS - dataPoints.length);

        // Fill area under curve
        ctx.beginPath();
        let firstPoint = true;
        dataPoints.forEach((point, i) => {
            if (point.temperature == null) return;
            const x = padding.left + (startIdx + i) * xStep;
            const y = padding.top + chartH - ((point.temperature - minTemp) / tempRange) * chartH;
            if (firstPoint) {
                ctx.moveTo(x, y);
                firstPoint = false;
            } else {
                ctx.lineTo(x, y);
            }
        });

        // Close the fill path
        const lastValidIdx = dataPoints.length - 1;
        const lastX = padding.left + (startIdx + lastValidIdx) * xStep;
        const firstX = padding.left + startIdx * xStep;
        ctx.lineTo(lastX, padding.top + chartH);
        ctx.lineTo(firstX, padding.top + chartH);
        ctx.closePath();

        const gradient = ctx.createLinearGradient(0, padding.top, 0, padding.top + chartH);
        gradient.addColorStop(0, 'rgba(99, 102, 241, 0.3)');
        gradient.addColorStop(1, 'rgba(99, 102, 241, 0.02)');
        ctx.fillStyle = gradient;
        ctx.fill();

        // Line
        ctx.beginPath();
        firstPoint = true;
        dataPoints.forEach((point, i) => {
            if (point.temperature == null) return;
            const x = padding.left + (startIdx + i) * xStep;
            const y = padding.top + chartH - ((point.temperature - minTemp) / tempRange) * chartH;
            if (firstPoint) {
                ctx.moveTo(x, y);
                firstPoint = false;
            } else {
                ctx.lineTo(x, y);
            }
        });
        ctx.strokeStyle = '#818cf8';
        ctx.lineWidth = 2.5;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        ctx.stroke();

        // Last point glow
        const lastPoint = dataPoints[dataPoints.length - 1];
        if (lastPoint && lastPoint.temperature != null) {
            const lx = padding.left + (startIdx + dataPoints.length - 1) * xStep;
            const ly = padding.top + chartH - ((lastPoint.temperature - minTemp) / tempRange) * chartH;
            const color = getTemperatureColor(lastPoint.temperature);

            // Glow
            const glow = ctx.createRadialGradient(lx, ly, 0, lx, ly, 12);
            glow.addColorStop(0, color);
            glow.addColorStop(1, 'transparent');
            ctx.fillStyle = glow;
            ctx.fillRect(lx - 12, ly - 12, 24, 24);

            // Dot
            ctx.beginPath();
            ctx.arc(lx, ly, 4, 0, Math.PI * 2);
            ctx.fillStyle = color;
            ctx.fill();
            ctx.strokeStyle = '#0f172a';
            ctx.lineWidth = 2;
            ctx.stroke();
        }

        // X axis label
        ctx.fillStyle = '#64748b';
        ctx.font = '11px Inter, system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('← Last 60 readings →', W / 2, H - 8);

        // Title
        ctx.fillStyle = '#e2e8f0';
        ctx.font = 'bold 13px Inter, system-ui, sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText('Temperature Timeline', padding.left, 18);

    }, [dataPoints]);

    return (
        <canvas
            ref={canvasRef}
            className="sensor-chart-canvas"
            style={{ width: '100%', height: '300px' }}
        />
    );
}

// ─── Main Dashboard Component ───────────────────────────────────────
export default function SensorDashboard() {
    const [deviceId, setDeviceId] = useState('depo-sensor-1');
    const [inputValue, setInputValue] = useState('depo-sensor-1');
    const [connected, setConnected] = useState(false);
    const [currentData, setCurrentData] = useState(null);
    const [dataHistory, setDataHistory] = useState([]);
    const [alerts, setAlerts] = useState([]);
    const [alertCount, setAlertCount] = useState(0);
    const eventSourceRef = useRef(null);

    // Connect to SSE stream
    const connectStream = useCallback((devId) => {
        // Close existing connection
        if (eventSourceRef.current) {
            eventSourceRef.current.close();
            eventSourceRef.current = null;
        }

        setConnected(false);
        setDataHistory([]);
        setCurrentData(null);

        const es = sensorApi.streamSensorData(
            devId,
            (data) => {
                setConnected(true);
                setCurrentData(data);
                setAlertCount(data.alertCount || 0);

                if (data.temperature != null) {
                    setDataHistory(prev => {
                        const next = [...prev, {
                            temperature: data.temperature,
                            timestamp: data.timestamp
                        }];
                        return next.length > MAX_DATA_POINTS ? next.slice(-MAX_DATA_POINTS) : next;
                    });
                }
            },
            () => {
                setConnected(false);
            }
        );

        eventSourceRef.current = es;
    }, []);

    // Fetch alerts
    const fetchAlerts = useCallback(async () => {
        try {
            const data = await sensorApi.getAlerts(deviceId);
            setAlerts(data.alerts || []);
            setAlertCount(data.alertCount || 0);
        } catch (e) {
            console.error('Failed to fetch alerts:', e);
        }
    }, [deviceId]);

    const handleClearAlerts = async () => {
        try {
            await sensorApi.clearAlerts(deviceId);
            setAlerts([]);
            setAlertCount(0);
        } catch (e) {
            console.error('Failed to clear alerts:', e);
        }
    };

    // Connect on mount and when deviceId changes
    useEffect(() => {
        connectStream(deviceId);
        return () => {
            if (eventSourceRef.current) {
                eventSourceRef.current.close();
            }
        };
    }, [deviceId, connectStream]);

    // Fetch alerts periodically
    useEffect(() => {
        fetchAlerts();
        const interval = setInterval(fetchAlerts, 5000);
        return () => clearInterval(interval);
    }, [fetchAlerts]);

    const handleDeviceSubmit = (e) => {
        e.preventDefault();
        const trimmed = inputValue.trim();
        if (trimmed && trimmed !== deviceId) {
            setDeviceId(trimmed);
        }
    };

    const temp = currentData?.temperature;
    const tempColor = getTemperatureColor(temp);
    const tempLabel = getTemperatureLabel(temp);

    return (
        <div className="sensor-dashboard">
            {/* Device Selector */}
            <div className="sensor-device-selector">
                <form onSubmit={handleDeviceSubmit} className="device-form">
                    <div className="device-input-group">
                        <label htmlFor="device-id-input">Device ID</label>
                        <div className="device-input-row">
                            <input
                                id="device-id-input"
                                type="text"
                                value={inputValue}
                                onChange={(e) => setInputValue(e.target.value)}
                                placeholder="e.g. depo-sensor-1"
                                className="device-input"
                            />
                            <button type="submit" className="btn btn-primary device-btn">
                                Connect
                            </button>
                        </div>
                    </div>
                </form>
                <div className={`connection-badge ${connected ? 'connected' : 'disconnected'}`}>
                    <span className="status-dot" />
                    {connected ? 'Live' : 'Disconnected'}
                </div>
            </div>

            {/* Stats Cards */}
            <div className="sensor-cards">
                {/* Temperature Card */}
                <div className="sensor-card temperature-card" style={{ '--accent': tempColor }}>
                    <div className="card-icon">🌡️</div>
                    <div className="card-body">
                        <span className="card-label">Temperature</span>
                        <span className="card-value" style={{ color: tempColor }}>
                            {temp != null ? `${temp.toFixed(1)}°C` : '—'}
                        </span>
                        <span className="card-badge" style={{ background: tempColor }}>
                            {tempLabel}
                        </span>
                    </div>
                </div>

                {/* Status Card */}
                <div className="sensor-card status-card">
                    <div className="card-icon">📡</div>
                    <div className="card-body">
                        <span className="card-label">Sensor Status</span>
                        <span className="card-value" style={{
                            color: currentData?.status === 'active' ? '#10b981' : '#6b7280'
                        }}>
                            {currentData?.status === 'active' ? 'Active' : 'Inactive'}
                        </span>
                        <span className="card-secondary">
                            Device: {deviceId}
                        </span>
                    </div>
                </div>

                {/* Alerts Card */}
                <div className="sensor-card alerts-card">
                    <div className="card-icon">🔔</div>
                    <div className="card-body">
                        <span className="card-label">Active Alerts</span>
                        <span className="card-value" style={{
                            color: alertCount > 0 ? '#ef4444' : '#10b981'
                        }}>
                            {alertCount}
                        </span>
                        <span className="card-secondary">
                            Threshold: {TEMP_ALERT}°C
                        </span>
                    </div>
                </div>

                {/* Data Points Card */}
                <div className="sensor-card datapoints-card">
                    <div className="card-icon">📊</div>
                    <div className="card-body">
                        <span className="card-label">Data Points</span>
                        <span className="card-value" style={{ color: '#818cf8' }}>
                            {dataHistory.length}
                        </span>
                        <span className="card-secondary">
                            of {MAX_DATA_POINTS} buffered
                        </span>
                    </div>
                </div>
            </div>

            {/* Chart */}
            <div className="sensor-chart-container">
                <TemperatureChart dataPoints={dataHistory} />
            </div>

            {/* Alerts Panel */}
            <div className="sensor-alerts-panel">
                <div className="alerts-header">
                    <h3>⚠️ Temperature Alerts</h3>
                    {alerts.length > 0 && (
                        <button onClick={handleClearAlerts} className="btn btn-alert-clear">
                            Clear All
                        </button>
                    )}
                </div>
                {alerts.length === 0 ? (
                    <div className="alerts-empty">
                        <span>✅</span>
                        <p>No alerts — all temperatures within safe range</p>
                    </div>
                ) : (
                    <div className="alerts-list">
                        {alerts.slice(0, 20).map((alert, i) => (
                            <div key={i} className="alert-item">
                                <span className="alert-dot" />
                                <span className="alert-text">{alert}</span>
                            </div>
                        ))}
                        {alerts.length > 20 && (
                            <div className="alerts-more">
                                +{alerts.length - 20} more alerts
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}
