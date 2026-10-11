-- Schema Supabase PostgreSQL untuk Buku Hutang Darfin
-- Jalankan di SQL Editor Supabase (Dashboard -> SQL Editor)

CREATE TABLE IF NOT EXISTS debtors (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    canonical_key TEXT UNIQUE NOT NULL,
    total_debt BIGINT DEFAULT 0,
    last_note TEXT,
    updated_at BIGINT DEFAULT (extract(epoch from now()) * 1000)
);

CREATE TABLE IF NOT EXISTS transactions (
    uuid TEXT PRIMARY KEY,
    debtor_id TEXT REFERENCES debtors(id) ON DELETE CASCADE,
    debtor_name TEXT NOT NULL,
    canonical_key TEXT NOT NULL,
    amount BIGINT NOT NULL,
    type TEXT NOT NULL, -- 'DEBT', 'PAYMENT', 'SETTLED'
    note TEXT,
    created_at TEXT NOT NULL,
    timestamp BIGINT NOT NULL
);

-- Indexing untuk query cepat
CREATE INDEX IF NOT EXISTS idx_tx_timestamp ON transactions(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_debtors_total ON debtors(total_debt DESC);
