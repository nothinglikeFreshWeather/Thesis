import React, { useState } from 'react';
import { stockApi } from '../api/stockApi';
import StockForm from './StockForm';

export default function StockList({ stocks, onRefresh }) {
    const [editingStock, setEditingStock] = useState(null);
    const [message, setMessage] = useState({ text: '', type: '' });

    const showMessage = (text, type = 'success') => {
        setMessage({ text, type });
        setTimeout(() => setMessage({ text: '', type: '' }), 3000);
    };

    const handleUpdate = async (formData) => {
        try {
            await stockApi.update(editingStock.id, formData);
            showMessage('Stock updated successfully!');
            setEditingStock(null);
            onRefresh();
        } catch (err) {
            showMessage(err.message, 'error');
            throw err;
        }
    };

    const handleDelete = async (id, productName) => {
        if (!confirm(`Are you sure you want to delete "${productName}"?`)) {
            return;
        }

        try {
            await stockApi.delete(id);
            showMessage('Stock deleted successfully!');
            onRefresh();
        } catch (err) {
            showMessage(err.message, 'error');
        }
    };

    if (editingStock) {
        return (
            <div className="stock-list">
                <StockForm
                    initialStock={editingStock}
                    onSubmit={handleUpdate}
                    onCancel={() => setEditingStock(null)}
                />
            </div>
        );
    }

    return (
        <div className="stock-list">
            {message.text && (
                <div className={`message ${message.type}`}>
                    {message.text}
                </div>
            )}

            <h3>📦 Stock Inventory</h3>

            {stocks.length === 0 ? (
                <div className="empty-state">
                    <p>No stocks available. Add your first stock above!</p>
                </div>
            ) : (
                <div className="table-container">
                    <table className="stock-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>Product Name</th>
                                <th>Quantity</th>
                                <th>Price</th>
                                <th>Created At</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {stocks.map(stock => (
                                <tr key={stock.id}>
                                    <td>{stock.id}</td>
                                    <td className="product-name">{stock.productName}</td>
                                    <td>{stock.quantity}</td>
                                    <td>${stock.price.toFixed(2)}</td>
                                    <td>{new Date(stock.createdAt).toLocaleDateString()}</td>
                                    <td className="actions">
                                        <button
                                            onClick={() => setEditingStock(stock)}
                                            className="btn btn-edit"
                                            title="Edit"
                                        >
                                            ✏️
                                        </button>
                                        <button
                                            onClick={() => handleDelete(stock.id, stock.productName)}
                                            className="btn btn-delete"
                                            title="Delete"
                                        >
                                            🗑️
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
