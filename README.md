# Buku Hutang Darfin 📱💚

Aplikasi pencatat hutang piutang keluarga & warung berbasis Android dengan antarmuka ramah orang tua, parser bahasa alami pintar (NLP Indonesia & Sulawesi), dan sinkronisasi real-time multi-device antar-HP (Tuan Darfin & Ibu).

---

## 🌟 Fitur Utama

1. **Parser Pintar Bahasa Alami**:
   - Ketik santai tanpa ribet:
     - `budi 50rb` ➔ Catat hutang Rp 50.000 atas nama Budi
     - `tanto cicil 20k` ➔ Potong hutang Rp 20.000
     - `mama yesti 1.5jt beli semen` ➔ Catat hutang Rp 1.500.000 dengan catatan "beli semen"
     - `latanto lunas` ➔ Reset hutang jadi Rp 0
2. **Normalisasi Nama Sulawesi**:
   - `La tanto` == `latanto` == `tanto` == `La Ode tanto` otomatis dikenali sebagai orang yang sama.
   - Pembedaan gelar keluarga tetap presisi: `mama yesti` != `bapak yesti` (tersimpan sebagai akun terpisah).
3. **Desain Ramah Ibu (Non-Flat & High Contrast)**:
   - Skema warna Gojek Green (`#00AA13`).
   - Tipografi besar (34sp) dengan kontras tinggi.
   - Kartu timbul (elevasi 5dp) dengan sudut melengkung 18dp.
   - **Filter Cepat**: `[Semua]` • `[Belum Lunas]` • `[Lunas]`.
   - **Avatar Inisial**: Lingkaran avatar otomatis dari inisial nama.
   - **Aksi Cepat di Halaman Detail**: Tombol `✓ Catat Bayar` dan `+ Tambah Hutang` berdampingan.
4. **Sinkronisasi Real-Time Multi-Device**:
   - **Offline-First**: Tetap bisa mencatat saat tanpa sinyal internet (tersimpan di SQLite lokal).
   - **Otomatis Sync**: Begitu ada koneksi, transaksi disinkronkan tiap 6 detik di latar belakang.
   - Tuan Darfin mencatat di HP-nya ➔ HP Ibu langsung update otomatis dalam hitungan detik.

---

## 📦 Lokasi File Siap Pakai

- **Android Debug APK**: `app/build/outputs/apk/debug/app-debug.apk` (6.2 MB)
- **Master Vector Logo & Icon**: `assets/logo/logo-master.svg` & `assets/logo/app-icon.svg`
- **Server Sync Node.js**: `src/server/syncServer.js`
- **Blueprint Render (Deploy Gratis 1-Klik)**: `render.yaml`
- **Skema Database Supabase**: `src/db/supabase_schema.sql`

---

## 🚀 Panduan Penggunaan & Pemasangan

### 1. Pasang APK ke HP Ibu & HP Tuan Darfin
1. Salin file `app/build/outputs/apk/debug/app-debug.apk` ke HP Android.
2. Buka file APK di HP, pilih **Install**.
3. Jika muncul peringatan keamanan Google Play Protect, pilih **Install anyway / Tetap install** (karena APK debug ditandatangani dengan debug key).

### 2. Mengaktifkan Sinkronisasi Real-Time Antar-HP

#### Opsi A: Menggunakan Wi-Fi Lokal (Gratis & Cepat)
1. Jalankan sync server di laptop/komputer yang satu jaringan Wi-Fi:
   ```bash
   npm start
   ```
2. Cari IP komputer (contoh: `http://192.168.1.5:3000`).
3. Di aplikasi Android, klik ikon **⚙️ Pengaturan** di pojok kanan atas.
4. Masukkan URL server: `http://192.168.1.5:3000` lalu klik **Simpan & Tes**.
5. Status akan berubah menjadi `🟢 Real-time Aktif`.

#### Opsi B: Deploy Gratis ke Cloud (Render.com)
1. Push repository ke akun GitHub Tuan Darfin (`arfinstar22`).
2. Masuk ke [dashboard.render.com](https://dashboard.render.com), pilih **New Web Service**, dan hubungkan repo ini. Render akan otomatis mendeteksi file `render.yaml`.
3. Setelah deploy selesai, Render akan memberikan URL publik (contoh: `https://buku-hutang-darfin.onrender.com`).
4. Masukkan URL tersebut di menu pengaturan aplikasi di HP Tuan Darfin dan HP Ibu. Kedua HP sekarang terhubung online 24 jam gratis!

---

## 🧪 Menjalankan Pengujian (Testing)

Semua fungsionalitas memiliki unit test otomatis yang teruji 100%:
```bash
npm test
```

Tes mencakup:
- Ekstraksi nominal rupiah (`50rb`, `50k`, `1.5jt`, `Rp 50.000`).
- Aksi pembayaran & pelunasan.
- Aturan nama Sulawesi & gelar keluarga.
- Sinkronisasi server batch merge & perhitungan saldo.

---

## 👨‍💻 Profil & Pembuat
- **Nama**: Tuan Darfin (Mahasiswa Teknik Informatika)
- **GitHub**: `arfinstar22`
- **Email**: `darfinstar22@gmail.com`
