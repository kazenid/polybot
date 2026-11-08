# PolyBot — Bot Trading Polyester untuk Android

> **Hanya untuk testnet (Paper Assets). Bukan uang sungguhan.**

Bot spot trading 24/7 yang berjalan sebagai APK native Android, terhubung ke **Polyester testnet** melalui API key Ed25519. Dibangun dengan Kotlin + Jetpack Compose, berjalan sebagai Foreground Service, dan dirancang agar tetap hidup di berbagai merek HP Android.

---

## Daftar Isi

- [Fitur](#fitur)
- [Tech Stack](#tech-stack)
- [Arsitektur](#arsitektur)
- [Cara Build](#cara-build)
- [Pengaturan Awal](#pengaturan-awal)
- [Strategi Trading](#strategi-trading)
- [Manajemen Risiko](#manajemen-risiko)
- [Keamanan](#keamanan)
- [Status Pengembangan](#status-pengembangan)
- [Dokumentasi](#dokumentasi)
- [Aturan Keras](#aturan-keras)

---

## Fitur

- **Foreground Service tipe `specialUse`** — berjalan 24/7, tidak dibatasi oleh Android 15+
- **Tiga strategi trading**: Market Maker ringan, Grid, DCA terjadwal
- **Risk Manager ketat**: batas ukuran order, eksposur per aset, rugi harian, error beruntun, kill switch
- **Autentikasi Ed25519**: penandatanganan request sesuai protokol Polyester (canonical query, timestamp unik, nonce aman)
- **Penyimpanan kunci aman**: Android Keystore (AES-GCM), tidak ada plaintext di storage
- **UI monitoring**: Dashboard, Transaksi, Order, Saldo, Log — semuanya read-only; semua kontrol di menu Pengaturan
- **Watchlist multi-simbol** dengan sparkline dan perubahan harga 24 jam
- **Grafik candlestick** dengan pilihan timeframe (1m–1d) dan statistik order book
- **Dry run mode**: simulasi penuh tanpa order nyata, aktif secara default
- **Pemulihan otomatis**: Reconciler dijalankan setelah restart, BootReceiver opsional
- **Laporan PnL per lot (LIFO)**: laba kotor, fee, bersih; ekspor CSV

---

## Tech Stack

| Lapisan | Teknologi |
|---|---|
| Bahasa | Kotlin (JVM 17) |
| UI | Jetpack Compose + Material 3 |
| Arsitektur | MVVM + Clean (data / domain / ui) |
| DI | Hilt 2.57 |
| Async | Kotlin Coroutines + Flow |
| HTTP | OkHttp 4.12 |
| JSON | kotlinx.serialization 1.9 |
| Ed25519 | BouncyCastle `bcprov-jdk18on` 1.81 |
| Hash | `MessageDigest` SHA-256 (JDK bawaan) |
| DB | Room 2.7 (order, fill, log, snapshot) |
| Prefs | DataStore (konfigurasi bot) |
| Keystore | Android Keystore + AES-GCM |
| Background | Foreground Service `specialUse` |
| Test | JUnit 5, MockK, MockWebServer |
| Build | Gradle Kotlin DSL + version catalog |
| CI | GitHub Actions |

- `minSdk 26` · `targetSdk 36` · `compileSdk 36`
- `versionName 0.1.0`

---

## Arsitektur

```
app/src/main/kotlin/app/polybot/
├── PolyBotApp.kt          # Application + Hilt entry point
├── MainActivity.kt
├── di/                    # Modul Hilt
├── core/
│   ├── time/              # Clock, NtpSkewChecker, TimestampAllocator
│   ├── result/            # ApiError, Result sealed class
│   └── log/               # RingLogger (terputar, ukuran terbatas)
├── data/
│   ├── crypto/
│   │   ├── Signer.kt           # Antarmuka tanda tangan
│   │   ├── Ed25519Signer.kt
│   │   ├── RequestSigner.kt    # Canonical string + header X-API-*
│   │   ├── CanonicalQuery.kt   # Percent-encode RFC 3986, sort by key
│   │   └── SecureKeyStore.kt   # Keystore + EncryptedPrefs
│   ├── api/
│   │   ├── ConnectClient.kt    # POST JSON, tanda tangan, retry
│   │   ├── RateLimiter.kt      # Token bucket per kelas (place/cancel)
│   │   ├── ErrorMapper.kt      # HTTP/Connect error -> ApiError
│   │   ├── dto/                # DTO request/respons
│   │   └── services/
│   │       ├── OrdersApi.kt
│   │       ├── MarketDataApi.kt
│   │       ├── LedgerApi.kt
│   │       └── CatalogApi.kt
│   ├── catalog/
│   │   └── CatalogRepository.kt  # symbol -> id, scale, tick/step/min
│   ├── db/                       # Room: Order, Fill, Balance, Equity, Run, Log
│   └── prefs/                    # DataStore: BotConfig, EnvConfig
├── domain/
│   ├── model/             # Price, Qty, Order, Fill, Balance (BigDecimal)
│   ├── numeric/           # Konversi decimal <-> scaled, pembulatan tick/step
│   ├── strategy/
│   │   ├── Strategy.kt    # Antarmuka strategi
│   │   ├── MarketMaker.kt
│   │   ├── Grid.kt
│   │   └── Dca.kt
│   ├── risk/
│   │   ├── RiskManager.kt
│   │   └── RiskRules.kt
│   └── engine/
│       ├── BotEngine.kt        # State machine + loop utama
│       ├── OrderManager.kt     # Idempoten: PENDING -> OPEN, baca balik saat ambigu
│       ├── Reconciler.kt       # Sinkronisasi lokal <-> server
│       └── ClientOrderIds.kt   # Format pb{run}-{seq}, seq persisten di Room
├── service/
│   ├── BotForegroundService.kt  # Foreground Service specialUse
│   ├── StatusNotifier.kt        # Notifikasi status + aksi Stop/Kill Switch
│   └── BootReceiver.kt          # BOOT_COMPLETED + MY_PACKAGE_REPLACED
└── ui/
    ├── theme/
    ├── components/         # StatTile, kartu, kapsul, EmptyState, ikon pair
    ├── dashboard/          # KPI, grafik ekuitas, watchlist, candlestick
    ├── transactions/       # Fill report, filter, PnL, ekspor CSV
    ├── orders/             # Order terbuka & riwayat
    ├── balances/           # Saldo per aset + riwayat
    ├── logs/
    └── settings/
        ├── control/        # Start / Pause / Stop / Kill Switch / Dry Run
        ├── account/        # API key, Account ID, Tes koneksi
        ├── strategy/       # Pilih strategi, simbol, parameter
        ├── risk/           # Parameter risiko
        ├── polling/        # Interval polling & notifikasi
        ├── data/           # Retensi & ekspor
        └── guide/          # Panduan baterai per merek HP
```

**Aturan lapisan:** `domain` tidak bergantung pada `data` atau Android API. `BotEngine` hanya berkomunikasi lewat antarmuka repository sehingga bisa diuji dengan implementasi palsu.

---

## Cara Build

### Prasyarat

- Android Studio Meerkat atau lebih baru (dengan Gradle 8.11+)
- JDK 17
- Android SDK, `minSdk 26`

### Build debug

```bash
./gradlew assembleDebug
```

### Jalankan unit test

```bash
./gradlew test
```

### Jalankan instrumented test (emulator/perangkat)

```bash
./gradlew connectedAndroidTest
```

### CI

GitHub Actions (`.github/workflows/android.yml`) menjalankan build, unit test, dan lint otomatis di setiap push/pull request.

---

## Pengaturan Awal

Ikuti langkah ini sebelum menjalankan bot untuk pertama kali:

1. **Siapkan API key di Polyester**
   - Buat API key di menu API aplikasi Polyester.
   - Aktifkan **Spot trading**, pilih market yang diizinkan, set ukuran order maksimum kecil.
   - Jangan beri izin withdraw/transfer.
   - Pastikan **Paper Assets** sudah ada di saldo **Unified Trading** (klaim harian di UI Polyester, lalu pindah dari Funding → Unified Trading).

2. **Isi API key di aplikasi**
   - Buka **Setelan → Akun & API Key**.
   - Isi API Key ID dan Private Key (64 karakter hex).
   - Tekan **Tes koneksi** — semua baris harus ✓.
   - Cocokkan "Public key turunan" yang tampil dengan yang tercatat di Polyester.

3. **Pengaturan baterai**
   - Buka **Setelan → Panduan baterai**, tekan **Minta bebas optimasi baterai**.
   - Ikuti panduan per merek HP (Xiaomi/Oppo/Vivo/Samsung/Huawei).
   - Aktifkan **Autostart** dan kunci PolyBot di daftar aplikasi terbaru.
   - Lihat [`docs/runbook.md`](docs/runbook.md) untuk prosedur lengkap.

4. **Urutan uji (wajib)**
   1. Tes koneksi bersih
   2. Dry run 30 menit — periksa tab Log (tidak ada error merah)
   3. Uji order manual satu kali lewat **Setelan → Uji order manual**
   4. Live kecil 1 jam, bandingkan tab Order/Transaksi dengan aplikasi Polyester
   5. Soak test 24 jam (lihat [`docs/runbook.md`](docs/runbook.md) bagian 4)

---

## Strategi Trading

### A — Market Maker (default)

Memasang quote bid dan ask simetris di sekitar harga tengah. Menggunakan `post_only=true` agar selalu sebagai maker.

| Parameter | Keterangan |
|---|---|
| `halfSpreadBps` | Setengah lebar spread (harus > fee maker + margin) |
| `orderQuote` | Nilai quote per order |
| `levels` | Jumlah level (1–3) |
| `levelStepBps` | Jarak antar level |
| `requoteThresholdBps` | Ambang untuk memasang ulang quote |
| `maxOrderAgeSec` | Umur order maksimum sebelum diganti |
| `maxInventoryPct` | Batas inventori (tahan satu sisi jika melewati) |
| `skewFactor` | Faktor koreksi quote berdasarkan posisi inventori |

**Profil siap pakai:** Hati-hati / Seimbang / Agresif (satu ketukan mengisi semua parameter).

### B — Grid

Memasang limit buy di level bawah harga dan limit sell di atas. Saat satu level terisi, langsung pasang order lawan di level berdampingan. Berhenti jika harga keluar rentang melebihi `rangeBreakBps`.

### C — DCA Terjadwal

Membeli berkala dengan limit `post_only` sedikit di bawah best bid. Berhenti saat total pembelian mencapai `maxTotalQuote`. Cocok untuk akumulasi bertahap.

---

## Manajemen Risiko

Risk Manager berjalan sebelum setiap order dikirim:

| Aturan | Default |
|---|---|
| Ukuran maks per order | ~1–2% saldo |
| Eksposur maks per aset | 20% |
| Order terbuka maks | min(6, batas server) |
| Penyimpangan harga dari mid | tolak jika > 2% |
| Rugi harian maks | stop + cancel-all |
| Error beruntun | 5 → PAUSED |
| Selisih jam | > 3 detik → PAUSED |
| Spread anomali (kosong, crossed) | PAUSED |

**Kill switch** tersedia di notifikasi foreground (tanpa membuka aplikasi) dan di **Setelan → Kontrol Bot**. Membatalkan semua order bot lalu pindah ke state `STOPPED`.

---

## Keamanan

- Private key diimpor sekali (tempel atau QR), **tidak pernah disimpan plaintext**.
- Dienkripsi dengan AES-GCM menggunakan kunci yang ada di Android Keystore.
- `android:allowBackup="false"` — kunci tidak masuk backup cloud.
- `FLAG_SECURE` pada layar yang menampilkan kunci (tidak bisa screenshot).
- Private key tidak pernah masuk log, crash report, atau pesan error.
- Gunakan API key khusus bot dengan izin minimal — tanpa izin withdraw/transfer.
- Jangan menyimpan key di repo atau file konfigurasi yang ikut di-commit.
- `.gitignore` sudah mengecualikan `*.keystore`, `local.properties`, `.env`.
- TLS default dipakai; cleartext diblokir (`usesCleartextTraffic=false`).

---

## Status Pengembangan

| Fase | Deskripsi | Status |
|---|---|---|
| 0 | Riset API (endpoint, bentuk JSON, error) | ✅ Selesai (sebagian menunggu API key) |
| 1 | Fondasi: Gradle, Hilt, Compose, CI, signing, SecureKeyStore | ✅ Selesai |
| 2 | Klien API read-only, katalog, order book, candle, UI dasar | ✅ Selesai |
| 3 | OrderManager idempoten, Reconciler, ClientOrderIds, Room | ✅ Selesai |
| 4 | Engine, RiskManager, strategi, simulasi FakeExchange, laporan | ✅ Selesai (menunggu uji live) |
| 5 | Foreground Service, wake lock, NetworkMonitor, pemulihan | ✅ Selesai (menunggu uji 24 jam di HP nyata) |
| — | Rombak UI: tema gelap/terang, watchlist, candlestick, profil MM | ✅ Selesai |
| 6 | Realtime WebSocket (protobuf, opsional) | ⬜ Belum dikerjakan |

**Hal yang belum diuji di perangkat nyata:**
- Uji koneksi dengan API key sungguhan ke testnet
- Order live ke testnet (fase 3)
- Soak test 24 jam (fase 5)
- Tampilan UI di layar HP fisik

---

## Dokumentasi

| File | Isi |
|---|---|
| [`plan.md`](plan.md) | Rencana proyek lengkap: API, arsitektur, fase, checklist |
| [`docs/api-notes.md`](docs/api-notes.md) | Catatan endpoint, bentuk JSON, error, contoh respons |
| [`docs/runbook.md`](docs/runbook.md) | Panduan menjalankan bot, pengaturan baterai per merek HP, uji 24 jam |

---

## Aturan Keras

> Pelanggaran aturan ini dapat menyebabkan banned dari Polyester.

- **Dilarang wash trading** — jangan beli dan jual ke akun sendiri untuk menggelembungkan volume.
- **Dilarang spam order** — jangan pasang dan batalkan order berulang tanpa tujuan trading.
- **Satu akun, satu API key, satu proses bot** — satu key di dua perangkat bisa menghasilkan autentikasi duplikat.
- Bot tidak boleh mengirim order di luar batas yang diatur di API-key policy dan Risk Manager.
- Baca aturan resmi kompetisi/reward Polyester di Discord atau X sebelum menggunakan bot untuk hadiah.

---

## Lingkungan

| | API | Chain |
|---|---|---|
| **Testnet** (dipakai bot) | `https://api.testnet.polyester.com` | 888169 |
| Devnet | `https://api.devnet.polyester.com` | 888168 |

Acuan SDK: **`polyester-sdk 0.3.0`** (31 Okt 2026). SDK berstatus pre-1.0 dengan lisensi proprietary — pin versi dan pantau [CHANGELOG](https://github.com/Fabric-Labs/polyester-sdk-python/blob/main/CHANGELOG.md) di setiap rilis.

---

<sub>Dibuat oleh Azzurahai · PolyBot v0.1.0 · Hanya untuk testnet</sub>
