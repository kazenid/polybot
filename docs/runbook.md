# Runbook PolyBot

Panduan menjalankan bot di HP, menguji ketahanannya 24 jam, dan memecahkan masalah. Semua langkah memakai **testnet (Paper Assets)**.

## 1. Perangkat

- Pakai HP khusus, tercolok charger, di tempat sejuk dan berventilasi. Batasi pengisian sekitar 80% bila HP mendukung.
- Aktifkan **waktu otomatis** (Pengaturan > Tanggal & waktu). Server menolak tanda tangan bila jam melenceng > 10 detik; bot menjeda diri bila selisih > 3 detik.
- Satu API key = satu proses bot. Jangan menjalankan key yang sama di dua perangkat.

## 2. Pengaturan sekali di awal

1. **Setelan > Akun & API Key**: isi API key id dan private key, Simpan, lalu **Tes koneksi**. Semua baris harus ✓ (Katalog, Autentikasi, Baca saldo, Baca order terbuka). Bandingkan "Public key turunan" dengan yang tercatat di Polyester.
2. **Setelan > Panduan baterai**: tekan **Minta bebas optimasi baterai** sampai status "Ya", izinkan notifikasi, lalu ikuti langkah untuk merek HP Anda (Autostart, "Tanpa batasan", kunci di recents).
3. **Setelan > Strategi dan Risiko**: periksa angka. Default sengaja kecil.

### POCO / Xiaomi / Redmi (HyperOS atau MIUI)

1. Info aplikasi PolyBot > **Penghemat baterai** > **Tanpa batasan**.
2. Info aplikasi PolyBot > **Mulai otomatis**: aktif.
3. Buka daftar aplikasi terbaru, tahan kartu PolyBot, pilih **Kunci**.
4. Jangan geser-hapus PolyBot dari daftar aplikasi terbaru saat bot berjalan.

## 3. Urutan uji (jangan dilewati)

1. **Tes koneksi** bersih.
2. **Dry run 30 menit**: Setelan > Kontrol Bot > Start (dry run). Periksa tab Log (tidak ada baris merah), Dashboard (ekuitas bergerak, grafik terisi).
3. **Uji order manual**: Setelan > Uji order manual. Pasang satu order kecil, batalkan, rekonsiliasi. Pastikan order muncul dan hilang di aplikasi Polyester.
4. **Live kecil**: matikan dry run, ukuran order kecil, pantau 1 jam. Bandingkan tab Order dan Transaksi dengan aplikasi Polyester.
5. **Uji 24 jam** (bagian 4), dulu dalam dry run, lalu live.

## 4. Uji 24 jam (soak)

Tujuan: membuktikan bot tidak mati diam-diam, tidak bocor memori, dan tidak membuat order ganda.

### Persiapan

- HP tercolok charger, layar dibiarkan mati setelah Start, jaringan Wi-Fi stabil.
- Catat di awal: jam mulai, persen baterai, versi aplikasi (Setelan > Tentang).
- Siapkan komputer dengan `adb` bila ingin memantau memori:

```bash
adb shell dumpsys meminfo app.polybot | grep -E "TOTAL|Java Heap|Native Heap"
```

### Pengamatan (0, 1, 6, 12, 24 jam)

| Yang dicek | Di mana | Lolos bila |
|---|---|---|
| Notifikasi status masih ada | Bilah notifikasi | Ada, jamnya diperbarui tiap ~30 detik |
| Status bot | Setelan > Kontrol Bot | "Berjalan" (atau dijeda otomatis lalu pulih) |
| Jumlah siklus | Kontrol Bot > Siklus | Terus bertambah |
| Kontinuitas ekuitas | Dashboard > Ekuitas 24 jam | Sekitar 1 titik per menit, tanpa celah > 3 menit |
| Log | Tab Log, filter Peringatan+ | Tidak ada galat berulang |
| Order ganda | Tab Order vs Log | Tidak ada dua order dengan niat sama |
| Memori | `dumpsys meminfo` | Pertumbuhan wajar, tidak naik terus-menerus |
| Baterai | Pengaturan HP | Konsumsi terukur dan masuk akal |

### Lolos bila

- Tidak pernah **GALAT FATAL**; setiap jeda otomatis pulih sendiri.
- Tidak ada celah ekuitas yang menandakan proses mati.
- Setelah uji pemulihan (bagian 5) bot lanjut dan Log mencatat "Sesi dipulihkan otomatis".
- Order di Polyester sama dengan tab Order (tidak ada yatim yang tak terhitung).

Catat hasilnya (jam, observasi, anomali). Bila gagal, simpan isi Log (ekspor dengan menyalin) dan model HP.

## 5. Uji pemulihan

Proses yang dimatikan sistem harus bisa lanjut.

**Dry run** (aman, otomatis dipulihkan):

```bash
adb shell run-as app.polybot kill $(adb shell pidof app.polybot)
```

Harapan: dalam beberapa detik notifikasi kembali dan Log menulis "Sesi dipulihkan otomatis setelah proses dihentikan". Perintah ini hanya berlaku untuk build debug.

**Live**: sesi live dipulihkan otomatis **hanya bila** Setelan > Strategi > "Pulihkan sesi LIVE otomatis" aktif, sesi terakhir kurang dari 6 jam, dan Anda tidak menekan Stop. Sebaliknya bot tetap berhenti dan mencatat alasannya di Log.

**Restart HP**: restart saat dry run berjalan. Harapan: sesi dilanjutkan setelah HP menyala (bila izin Autostart aktif). Untuk live berlaku aturan yang sama dengan di atas.

Setelah setiap pemulihan, engine merekonsiliasi order dengan server **sebelum** mengirim order baru. Order bot lama yang masih terbuka diadopsi, order manual tidak disentuh.

## 6. Penghentian darurat

- **Stop**: berhenti rapi dan membatalkan order milik bot (bila "Batalkan order saat berhenti" aktif).
- **Kill Switch**: dari layar Kontrol Bot atau tombol di notifikasi. Membatalkan order milik bot. Order manual di pair itu ikut dibatalkan **hanya** bila opsi "Kill switch membatalkan SEMUA order" aktif.
- Jika aplikasi tidak bisa dibuka: cabut API key di aplikasi Polyester (API > key > nonaktifkan).

## 7. Masalah umum

| Gejala | Kemungkinan penyebab | Tindakan |
|---|---|---|
| Notifikasi hilang, bot berhenti | Sistem mematikan proses | Ulangi Panduan baterai; kunci di recents; cek Autostart |
| "Dijeda otomatis: Tidak ada jaringan" | Wi-Fi putus | Pulih sendiri saat jaringan kembali |
| "Dijeda otomatis: Jam perangkat melenceng" | Waktu manual atau salah | Aktifkan waktu otomatis |
| "Order book crossed" atau kosong | Pasar testnet tipis atau pair dihentikan | Pulih sendiri; cek status pair |
| "5 error beruntun" | API bermasalah, izin berubah | Buka Log; tekan Lanjut setelah penyebab hilang |
| GALAT FATAL (izin ditolak) | Policy API key berubah atau key dinonaktifkan | Perbaiki policy atau key, lalu Start lagi |
| "Rugi harian ... mencapai batas" | Batas rugi harian tercapai | Bot berhenti dan membatalkan order; periksa strategi sebelum Start |
| Notifikasi tidak muncul | Izin notifikasi ditolak | Panduan baterai > Buka pengaturan notifikasi |
| "Hasil order belum pasti" | Timeout saat kirim order | Jangan kirim ulang manual; bot merekonsiliasi pada siklus berikutnya |
