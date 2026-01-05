import React, { useState, useEffect } from 'react';
import { stockApi } from './api/stockApi';
import StockForm from './components/StockForm';
import StockList from './components/StockList';
import GrafanaDashboard from './components/GrafanaDashboard';
import './App.css';

export default function App() {
    const [activeTab, setActiveTab] = useState('stocks');
    const [stocks, setStocks] = useState([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [message, setMessage] = useState({ text: '', type: '' });

    const showMessage = (text, type = 'success') => {
        setMessage({ text, type });
        setTimeout(() => setMessage({ text: '', type: '' }), 3000);
    };

    const fetchStocks = async () => {
        setLoading(true);
        setError('');
        try {
            const data = await stockApi.getAll();
            setStocks(data);
        } catch (err) {
            setError(err.message);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchStocks();
    }, []);

    const handleCreateStock = async (formData) => {
        try {
            await stockApi.create(formData);
            showMessage('Stock created successfully!');
            fetchStocks();
        } catch (err) {
            showMessage(err.message, 'error');
            throw err;
        }
    };

    return (
        <div className="app">
            <header className="app-header">
                <h1>📊 Stock Tracking System</h1>
                <p className="subtitle">Manage inventory and monitor metrics</p>
            </header>

            {message.text && (
                <div className={`global-message ${message.type}`}>
                    {message.text}
                </div>
            )}

            <div className="tabs">
                <button
                    className={`tab ${activeTab === 'stocks' ? 'active' : ''}`}
                    onClick={() => setActiveTab('stocks')}
                >
                    📦 Stock Management
                </button>
                <button
                    className={`tab ${activeTab === 'dashboard' ? 'active' : ''}`}
                    onClick={() => setActiveTab('dashboard')}
                >
                    📊 Metrics Dashboard
                </button>
            </div>

            <main className="app-content">
                {activeTab === 'stocks' ? (
                    <div className="stocks-view">
                        <div className="add-stock-section">
                            <StockForm onSubmit={handleCreateStock} />
                        </div>

                        {loading ? (
                            <div className="loading">Loading stocks...</div>
                        ) : error ? (
                            <div className="error-message">
                                Error: {error}
                                <button onClick={fetchStocks} className="btn btn-secondary">
                                    Retry
                                </button>
                            </div>
                        ) : (
                            <StockList stocks={stocks} onRefresh={fetchStocks} />
                        )}
                    </div>
                ) : (
                    <GrafanaDashboard />
                )}
            </main>

            <footer className="app-footer">
                <p>Stock Tracking System v1.0 | Powered by Spring Boot + React + Prometheus + Grafana</p>
            </footer>
        </div>
    );
}
