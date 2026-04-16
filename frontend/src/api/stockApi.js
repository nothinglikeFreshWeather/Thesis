const API_BASE = '/api';

export const stockApi = {
    async getAll() {
        const response = await fetch(`${API_BASE}/stocks`);
        if (!response.ok) throw new Error('Failed to fetch stocks');
        return response.json();
    },

    async getById(id) {
        const response = await fetch(`${API_BASE}/stocks/${id}`);
        if (!response.ok) throw new Error('Failed to fetch stock');
        return response.json();
    },

    async create(stock) {
        const response = await fetch(`${API_BASE}/stocks`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(stock)
        });
        if (!response.ok) {
            const error = await response.text();
            throw new Error(error || 'Failed to create stock');
        }
        return response.json();
    },

    async update(id, stock) {
        const response = await fetch(`${API_BASE}/stocks/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(stock)
        });
        if (!response.ok) {
            const error = await response.text();
            throw new Error(error || 'Failed to update stock');
        }
        return response.json();
    },

    async delete(id) {
        const response = await fetch(`${API_BASE}/stocks/${id}`, {
            method: 'DELETE'
        });
        if (!response.ok) throw new Error('Failed to delete stock');
    }
};
