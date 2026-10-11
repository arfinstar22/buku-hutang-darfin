-- ====================================================================
-- SKRIP PENGAMANAN AKSES DATABASE SUPABASE (RLS & PASSKEY ENFORCEMENT)
-- Proyek : Buku Hutang Darfin (Sinkronisasi HP Tuan Darfin & HP Ibu)
-- 
-- CARA AKTIFKAN:
-- 1. Buka https://supabase.com/dashboard
-- 2. Pilih project Buku Hutang ("fislizbynjisvwwdflaq")
-- 3. Di menu sebelah kiri, klik "SQL Editor"
-- 4. Buat query baru, tempel (paste) seluruh teks ini, lalu klik "RUN"
-- ====================================================================

-- LANGKAH 1: FUNGSI BANTU MEMBACA HEADER 'x-sync-secret' DENGAN AMAN
CREATE OR REPLACE FUNCTION get_sync_secret()
RETURNS TEXT
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN coalesce(
        nullif(current_setting('request.headers', true), '')::json->>'x-sync-secret',
        ''
    );
EXCEPTION
    WHEN OTHERS THEN
        RETURN '';
END;
$$;

-- LANGKAH 2: AKTIFKAN ROW LEVEL SECURITY (RLS) DI KEDUA TABEL
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE debtors ENABLE ROW LEVEL SECURITY;

-- LANGKAH 3: HAPUS POLICY LAMA (JIKA ADA) AGAR BERSIH
DROP POLICY IF EXISTS "Allow select transactions with secret" ON transactions;
DROP POLICY IF EXISTS "Allow insert transactions with secret" ON transactions;
DROP POLICY IF EXISTS "Allow select debtors with secret" ON debtors;
DROP POLICY IF EXISTS "Allow insert debtors with secret" ON debtors;
DROP POLICY IF EXISTS "Allow update debtors with secret" ON debtors;
DROP POLICY IF EXISTS "Public access transactions" ON transactions;
DROP POLICY IF EXISTS "Public access debtors" ON debtors;

-- ====================================================================
-- KEBIJAKAN PENGAMANAN TABEL TRANSACTIONS
-- ====================================================================

-- 1. ANTI-HAPUS & ANTI-UBAH (IMMUTABILITY)
-- KETENTUAN: Tidak ada policy UPDATE atau DELETE yang dibuat!
-- HASIL: Siapapun (termasuk jika anon key bocor) TIDAK DAPAT menghapus
-- atau memodifikasi transaksi yang sudah dicatat. Buku hutang 100% aman dari penghapusan massal.

-- 2. BACA (SELECT): Hanya jika mengirimkan x-sync-secret yang valid
CREATE POLICY "Allow select transactions with secret"
ON transactions
FOR SELECT
TO anon, authenticated
USING (
    get_sync_secret() = 'darfin_sync_secure_2026'
);

-- 3. CATAT TRANSAKSI BARU (INSERT): Wajib kunci rahasia + validasi data logis
CREATE POLICY "Allow insert transactions with secret"
ON transactions
FOR INSERT
TO anon, authenticated
WITH CHECK (
    get_sync_secret() = 'darfin_sync_secure_2026'
    AND amount >= 0
    AND type IN ('DEBT', 'PAYMENT', 'SETTLED')
    AND length(trim(debtor_name)) > 0
);

-- ====================================================================
-- KEBIJAKAN PENGAMANAN TABEL DEBTORS (RINGKASAN KONTAK)
-- ====================================================================

CREATE POLICY "Allow select debtors with secret"
ON debtors
FOR SELECT
TO anon, authenticated
USING (
    get_sync_secret() = 'darfin_sync_secure_2026'
);

CREATE POLICY "Allow insert debtors with secret"
ON debtors
FOR INSERT
TO anon, authenticated
WITH CHECK (
    get_sync_secret() = 'darfin_sync_secure_2026'
);

CREATE POLICY "Allow update debtors with secret"
ON debtors
FOR UPDATE
TO anon, authenticated
USING (
    get_sync_secret() = 'darfin_sync_secure_2026'
)
WITH CHECK (
    get_sync_secret() = 'darfin_sync_secure_2026'
);
