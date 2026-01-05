import React, { useState } from 'react';

export default function StockForm({ onSubmit, initialStock = null, onCancel }) {
    const [formData, setFormData] = useState(
        initialStock || { productName: '', quantity: 0, price: 0 }
    );
    const [error, setError] = useState('');

    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({
            ...prev,
            [name]: name === 'productName' ? value : parseFloat(value) || 0
        }));
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');

        if (!formData.productName.trim()) {
            setError('Product name is required');
            return;
        }
        if (formData.quantity < 0) {
            setError('Quantity must be positive');
            return;
        }
        if (formData.price < 0) {
            setError('Price must be positive');
            return;
        }

        try {
            await onSubmit(formData);
            if (!initialStock) {
                setFormData({ productName: '', quantity: 0, price: 0 });
            }
        } catch (err) {
            setError(err.message);
        }
    };

    return (
        <form onSubmit={handleSubmit} className="stock-form">
            <h3>{initialStock ? '✏️ Update Stock' : '➕ Add New Stock'}</h3>

            {error && <div className="error-message">{error}</div>}

            <div className="form-group">
                <label htmlFor="productName">Product Name</label>
                <input
                    type="text"
                    id="productName"
                    name="productName"
                    value={formData.productName}
                    onChange={handleChange}
                    placeholder="Enter product name"
                    required
                />
            </div>

            <div className="form-row">
                <div className="form-group">
                    <label htmlFor="quantity">Quantity</label>
                    <input
                        type="number"
                        id="quantity"
                        name="quantity"
                        value={formData.quantity}
                        onChange={handleChange}
                        min="0"
                        required
                    />
                </div>

                <div className="form-group">
                    <label htmlFor="price">Price ($)</label>
                    <input
                        type="number"
                        id="price"
                        name="price"
                        value={formData.price}
                        onChange={handleChange}
                        min="0"
                        step="0.01"
                        required
                    />
                </div>
            </div>

            <div className="form-actions">
                <button type="submit" className="btn btn-primary">
                    {initialStock ? 'Update' : 'Add Stock'}
                </button>
                {initialStock && (
                    <button type="button" onClick={onCancel} className="btn btn-secondary">
                        Cancel
                    </button>
                )}
            </div>
        </form>
    );
}
