# PolyBot Android — Rencana Proyek

Bot trading **testnet Polyester** yang berjalan 24/7 di Android sebagai APK native (Kotlin), memakai API key (Ed25519), dengan Foreground Service dan notifikasi status.

> **Untuk Claude Code:** baca seluruh dokumen ini sebelum menulis kode. Kerjakan per fase (bagian 14), jangan melompat. Item bertanda **[VERIFIKASI]** belum terkonfirmasi dari dokumentasi, jadi cek dulu (bagian 4) sebelum mengimplementasikannya.

---

## 1. Tujuan & Ruang Lingkup

**Tujuan**
- Menjalankan bot spot trading di **testnet** Polyester (Paper Assets, bukan uang sungguhan) secara stabil 24/7 dari HP Android.
- Bot disiplin: strategi sederhana, manajemen risiko ketat, tidak asal buy/sell.
- UI minimal: login key, konfigurasi strategi, start/stop, kill switch, log, notifikasi status.

**Di luar lingkup (v1)**
- Mainnet / uang sungguhan.
- Margin, perpetual, leverage.
- Deposit/withdraw on-chain, wallet login, MFA (SDK API-key memang tidak mendukungnya).
- Multi-akun.

**Aturan keras (jangan dilanggar)**
- **Dilarang wash trading:** jangan jual-beli ke diri sendiri atau antar akun milik sendiri untuk menggelembungkan volume.
- **Dilarang spam order:** hindari pola pasang lalu langsung batal berulang tanpa tujuan trading.
- **Satu akun, satu API key, satu proses bot.** Protokol tanda tangan tidak punya nonce lintas proses, jadi satu key yang dipakai di dua proses bisa menghasilkan tuple autentikasi identik.
- Bot tidak boleh mengirim order di luar batas yang diatur di API-key policy dan Risk Manager.
- Baca aturan resmi kompetisi/reward di Discord atau X Polyester sebelum dipakai untuk hadiah.

---

## 2. Referensi & Dokumentasi

| Sumber | Link |
|---|---|
| Dokumentasi developer (capability matrix SDK) | https://polyester.ai/docs/developer-docs/getting-started/sdk-capability-matrix |
| Market Order Price Protection | https://polyester.ai/developer-docs/shared-concepts/market-order-price-protection |
| Dokumentasi SDK Rust | https://polyester.ai/docs/sdk/rust/get-started/overview |
| SDK Python (README lengkap, sumber utama) | https://github.com/Fabric-Labs/polyester-sdk-python |
| SDK Go | https://github.com/Fabric-Labs/polyester-sdk-go |
| SDK Rust | https://github.com/Fabric-Labs/polyester-sdk-rust |
| Contoh resmi (REST, realtime, order, bots) | https://github.com/Fabric-Labs/polyester-examples-python |
| Source penandatanganan request (Rust) | https://docs.rs/polyester-sdk/latest/src/polyester/auth.rs.html |
| Paket PyPI | https://pypi.org/project/polyester-sdk/ |
| Crate Rust | https://crates.io/crates/polyester-sdk |

| CHANGELOG SDK Python (cek tiap rilis) | https://github.com/Fabric-Labs/polyester-sdk-python/blob/main/CHANGELOG.md |

Catatan:
- SDK berstatus **pre-1.0** (lisensi proprietary). Acuan plan ini: **`polyester-sdk==0.3.0`** (31 Okt 2026). Rilis minor bisa breaking, jadi pin versi, baca CHANGELOG, dan bungkus semua panggilan API di satu lapisan supaya mudah disesuaikan.
- Dokumentasi di `polyester.ai/docs` sekarang **di balik login Cloudflare Access** (per 31 Okt 2026). Sumber yang bisa diakses publik: README/CHANGELOG SDK di GitHub, PyPI, docs.rs.
- `polyester-examples-python` menarget **devnet** dan hanya mensyaratkan `>=0.1.0a45`, jadi bisa tertinggal dari perubahan breaking 0.2.0. Jika bertentangan, ikuti SDK 0.3.0.

---

## 3. Fakta API yang Sudah Terverifikasi

Dari README SDK Python/Go/Rust dan source `auth.rs`:

**Autentikasi (API key, Ed25519)**
- Header: `X-API-KEY-ID`, `X-API-TIMESTAMP`, `X-API-SIGNATURE`.
- String yang ditandatangani = lima bagian digabung dengan `\n`:
  1. timestamp dalam milidetik (string)
  2. method HTTP huruf besar (`POST`)
  3. path URL (default `/` jika kosong)
  4. query kanonik (pasangan key=value diurutkan menurut key lalu value, tiap komponen di-percent-encode; karakter tak-dicadangkan RFC 3986 `A-Z a-z 0-9 - . _ ~` tetap literal; spasi jadi `%20`)
  5. SHA-256 dari **byte body persis** yang dikirim, dalam hex huruf kecil
- Tanda tangan = Ed25519 atas string UTF-8 tersebut, dikirim sebagai **hex 128 karakter**.
- Private key = seed Ed25519 **64 karakter hex (32 byte)**, ditampilkan sekali saat key dibuat.
- Jendela kesegaran server: **10 detik**. Timestamp boleh mendahului jam lokal paling banyak 5 detik. Setiap request identik harus mendapat timestamp unik (naikkan +1 ms jika bentrok).
- Body yang ditandatangani harus **byte yang sama** dengan yang dikirim. Serialisasi JSON sekali, hitung hash dari byte itu, kirim byte itu. Jangan biarkan HTTP client meng-encode ulang.

**Test vector (dari source resmi, wajib dijadikan unit test)**
```
timestamp = "123"
method    = "post"                      -> "POST"
url       = "https://api.example.test/foo/bar?b=2&a=1"
body      = "{}"

canonical = "123\nPOST\n/foo/bar\na=1&b=2\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
```
Vektor query lain:
- `?z=1&a=hello world&m=a+b` -> `a=hello%20world&m=a%20b&z=1`
- `?z=1&a=hello%20world&m=a%2Bb` -> `a=hello%20world&m=a%2Bb&z=1`
- `?b=&a=1` -> `a=1&b=`
- `?a=1&a=2&b=0` -> `a=1&a=2&b=0`
- `?path=foo/bar&name=a_b.c~d-e` -> `name=a_b.c~d-e&path=foo%2Fbar`
- `?msg=%E2%9C%93&plain=ok` -> `msg=%E2%9C%93&plain=ok`

Vektor encode komponen: `api-keys` -> `api-keys` (`-` wajib literal), `hello world` -> `hello%20world`, `a+b` -> `a%2Bb`, `private:auth:api-keys:account:proto` -> `private%3Aauth%3Aapi-keys%3Aaccount%3Aproto`. Catatan: `+` di query input di-decode jadi spasi sebelum di-encode ulang.

Body kosong: path `/orders.v1.OrdersService/CreateOrder`, body kosong -> baris ke-5 = SHA-256 input kosong (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`).

**Environment**
- Testnet publik: `https://api.testnet.polyester.com` (WebSocket `wss://` padanannya), chain `888169`. Konstanta SDK: `POLYESTER_TESTNET_ENVIRONMENT`.
- Devnet: `api.devnet.polyester.com` (`POLYESTER_DEVNET_ENVIRONMENT`, default konstruktor SDK). Host lama `api-testnet...` / `api-devnet.polyester.ai` **tidak dipakai lagi**.
- Variabel lingkungan SDK (acuan nama saja): `POLYESTER_ENV=devnet|testnet`, `POLYESTER_API_KEY_ID`, `POLYESTER_API_PRIVATE_KEY`, `POLYESTER_ACCOUNT_ID`, `POLYESTER_API_URL`, `POLYESTER_WS_URL`.

**Transport**
- Connect RPC lewat HTTP. Contoh path: `/orders.v1.OrdersService/CreateOrder`, `/auth.v1.AuthService/Me`.
- Format kawat default SDK = **protobuf biner**; SDK menyediakan `wire_format="json"` yang disebut **untuk debugging**, jadi Connect JSON didukung tapi bukan jalur utama. Untuk v1 Android, pakai JSON di balik antarmuka `Codec` supaya bisa diganti protobuf tanpa mengubah lapisan atas. Sejak 0.2.0, field dan nama enum tak dikenal di JSON harus di-decode dengan aman (enum tak dikenal = unspecified), jadi parser wajib `ignoreUnknownKeys`.
- **Opsi protobuf:** skema bisa diturunkan dari kode generated SDK (`src/polyester/gen/`, descriptor `_pb2`) lalu dibuat kelas Kotlin dengan Wire / protobuf-lite. Cek dulu lisensi proprietary sebelum menyalin skema ke repo.
- **Realtime (Centrifugo) hanya protobuf biner** (`centrifuge-protobuf`, channel `:proto`). Stream order privat: `/v1/ws/private`, subprotokol `polyester.v1.proto`, tunggu event `PrivateOrdersReady` sebelum mengandalkan stream. Pesan masuk maks 4 MiB. Mode JSON tidak berlaku di realtime. Konsekuensi: v1 pakai **polling REST**, realtime masuk fase opsional.
- Beberapa RPC bisa mengembalikan 404 di devnet/host tertentu (contoh: Preview order). Tangani sebagai "tidak tersedia", jangan jadikan prasyarat.

**Order**
- Operasi: create, cancel, modify, batch (maks **20 item**), cancel-all, list_open, list_history, get.
- `client_order_id`: 1-36 karakter ASCII (huruf, angka, `.` `_` `:` `/` `-`). ID yang sama ditolak (`CONFLICT_DUPLICATE_CLIENT_ORDER_ID`) walau payload sama.
- Setelah kegagalan ambigu (timeout/5xx), **cek dulu** dengan `get`/`list_open` sebelum membuat order lagi dengan ID itu.
- Respons cancel hanya tanda diterima, jadi rekonsiliasi dengan `list_open`.
- Ukuran order: isi tepat salah satu antara `qty` (base) atau `max_quote_debit`.
- Field order: `symbol`, `side` (buy/sell), `order_type` (limit/market), `tif` (mis. `gtc`), `qty`, `price`, `post_only`, `client_order_id`.
- Market order = IOC dengan batas slippage (lihat tautan Price Protection).
- Jangan pakai float. Harga dan kuantitas sebagai string desimal atau integer terskala.
- Harga: `ticks` = satuan protokol **1e9** tetap (9 desimal, 1 tick = 1e-9 quote; bukan tick size pasar). **Berubah dari 1e6 di SDK 0.2.0**, versi lama meleset 1000×. Nilai ticks harus muat di int64 (Q9). Server memvalidasi tick size, step, min qty, dan min notional.
- `tif`: `gtc`, `ioc`, `fok`, `gtd` (`gtd` wajib `expires_at` RFC3339 UTC, 1 detik s.d. 30 hari).
- Market order: slippage pakai salah satu `max_slippage_bps` atau `max_slippage_ticks` (hanya di market order; `bps` sell maks 9999, buy maks 10000).
- `batch_replace` mengembalikan tanda terima admisi, lacak dengan `get_batch_replace_status`. `batch_create` selalu mengirim `request_id` (batas idempotensi batch).
- `cancel_all` menerima `symbol`, `symbols`, atau `symbol_ids` (saling eksklusif, maks 100 ID).
- Kuantitas: integer terskala dengan `scale` dari katalog per simbol (`base_quantity_scale`, `quote_quantity_scale`). Jangan menebak skala 8.
- Request Connect memakai **`symbol_id` numerik**. Simbol tampilan (`BTC-USDT`) harus di-resolve lewat katalog (hydration) sebelum order. Simbol tak dikenal -> gagal tertutup.
- Hydration: **hanya `get_spot_config`** yang mengembalikan `symbol` sekaligus `symbol_id`. Spot config juga memuat skala kuantitas, batas order per pair (termasuk cap notional market order, ditolak dengan `MAX_NOTIONAL`) dan batas order terbuka (`max_open_orders_total`).
- Kuantitas pada order privat bisa datang dengan `scale=None`: selalu resolve skala dari katalog.

**Saldo**
- Ada bucket **funding** dan **trading** per aset. Order spot memakai saldo **trading**.
- Pemindahan funding -> trading dilakukan di UI Polyester (**Funding -> Unified Trading**) atau on-chain (`TradingGateway.deposit`). Bot **tidak** melakukannya. Paper Assets harus sudah ada di saldo trading sebelum bot jalan.
- SDK kini juga mendukung trading -> funding via API key (`trading_withdraws`) dan transfer internal (`internal_transfers`). **Di luar lingkup bot**: API key bot tidak boleh diberi izin withdraw/transfer.
- Paper Assets testnet diklaim **harian** di UI setelah menautkan X atau Discord (menurut panduan pihak ketiga). Bot tidak bisa mengisi saldo sendiri; catat di runbook.
- Fee di `UserTrade` berupa 18-desimal tetap (`fee_amount_e18`); `fee_is_rebate` menandai kredit.

**Rate limit & error**
- HTTP 429 / Connect `resource_exhausted`. Detail ada di `polyester.ratelimit.v1.RateLimitDetail` (`policy_class`, `scope`, `operation_id`, `retry_after_ms`). `retry_after` prefer `detail.retry_after_ms`, lalu header `Retry-After` / `Retry-After-Ms` / `Grpc-Retry-Pushback-Ms`.
- Kelas rate limit trading terpisah: `TRADING_RATE_LIMIT_CLASS_PLACE` dan `TRADING_RATE_LIMIT_CLASS_CANCEL`. Token bucket klien dipisah per kelas.
- Penolakan izin (permission denied) bersifat **non-transient**: jangan retry, perbaiki API-key policy.
- Kode error yang sudah diketahui (SDK 0.3.0):
  | Kode / kondisi | Sifat | Tindakan |
  |---|---|---|
  | `TIMESTAMP_SKEW` | retryable | tanda tangani ulang dengan timestamp baru; jika berulang, cek jam |
  | `AUTH_TEMPORARILY_UNAVAILABLE` | retryable | backoff |
  | `SIGNATURE_INVALID`, `KEY_DISABLED`, `IP_NOT_ALLOWED` | permanen | hentikan bot, notifikasi |
  | `*_OUTCOME_UNKNOWN` | **tidak** di-retry | baca balik order (`get`/`list_open`) sebelum kirim ulang |
  | Connect `deadline_exceeded` (timeout) | retryable, tapi mutasi mungkin sudah terjadi | perlakukan sebagai hasil ambigu |
  | HTTP 404 / 501 / `unimplemented` | permanen | fitur tidak tersedia di host ini |
  | `CONFLICT_DUPLICATE_CLIENT_ORDER_ID` | permanen | ambil `existing_order_id` dari error, adopsi order itu |
  | `ERROR_CODE_CANCEL_REQUEST_EXPIRED` | permanen untuk request itu | rekonsiliasi, lalu kirim cancel baru jika perlu |
  | `MAX_NOTIONAL`, `BAD_QTY`, validasi lain | permanen | catat, sesuaikan parameter |
  | kode numerik tak dikenal | permanen (konservatif) | catat sebagai `UNKNOWN_ERROR_CODE(n)` |
- Kuota trading publik dan per-akun tersedia lewat `ratelimit.v1.RateLimitService` (di SDK: `rate_limits.get_rate_limit_config()`, `get_trading_rate_limits()`).

**Market data**
- Publik tanpa kredensial: spot config, trades, candles, order book snapshot, market overview.
- Candle: `get_candles()` mengembalikan **terbaru dulu** (newest-first). Urutkan manual berdasarkan `ts_sec` sebelum menghitung indikator. Kolom `ts_ns` = epoch nanodetik.
- Volume candle dan market overview memakai `market_data_volume_scale`; OHLC reference candle memakai `reference_price_scale`.
- Pagination trades: sejak 0.2.0 `get_trades()` tidak lagi menerima `from_match_id`, dan `next_match_id` diganti `next_page_token`. **[VERIFIKASI]** apakah replay `trades.list(after_match_id=...)` (dengan `symbol`/`symbol_id` wajib) masih berlaku.
- Fee efektif dan status VIP tersedia lewat API (lihat contoh `21_vip_fees_rate_limits.py`).

**API-key policy (di aplikasi Polyester, menu API)**
- Aktifkan **Spot trading**, pilih **market** yang diizinkan, set **ukuran order maksimum**.
- Untuk key subaccount, otorisasi = irisan **API-key policy** dan **subaccount policy**, jadi dua-duanya harus benar.
- `default_account_id` = **Account ID** di halaman Profile (bukan ID numerik internal).

---

## 4. Hal yang Belum Terverifikasi (Kerjakan Dulu, Fase 0)

Claude Code: sebelum fase 1, jawab daftar ini dengan membaca source SDK (`src/polyester/` Python, `src/gen/` Rust, atau repo Go) dan `polyester-examples-python`.

Status per 31 Okt 2026 (riset README/CHANGELOG SDK 0.3.0): butir 1 dan 3 **terjawab** (lihat bagian 3), butir 5 **sebagian besar terjawab** (tabel error bagian 3). Sisanya tetap wajib dicek dari source.

1. ~~**Base URL testnet**~~ Terjawab: `https://api.testnet.polyester.com`, chain `888169`. Tinggal konfirmasi path WebSocket publik dan privat (`/v1/ws/private`) di host testnet.
2. **Nama RPC & bentuk JSON** untuk: CreateOrder, CancelOrder, CancelAll, ListOpenOrders, GetOrder, ListBalances, GetSpotConfig/katalog, GetOrderBook, GetCandles, GetTrades, ListUserTrades, GetSpotFeeRates, GetTradingRateLimits. Catat nama service lengkap, nama field JSON (camelCase dari protobuf), dan representasi tipe Price/Quantity di JSON.
3. ~~**Cara hydration katalog**~~ Terjawab: `get_spot_config`. Tinggal catat nama field JSON untuk tick size, step size, min qty, min notional, dan batas order.
4. **Header Connect**: konfirmasi `Content-Type: application/json` dan `Connect-Protocol-Version: 1` cukup untuk unary JSON, dan apakah ada header lain yang disyaratkan.
5. **Pemetaan error**: sebagian besar terjawab. Tinggal: bentuk JSON detail error Connect (lokasi kode string dan `RateLimitDetail`), serta daftar lengkap kode `*_OUTCOME_UNKNOWN`.
6. **Endpoint order book & saldo** untuk polling (frekuensi aman dan kuota).
7. Apakah **.proto** publik tersedia (untuk Fase 6 realtime). Alternatif: turunkan skema dari descriptor di `src/polyester/gen/` SDK (cek lisensi). Jika tidak memungkinkan, realtime ditunda.
8. Apakah server punya **self-trade prevention** (STP) dan modenya. Jika tidak, Risk Manager wajib mencegah sendiri.
9. Bentuk respons `batch_replace` / `get_batch_replace_status` dan apakah cocok untuk requote market maker.
10. **Aturan kompetisi/reward testnet** yang berlaku (bacaan manual dari Discord/X, bukan tugas kode).

Keluaran Fase 0: file `docs/api-notes.md` berisi jawaban di atas dengan contoh request/respons JSON nyata (tanpa key asli).

---

## 5. Tech Stack

| Lapisan | Pilihan | Catatan |
|---|---|---|
| Bahasa | Kotlin (JVM 17) | |
| UI | Jetpack Compose + Material 3 | satu Activity, navigasi Compose |
| Arsitektur | MVVM + Clean-ish (data / domain / ui) | |
| DI | Hilt | |
| Async | Kotlin Coroutines + Flow | satu scope di service |
| HTTP | OkHttp 4/5 | satu instance singleton, keep-alive, timeout jelas |
| JSON | kotlinx.serialization | serialisasi ke `ByteArray` sekali |
| Ed25519 | BouncyCastle lightweight (`bcprov-jdk18on`) atau Tink | pilih satu, bungkus di antarmuka `Signer` |
| Hash | `MessageDigest` SHA-256 bawaan JDK | |
| Penyimpanan state | Room (order, log, run) + DataStore (konfigurasi) | |
| Penyimpanan rahasia | Android Keystore (AES-GCM) membungkus private key | jangan simpan plaintext |
| Background | Foreground Service tipe `specialUse` | **bukan** `dataSync` (dibatasi 6 jam/24 jam di Android 15+). Syarat `specialUse` terverifikasi (developer.android.com/develop/background-work/services/fgs/service-types) |
| Codec kawat | antarmuka `Codec` (JSON v1, protobuf/Wire opsional) | supaya bisa pindah ke protobuf tanpa mengubah lapisan atas |
| Notifikasi | NotificationCompat, channel `IMPORTANCE_LOW` | |
| Test | JUnit5, MockK, Turbine, MockWebServer | |
| Build | Gradle (Kotlin DSL) + version catalog | minSdk 26, targetSdk terbaru |
| CI | GitHub Actions: build, unit test, lint | |

Dependensi dijaga sedikit. Hindari library trading/TA berat. Indikator sederhana (EMA, ATR) ditulis sendiri.

---

## 6. Struktur Proyek

```
polybot/
├─ plan.md
├─ docs/
│  ├─ api-notes.md                # hasil Fase 0
│  └─ runbook.md                  # panduan pengaturan baterai per merek HP
├─ app/
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ kotlin/app/polybot/
│        ├─ PolyBotApp.kt         # Application + Hilt
│        ├─ MainActivity.kt
│        ├─ di/                   # modul Hilt
│        ├─ core/
│        │  ├─ time/              # Clock, NtpSkewChecker
│        │  ├─ result/            # sealed Result/ApiError
│        │  └─ log/               # RingLogger (terputar, ukuran terbatas)
│        ├─ data/
│        │  ├─ crypto/
│        │  │  ├─ Signer.kt            # antarmuka
│        │  │  ├─ Ed25519Signer.kt
│        │  │  ├─ RequestSigner.kt     # canonical string + header
│        │  │  ├─ CanonicalQuery.kt
│        │  │  └─ SecureKeyStore.kt    # Keystore + EncryptedPrefs
│        │  ├─ api/
│        │  │  ├─ ConnectClient.kt     # POST JSON, tanda tangan, retry
│        │  │  ├─ RateLimiter.kt       # token bucket klien
│        │  │  ├─ ErrorMapper.kt
│        │  │  ├─ dto/                 # DTO request/respons
│        │  │  └─ services/
│        │  │     ├─ OrdersApi.kt
│        │  │     ├─ MarketDataApi.kt
│        │  │     ├─ LedgerApi.kt
│        │  │     └─ CatalogApi.kt
│        │  ├─ catalog/
│        │  │  └─ CatalogRepository.kt # symbol -> id, scale, tick/step/minQty/minNotional
│        │  ├─ db/                     # Room: OrderEntity, FillEntity, BalanceSnapshotEntity, EquitySnapshotEntity, RunEntity, LogEntity
│        │  └─ prefs/                  # DataStore: BotConfig, EnvConfig
│        ├─ domain/
│        │  ├─ model/                  # Price, Qty (BigDecimal + scaled), Order, Fill, Balance, Market
│        │  ├─ numeric/                # konversi desimal <-> scaled, pembulatan tick/step
│        │  ├─ strategy/
│        │  │  ├─ Strategy.kt         # antarmuka
│        │  │  ├─ MarketMaker.kt
│        │  │  ├─ Grid.kt
│        │  │  └─ Dca.kt
│        │  ├─ risk/
│        │  │  ├─ RiskManager.kt
│        │  │  └─ RiskRules.kt
│        │  └─ engine/
│        │     ├─ BotEngine.kt        # loop utama, state machine
│        │     ├─ OrderManager.kt     # create/cancel/reconcile idempoten
│        │     ├─ Reconciler.kt
│        │     └─ ClientOrderIds.kt
│        ├─ service/
│        │  ├─ BotForegroundService.kt
│        │  ├─ StatusNotifier.kt
│        │  └─ BootReceiver.kt        # opsional
│        └─ ui/
│           ├─ theme/
│           ├─ components/      # top bar status, kartu KPI, tabel, grafik
│           ├─ dashboard/       # ringkasan KPI, grafik, status & kesehatan
│           ├─ transactions/    # laporan fill, filter, ekspor CSV
│           ├─ orders/          # order terbuka & riwayat
│           ├─ balances/        # saldo per aset & riwayat
│           ├─ logs/
│           └─ settings/
│              ├─ control/      # Start/Pause/Stop/Kill Switch, dry run
│              ├─ account/      # API key, Account ID, tes koneksi
│              ├─ strategy/     # parameter strategi
│              ├─ risk/         # parameter risiko
│              ├─ polling/      # interval & notifikasi
│              ├─ data/         # retensi & ekspor
│              └─ guide/        # panduan pengaturan baterai
└─ app/src/test/ ... app/src/androidTest/ ...
```

Aturan lapisan: `domain` tidak boleh bergantung pada `data` atau Android. `BotEngine` hanya bicara lewat antarmuka repository, supaya bisa diuji dengan fake.

---

## 7. Modul Penandatanganan Request

`RequestSigner.sign(method, url, bodyBytes): Map<String,String>`

Langkah:
1. `ts = timestampAllocator.next()` (ms; jika `<= last`, pakai `last + 1`; tolak jika melewati `now + 5000` dan tunggu).
2. `canonical = listOf(ts, method.uppercase(), path.ifEmpty{"/"}, canonicalQuery(url), sha256Hex(bodyBytes)).joinToString("\n")`.
3. `sig = hex(ed25519.sign(canonical.toByteArray(UTF_8)))`.
4. Kembalikan header `X-API-KEY-ID`, `X-API-TIMESTAMP`, `X-API-SIGNATURE`.

`ConnectClient.call(procedure, request)`:
1. Serialisasi request ke `ByteArray` **sekali**.
2. Tanda tangani byte itu.
3. Kirim `POST {baseUrl}/{procedure}` dengan `RequestBody` dari byte yang sama, header `Content-Type: application/json`, `Connect-Protocol-Version: 1` **[VERIFIKASI]**.
4. Petakan HTTP/Connect error ke `ApiError` (bagian 9).

Persyaratan keamanan:
- Private key tidak pernah di-log, tidak masuk crash report, tidak dimasukkan ke `toString()`.
- Pesan error tidak boleh menyertakan nilai key.

Unit test wajib: test vector bagian 3, tiga timestamp berurutan unik, dan 10.000 tanda tangan paralel tidak menghasilkan timestamp duplikat.

---

## 8. Angka, Skala, dan Pembulatan

- Representasi internal: `BigDecimal` untuk logika, integer terskala untuk kawat. **Tanpa Double/Float.**
- `Price`: `ticks = (decimal * 1_000_000_000)` integer (Q9, harus muat di `Long`; tolak jika overflow). Bulatkan ke kelipatan tick size pasar (dari katalog) dengan arah konservatif: bid dibulatkan ke bawah, ask ke atas.
- `Qty`: integer terskala memakai `base_quantity_scale` simbol. Bulatkan ke bawah ke kelipatan step size, tolak jika `< minQty` atau notional `< minNotional`.
- Semua konversi lewat satu kelas `Numeric` dengan unit test (round-trip, batas, nol, nilai sangat kecil).
- Skala selalu diambil dari katalog yang sudah di-hydrate. Jika katalog belum siap, bot tidak boleh mengirim order.

---

## 9. Penanganan Error, Retry, dan Idempotensi

Kategori `ApiError`:
- **RateLimited(retryAfterMs)**: backoff sesuai `retry_after`, kurangi laju kirim.
- **Transient** (5xx, jaringan putus, `AUTH_TEMPORARILY_UNAVAILABLE`): retry dengan backoff eksponensial + jitter, maksimal N kali. Untuk request **read-only** saja; mutasi lihat `OutcomeUnknown`.
- **OutcomeUnknown** (timeout/`deadline_exceeded` pada mutasi, kode `*_OUTCOME_UNKNOWN`): **jangan** kirim ulang. Baca balik order dulu, baru putuskan.
- **Auth/Permission** (`SIGNATURE_INVALID`, `KEY_DISABLED`, `IP_NOT_ALLOWED`, izin ditolak): **hentikan bot**, tampilkan notifikasi, jangan retry.
- **Validation/Business** (`BAD_QTY`, `MAX_NOTIONAL`, harga di luar batas, saldo kurang): jangan retry otomatis, catat, sesuaikan parameter.
- **NotAvailable** (404/501/`unimplemented`): tandai fitur tidak tersedia, jangan retry.
- **ClockSkew** (`TIMESTAMP_SKEW`): tanda tangani ulang dengan timestamp baru; jika berulang, jalankan pengecekan jam dan PAUSED.

Aturan order idempoten:
- `client_order_id` = `pb{runShort}-{seq}` (maks 36 karakter, hanya karakter yang diizinkan). `seq` disimpan di Room dan monoton naik, bertahan setelah restart.
- Alur create: simpan order lokal berstatus `PENDING` -> kirim -> jika sukses `OPEN`; jika hasil ambigu, **`get` by client_order_id** (atau `list_open`) sebelum apa pun. Hanya kirim ulang dengan ID itu jika terbukti tidak masuk. Jika dapat `CONFLICT_DUPLICATE_CLIENT_ORDER_ID`, pakai `existing_order_id` untuk mengadopsi order yang sudah ada.
- Cancel: setelah cancel diterima, rekonsiliasi dengan `list_open` sebelum membebaskan state lokal.
- Saat start dan setiap N siklus, **Reconciler** membandingkan order lokal dengan `list_open` server dan menyelesaikan selisih (order yatim -> batalkan atau adopsi sesuai kebijakan).

---

## 10. Logika Bot

### 10.1 Loop utama (tiap siklus, default 5 detik saat ada order aktif, 15-30 detik saat idle)

```
tick():
  guard: service aktif, jaringan ada, jam OK, katalog siap, bukan kill switch
  snapshot = fetch(orderBook(top-N), lastPrice, openOrders, balances(trading))
  risk.preTrade(snapshot) -> jika gagal: cancelAllIfNeeded(); mode PAUSED; notify; return
  intents = strategy.decide(snapshot, state, config)
  for intent in intents.filter(risk.approve):
      orderManager.submit(intent)       # idempoten, post_only untuk quote
  orderManager.cancelStale(snapshot, config)
  persist(state); updateNotification()
```

State bot: `IDLE -> STARTING -> RUNNING -> PAUSED(reason) -> STOPPING -> STOPPED`, plus `ERROR_FATAL`. Transisi dicatat ke log.

### 10.2 Strategi A — Market Maker Ringan (default)

Parameter: `halfSpreadBps`, `orderQuote` (nilai quote per order), `levels` (1-3), `levelStepBps`, `requoteThresholdBps`, `maxOrderAgeSec`, `maxInventoryPct`, `skewFactor`, `cooldownSec`.

```
mid = (bestBid + bestAsk) / 2        # fallback ke lastPrice jika book tipis
inv = (baseValue / (baseValue + quoteValue)) - 0.5      # deviasi inventori [-0.5, 0.5]
skewBps = -skewFactor * inv * 10_000                    # inventori berlebih -> geser quote turun
for level in 1..levels:
    bid = mid * (1 - (halfSpreadBps + (level-1)*levelStepBps)/10_000) * (1 + skewBps/10_000)
    ask = mid * (1 + (halfSpreadBps + (level-1)*levelStepBps)/10_000) * (1 + skewBps/10_000)
    qty = floorToStep(orderQuote / price)
    quote kedua sisi dengan post_only=true, tif=gtc
requote jika: |mid - lastQuotedMid| / lastQuotedMid > requoteThresholdBps  ATAU umur order > maxOrderAgeSec
tahan sisi buy jika inventoryPct > maxInventoryPct; tahan sisi sell jika inventoryPct < (1 - maxInventoryPct)
```

Jaga agar quote tidak menyilang book (bid >= bestAsk atau ask <= bestBid ditolak post_only), dan tidak saling bertemu dengan order milik sendiri (hindari self-trade).

Efisiensi request (mengurangi pola pasang-batal yang bisa dianggap spam):
- Pasang quote dengan `tif=gtd` + `expires_at = now + maxOrderAgeSec` supaya order basi kedaluwarsa sendiri, tanpa request cancel. Fallback ke `gtc` + cancel manual jika `gtd` tidak tersedia di host.
- Requote memakai `batch_replace` (maks 20 item) bila terverifikasi cocok (bagian 4 butir 9), bukan cancel lalu create terpisah.
- `halfSpreadBps` wajib > fee maker efektif (ambil dari API fee/VIP saat start). Jika tidak, strategi menolak start.

### 10.3 Strategi B — Grid

Parameter: `lower`, `upper`, `gridCount`, `orderQuote`. Pasang limit buy di level di bawah harga, limit sell di level di atas. Saat satu level terisi, pasang order lawan di level berdampingan. Berhenti dan batalkan semua jika harga keluar rentang melebihi `rangeBreakBps`.

### 10.4 Strategi C — DCA Terjadwal

Parameter: `intervalSec`, `orderQuote`, `maxTotalQuote`. Beli kecil berkala dengan limit `post_only` sedikit di bawah best bid. Berhenti saat `maxTotalQuote` tercapai.

### 10.5 Filter sinyal (jika menambah indikator)
- Tidak bereaksi pada satu candle. Pakai EMA cepat/lambat atau ATR sebagai filter volatilitas.
- Urutkan candle oldest-first sebelum indikator (API mengembalikan newest-first).
- Cooldown antar sinyal, dan jangan buka posisi berlawanan di simbol yang sama dalam jendela singkat.

### 10.6 Risk Manager (wajib lulus sebelum order apa pun)

| Aturan | Default |
|---|---|
| Ukuran maksimum per order (quote) | kecil, mis. 1-2% saldo |
| Eksposur maksimum per aset | 10-20% saldo |
| Order terbuka maksimum | min(6, batas server dari spot config) |
| Notional market order | di bawah cap `MAX_NOTIONAL` pair |
| Order baru per menit maksimum | di bawah kuota rate limit akun |
| Penyimpangan harga dari last/mid | tolak jika > 2% |
| Saldo trading cukup + cadangan | wajib |
| Rugi harian maksimum | stop + cancel-all |
| Error beruntun | 5 -> PAUSED |
| Spread/book anomali (kosong, crossed) | PAUSED |
| Selisih jam | > 3 detik -> PAUSED |
| Batas pasangan yang boleh | hanya simbol di allowlist |

Kill switch (tombol UI + notifikasi aksi): `cancel_all` dengan filter **`symbol_ids`** = allowlist bot (maks 100; jangan pernah tanpa filter), lalu rekonsiliasi `list_open`, lalu state `STOPPED`. Tangani `ERROR_CODE_CANCEL_REQUEST_EXPIRED` dengan mengirim cancel baru.

Perilaku saat stop atau fatal: bot **membatalkan order terbuka miliknya** (berdasarkan prefix `client_order_id`) secara default, bisa diubah di konfigurasi.

---

## 11. Foreground Service & Manajemen Resource

- `BotForegroundService` menjalankan satu `CoroutineScope(SupervisorJob + Dispatchers.Default)`; engine hidup di sini, bukan di Activity.
- Notifikasi permanen channel `IMPORTANCE_LOW`, tanpa suara. Isi: status, jumlah order terbuka, PnL sesi, waktu update terakhir. Perbarui hanya saat status berubah atau tiap 30-60 detik. Aksi: **Stop**, **Kill Switch**.
- Manifest: izin `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`, `INTERNET`, `ACCESS_NETWORK_STATE`.
- Tipe foreground service: Android 14+ mewajibkan tipe. **Jangan pakai `dataSync`**: di Android 15+ (target SDK 35+) `dataSync` hanya boleh total 6 jam per 24 jam, lalu `onTimeout()` dipanggil (terverifikasi di dokumentasi Android). Pakai `specialUse` dengan `android:foregroundServiceType="specialUse"`, izin `FOREGROUND_SERVICE_SPECIAL_USE`, dan `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` berisi alasan. Terverifikasi: izin `FOREGROUND_SERVICE_SPECIAL_USE`, atribut `foregroundServiceType="specialUse"`, dan properti `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; `specialUse` tidak termasuk tipe yang dilarang dimulai dari `BOOT_COMPLETED`. Restart `START_STICKY` dari latar belakang tidak didokumentasikan sebagai pengecualian, jadi service menangani `ForegroundServiceStartNotAllowedException` dan mencatatnya ke Log.
- `PARTIAL_WAKE_LOCK` hanya selama siklus aktif atau seperlunya; lepas saat idle panjang. Pantau penggunaan baterai.
- Satu `OkHttpClient` singleton; koneksi dipakai ulang; timeout connect/read/write eksplisit.
- **Interval adaptif**: lambat saat idle, cepat saat ada order aktif; hormati rate limit; backoff eksponensial saat error.
- Pantau konektivitas (`ConnectivityManager.NetworkCallback`): jeda saat offline, lanjut + rekonsiliasi saat kembali.
- Pantau jam: bandingkan `Date` header respons dengan jam lokal; jika selisih > ambang, PAUSED dan minta pengguna aktifkan waktu otomatis.
- Log terputar (maks beberapa MB), pangkas Room secara berkala (mis. simpan 7 hari).
- Pulihkan state dari Room saat proses dimatikan lalu hidup lagi (`START_STICKY` + `BootReceiver` opsional). Setelah restart selalu jalankan Reconciler sebelum mengirim order baru.
- Panduan merek HP (Xiaomi/Oppo/Vivo/Huawei/Samsung: autostart, "tidak dibatasi", kunci aplikasi di recents) ditulis di `docs/runbook.md` dan layar `ui/guide`. Runbook juga memuat klaim Paper Assets harian di UI Polyester dan pemindahan Funding -> Unified Trading.
- Saran perangkat: HP lama khusus, tercolok charger, batas pengisian ~80% jika tersedia.

---

## 12. Keamanan & Penyimpanan Kunci

- Private key diimpor sekali (tempel/QR), dienkripsi AES-GCM dengan kunci Android Keystore, disimpan di penyimpanan privat aplikasi.
- Opsional: kunci dibuka dengan biometrik saat start bot.
- `android:allowBackup="false"`, `FLAG_SECURE` pada layar yang menampilkan kunci, tidak ada screenshot di layar setup.
- Gunakan **API key khusus bot** dengan policy minimal: hanya Spot trading, hanya market yang dipakai, ukuran order maksimum kecil, tanpa izin withdraw.
- Jangan menyematkan key apa pun di APK atau repo. `.gitignore` memuat `*.keystore`, `local.properties`, `.env`.
- Pinning sertifikat opsional; minimal pakai TLS default dan tolak cleartext (`usesCleartextTraffic=false`).

---

## 13. UI (Compose)

Gaya **admin dashboard**: layar utama untuk memantau (laporan dan status), sedangkan semua tombol kontrol dan konfigurasi dikumpulkan di menu **Pengaturan**. Layar pantau bersifat read-only supaya tidak ada aksi trading yang tertekan tanpa sengaja.

**Navigasi**
- Bottom navigation (HP) / navigation rail (layar lebar): **Dashboard**, **Transaksi**, **Order**, **Saldo**, **Log**, **Pengaturan** (label bar bawah "Setelan" karena "Pengaturan" terpotong di layar 6 tab).
- Top app bar di semua layar pantau: chip status bot (RUNNING / PAUSED(alasan) / STOPPED / ERROR), indikator jaringan, indikator jam, dan waktu update terakhir. Chip status hanya menampilkan; ketuk -> buka Pengaturan > Kontrol Bot.
- Kill switch tetap tersedia sebagai aksi di **notifikasi** foreground service (bagian 11), jadi bisa dipakai tanpa membuka aplikasi.

**Layar pantau (read-only)**
1. **Dashboard (ringkasan):**
   - Kartu KPI: total ekuitas (dalam quote), PnL sesi, PnL hari ini, PnL 7 hari, jumlah order terbuka, jumlah fill hari ini, total fee (rebate dipisah).
   - Grafik ekuitas/PnL (hari ini, 7 hari, 30 hari) dari snapshot yang disimpan di Room.
   - Grafik harga simbol aktif dengan penanda fill buy/sell dan level quote yang sedang terpasang.
   - Panel status: state engine, strategi aktif, mode (live / dry run), uptime, siklus terakhir, error beruntun, pemakaian kuota rate limit (place/cancel).
   - Panel kesehatan: selisih jam, latensi rata-rata API, status jaringan, status katalog, baterai/pengisian.
   - 5 transaksi terakhir dan 5 kejadian log penting terakhir (pintasan ke layar lengkap).
2. **Transaksi (laporan fill):**
   - Tabel fill: waktu, simbol, sisi, harga, qty, notional, fee/rebate, maker/taker, `client_order_id`.
   - Filter: rentang tanggal, simbol, sisi, strategi/run. Ringkasan di atas tabel: volume, jumlah trade, fee bersih, realized PnL.
   - Laporan harian/mingguan (agregat per hari). Ekspor CSV lewat share sheet Android.
3. **Order:**
   - Tab **Terbuka** (dari rekonsiliasi terakhir) dan **Riwayat** (filled, cancelled, expired, rejected beserta alasan).
   - Detail order: semua field, umur order, siklus pembuatan, alasan keputusan strategi.
4. **Saldo:**
   - Per aset: trading (available / locked di order) dan funding (jika bisa dibaca). Nilai dalam quote dan persentase inventori dibanding `maxInventoryPct`.
   - Riwayat saldo (grafik) dari snapshot berkala.
5. **Log:** daftar keputusan dan kejadian (alasan, harga, ukuran, hasil), filter level/kategori, pencarian, ekspor teks.

**Pengaturan (semua kontrol di sini)**
1. **Kontrol Bot:** Start, Pause/Resume, Stop, **Kill Switch** (dialog konfirmasi + ringkasan apa yang akan dibatalkan), toggle dry run, tombol "Rekonsiliasi sekarang". Aksi berbahaya selalu minta konfirmasi; opsional biometrik.
2. **Akun & API Key:** environment (testnet), API key id, private key (impor tempel/QR), Account ID, tombol "Tes koneksi" (endpoint ringan terautentikasi, mis. `Me` atau `ListBalances` **[VERIFIKASI]**), ganti/hapus key. Error jelas (izin, jam, key salah). Layar ini memakai `FLAG_SECURE`. Private key diperiksa formatnya langsung saat diketik (64 hex; pesan khusus untuk `0x`, PEM, 128 karakter) dan **public key turunannya ditampilkan** agar bisa dicocokkan dengan yang tercatat di Polyester.
3. **Strategi:** pilih strategi (Market Maker / Grid / DCA), simbol (allowlist), parameter per strategi dengan validasi dan nilai default.
4. **Risiko:** semua parameter bagian 10.6, batas rugi harian, error beruntun, ambang selisih jam.
5. **Polling & Notifikasi:** interval aktif/idle, frekuensi update notifikasi, notifikasi untuk fill/pause/error.
6. **Data:** retensi log dan riwayat (default 7 hari untuk log, 90 hari untuk fill dan snapshot saldo), ekspor, hapus data lokal.
7. **Panduan baterai:** langkah per merek, tombol pintasan ke pengaturan sistem.
8. **Tentang:** versi aplikasi, versi API/SDK acuan, link dokumentasi.

Perubahan konfigurasi saat bot RUNNING: parameter strategi/risiko diterapkan di siklus berikutnya setelah divalidasi; perubahan simbol atau API key mewajibkan bot di-Stop dulu.

**"Realtime" di v1**
- Data diperbarui dari polling (bagian 10.1), bukan WebSocket. UI mengamati `Flow` dari Room/state engine, jadi layar langsung berubah setiap siklus tanpa refresh manual. Tampilkan "diperbarui N detik lalu" agar jelas.
- Fase 6 (opsional) mengganti sumber data dengan stream realtime tanpa mengubah UI.

**Data pendukung (Room)**
- `FillEntity` (dari ListUserTrades, dedup per trade id), `OrderEntity`, `BalanceSnapshotEntity` (tiap 1-5 menit), `EquitySnapshotEntity`, `RunEntity`, `LogEntity`.
- PnL dihitung di `domain` (realized dari fill, unrealized dari mark ke mid) dengan `BigDecimal`, tanpa Double. Untuk grafik, konversi ke float hanya di lapisan UI.

**Grafik:** pakai library ringan Compose-native (mis. Vico) **[VERIFIKASI]** versi terbaru, atau `Canvas` sendiri. Jangan pakai WebView.

---

## 14. Fase & Checklist

**Fase 0 — Riset API (hasil: `docs/api-notes.md`)**
- [x] Jawab butir bagian 4 yang bisa dijawab dari source SDK (lihat `docs/api-notes.md`). Sisanya (bentuk JSON `int64`/`U128`, header Connect, `RateLimitDetail`) dikonfirmasi di Fase 2.
- [~] Contoh respons **nyata** untuk endpoint publik sudah tercatat di `docs/api-notes.md` bagian 5b. Endpoint terautentikasi menunggu API key (Fase 2, butir terakhir).

**Fase 1 — Fondasi**
- [x] Proyek Gradle, Hilt, Compose, CI (`.github/workflows/android.yml`).
- [x] `CanonicalQuery`, `RequestSigner`, `Ed25519Signer`, `TimestampAllocator` + test vector bagian 3 dan RFC 8032 (16 test lulus, termasuk 10.000 tanda tangan paralel).
- [x] `SecureKeyStore` (`AndroidKeystoreKeyStore`, AES-GCM) ditulis dan lolos kompilasi + lint.
- [x] Uji `AndroidKeystoreKeyStore` di emulator (`androidTest`): round-trip, tidak ada plaintext di prefs, blob rusak -> null.

**Fase 2 — Klien API read-only**
- [x] `ConnectClient` + `ErrorMapper` + `RateLimiter` (+ `ClockMonitor` dari header `Date`), diuji dengan MockWebServer.
- [x] Katalog (`GetSpotConfig`), order book, candle, trade (publik, **terverifikasi ke testnet nyata**), saldo dan order terbuka (terautentikasi, **belum teruji** tanpa API key).
- [x] `Numeric` (ticks 1e9, skala qty, U128, pembulatan, validasi) + test. Sebagian dimajukan dari Fase 3.
- [x] Kerangka navigasi (bottom nav, menu Setelan) dan Setelan > Akun & API Key + tombol "Tes koneksi". Berjalan di emulator; layar Akun memakai `FLAG_SECURE`.
- [x] Layar Dashboard (koneksi, pasar, jam), Saldo, Order terbuka.
- [ ] **"Tes koneksi" berhasil terhadap testnet dengan API key sungguhan**: butuh key milik pengguna. Yang diverifikasi saat itu: bentuk JSON `Me`/`GetBalances`/`GetOpenOrders`, skala saldo 18 desimal, `subaccountId`.
- [x] Uji `AndroidKeystoreKeyStore` di emulator (`androidTest`, 5 test lulus).

**Fase 3 — Order & numerik**
- [x] `Numeric` (skala, tick, step, pembulatan, U128, validasi lokal) + test. Selesai di Fase 2.
- [x] `OrderManager` idempoten: simpan PENDING sebelum kirim, baca balik dengan `client_order_id` saat ambigu, kirim ulang dengan ID yang sama hanya setelah terbukti tidak ada, adopsi bila ID ganda, cancel dikonfirmasi lewat `get`. Teruji dengan `FakeExchange` (16 skenario).
- [x] `Reconciler`: sinkronisasi lokal vs server, order yatim (adopsi/batalkan, hanya prefix `pb`), order milik orang lain tidak disentuh, gagal-aman bila `listOpen` gagal (10 skenario).
- [x] `ClientOrderIds` (`pb{run}-{seq}`, seq di Room, bertahan setelah restart) dan penyimpanan Room (`orders`, `counters`). Teruji di emulator: 200 `nextSeq` paralel semuanya unik, seq dan run id bertahan setelah database dibuka ulang (4 test `androidTest`).
- [x] Layar Setelan > Uji order manual (dialog konfirmasi sebelum kirim).
- [ ] **Uji manual terhadap testnet dengan API key sungguhan**: satu limit `post_only` kecil, batalkan, rekonsiliasi. Ini yang akan membuktikan bentuk request di `docs/api-notes.md` bagian 5c.

**Fase 4 — Engine, risiko, strategi**

Tahap 4a-4c selesai di sisi kode; yang bergantung pada API key sungguhan belum terbukti.

- [x] `RiskManager` + semua aturan bagian 10.6 (ukuran order, eksposur termasuk beli yang masih terbuka, jumlah order terbuka, order per menit, deviasi harga, saldo + cadangan, rugi harian UTC, error beruntun, buku anomali, selisih jam, allowlist, anti self-trade, post-only tidak menyilang) dengan test.
- [x] `BotEngine`: state machine IDLE/STARTING/RUNNING/PAUSED(auto|manual)/STOPPING/STOPPED/FATAL, rekonsiliasi tiap siklus, jeda otomatis pulih sendiri, Halt dari strategi, stop, kill switch (order bot saja; `cancel_all` per simbol hanya bila diizinkan). **Dry run** memakai komponen dalam memori sehingga jalur kodenya sama dengan live. Dry run adalah default dan Start LIVE butuh konfirmasi.
- [x] Market Maker, Grid, DCA (murni, tanpa jaringan) dengan test.
- [x] Simulasi dengan bursa palsu (harga acak, fill, saldo, penolakan seperti server): invarian dicek tiap siklus (batas order terbuka, nilai order, eksposur, tidak ada self-cross, ID unik, tanpa penolakan server, tanpa order tersisa setelah stop).
- [x] Setelan > Kontrol Bot, Strategi, Risiko; layar Log (filter level); kartu status bot di Dashboard.
- [ ] **Belum dijalankan terhadap testnet dengan API key sungguhan** (dry run maupun live).
- [ ] Dry run belum menyimulasikan fill. Syarat "half spread harus lebih besar dari fee maker" belum diperiksa otomatis (endpoint fee belum dipakai).
- [x] 4c pelaporan: layar **Transaksi** (fill dari `GetUserTrades`, filter rentang/sisi/simbol, ringkasan volume, PnL terealisasi, fee bersih, posisi, ekspor CSV lewat share sheet); **PnL** biaya rata-rata per simbol dihitung dari seluruh riwayat lalu disaring ke rentang; **riwayat order** (`GetOrderHistory`) di tab Order; **snapshot ekuitas** tiap menit saat bot berjalan + grafik di Dashboard (1 jam/24 jam/7 hari); kartu KPI hari ini/7 hari; **log tersimpan** di Room (dipulihkan setelah restart); layar **Data** (jumlah data, retensi, hapus).
- [ ] Bentuk `GetUserTrades`/`GetOrderHistory` belum dikonfirmasi ke server (butuh API key). Rumus PnL teruji dengan data buatan (13 test) dan tampilan diperiksa di emulator dengan data contoh.

Penyimpangan dari rencana awal, sengaja:
- Inventori target market maker = separuh batas eksposur (default plan, inventori netral 50% dengan eksposur maks 15%, saling bertabrakan). `maxInventoryPct` kini bagian dari batas eksposur (default 0.8) dan `skewFactor` menggeser kuotasi maksimum `skewFactor x halfSpread`.
- Perubahan pengaturan berlaku pada Start berikutnya, bukan di siklus berjalan.
- Retensi: log 7 hari, snapshot 90 hari, **fill 365 hari** (bukan 90): PnL memakai biaya rata-rata dari seluruh riwayat, jadi fill tidak dipangkas cepat.
- Tabel laporan ada di database terpisah (`polybot-report.db`) supaya database order tidak perlu migrasi.
- "Hari ini" di laporan mengikuti tengah malam waktu lokal; batas rugi harian risiko memakai hari UTC.

**Fase 5 — Service & notifikasi**
- [x] `BotForegroundService` (tipe `specialUse`): notifikasi status (IMPORTANCE_LOW) dengan aksi **Stop** dan **Kill Switch**, diperbarui saat status berubah atau tiap 30 detik; peringatan terpisah (jeda, berhenti otomatis, galat fatal) di channel sendiri; menutup diri saat sesi berakhir. Terverifikasi di emulator Android 16: notifikasi foreground berflag `FOREGROUND_SERVICE` dengan dua aksi, diganti peringatan kritis saat galat fatal, service berhenti sendiri.
- [x] Wake lock parsial hanya selama bot perlu polling (batas 10 menit, diperpanjang berkala); `NetworkMonitor`: bot dijeda saat offline dan lanjut (dengan rekonsiliasi) saat jaringan kembali.
- [x] Persisten dan pemulihan: penanda sesi + denyut di DataStore, `SessionPolicy` (hanya bila tidak di-Stop pengguna, kurang dari 6 jam, dry run atau live dengan izin eksplisit `autoResumeLive`), `START_STICKY`, `BootReceiver` (BOOT_COMPLETED dan MY_PACKAGE_REPLACED). Engine selalu merekonsiliasi sebelum order baru.
- [x] Izin notifikasi (Android 13+) diminta saat Start; layar **Panduan baterai** (status bebas-optimasi, tombol permintaan, langkah per merek) dan `docs/runbook.md` (prosedur uji 24 jam, uji pemulihan, penghentian darurat, pemecahan masalah).
- [ ] **Uji 24 jam pada perangkat nyata**: butuh HP Anda dan API key; prosedur dan kriteria lolos ada di `docs/runbook.md` bagian 4.
- [ ] Layar "Polling & Notifikasi" tersendiri belum dibuat: interval polling sudah ada di Setelan > Strategi, notifikasi mengikuti channel sistem.
- [ ] Belum terverifikasi di perangkat: pemulihan setelah proses dimatikan sistem dan setelah restart HP (dengan Autostart merek tertentu).

**Rombak UI (gaya aplikasi trading)**
- [x] Tema gelap/terang khusus (emas, hijau naik, merah turun), komponen baru: ikon koin dan pasangan, kartu, kapsul, StatTile, EmptyState.
- [x] Dashboard, Order, Saldo, Transaksi, Log dan Kontrol Bot dirombak; grafik ekuitas berwarna sesuai naik/turun.
- [x] Simbol dipilih dari daftar katalog server (bukan ketik): satu simbol di Strategi, banyak simbol di Risiko.
- [x] Layar **Polling & Notifikasi** (slider interval, status izin, pintas pengaturan channel) dan **Tentang** sudah dibuka.
- [x] Ikon peluncur diganti dengan logo bulan-koin (Azzurahai): ikon adaptif `mipmap-anydpi` (latar solid `#E591DF` + stiker tanpa latar di zona aman), PNG lama/bulat per kepadatan, `android:icon` dan `roundIcon` di manifest, dan logo di layar Tentang (`drawable-nodpi/logo_polybot.png`). Sumber gambar JPEG tanpa transparansi, jadi latarnya dipotong dengan flood fill dari tepi.
- [ ] Belum diuji di HP asli. Ikon tema Android 13 (monochrome) belum dibuat.

**Perbaikan Market Maker (setelah uji live 1 Nov)**
- [x] Skew netral terhadap posisi awal sesi (bukan target tetap 7.5%); half spread efektif tidak di bawah fee maker + margin (default 10+10); default half spread 25 bps, ambang requote 8 bps.
- [x] Requote asimetris (order yang jadi terlalu dekat dibatalkan lebih cepat dari yang menjauh); sisi kosong dipasang sendiri; beli ditahan saat inventori naik dan harga turun dari puncak jendela 5 menit.
- [ ] Belum dibuktikan menguntungkan di pasar nyata; fee maker masih diisi manual (belum dibaca dari server).

**Pasar: watchlist & grafik harga**
- [x] Dashboard menampilkan **watchlist multi-simbol** (default BTC-USDT, ETH-USDT, lalu pair aktif lain sampai 5; diubah lewat "Ubah daftar", disimpan di DataStore): ikon pair, sparkline, harga tengah order book, kapsul perubahan 24 jam dengan panah hijau naik / merah turun. Satu pair gagal tidak menjatuhkan daftar.
- [x] Ketuk baris membuka **Detail pasar** (`market/{symbol}`): chart **candlestick** dengan volume, label harga, garis harga terakhir, kursor ketuk/geser untuk OHLC; pilihan timeframe 1/5/15 mnt, 1/4 jam, 1 hari (nama enum terverifikasi ke testnet) dan mode Garis; statistik 24 jam; order book 6 level.
- [x] Perubahan 24 jam dihitung dari 25 candle 1 jam (acuan = open candle tertua); bila data lebih pendek, jumlah jam ikut ditampilkan.
- [ ] Bot tetap **satu simbol per sesi**. Bot multi-simbol (satu mesin per pair, batas risiko bersama, status/log per pair) belum dikerjakan.
- [ ] Grafik ekuitas tetap grafik garis (satu titik per menit, candlestick kurang berguna). Belum diuji di HP asli.

**Fee vs laba kotor (setelah uji live 1 Nov)**
- [x] **Lantai jual impas** di Market Maker (`sellAboveCost`, default aktif): harga jual tidak di bawah `biaya x (1 + fee + margin) / (1 - fee)` (default ~30 bps di atas biaya). Biaya = **lot terbaru yang belum terjual** (`FillCostBasis`: sinkron tiap 20 dtk, PnlCalculator), bukan rata-rata campuran; dry run dan biaya tak dikenal memakai harga ideal biasa. Order jual yang sudah terpasang di bawah lantai dibatalkan lalu dipasang ulang di lantai.
- [x] **Perbaikan setelah cek log HP (1 Nov 18:30):** versi pertama memakai rata-rata campuran yang masih membawa lot lama ~80.400, sehingga jual dipasang 57 bps di atas pasar dan bot hanya membeli (18 beli, 0 jual sejak 18:24). Sekarang per lot.
- [x] **PnL per lot, terbaru dulu (LIFO)** menggantikan biaya rata-rata bergerak: jual dicocokkan dengan pembelian terbaru, lot lama tetap terbuka sampai terjual. Replay fill 1 Nov: sebelumnya bersih −5,44; per lot kotor +1,76, fee 1,07, bersih +0,69. Lot lama tidak hilang: posisi terbuka tetap membawa rugi mengambang (biaya rata-rata ~79.371 vs pasar ~77.960).
- [x] Tab Transaksi menampilkan **perubahan ekuitas saat bot jalan** (jumlah per sesi bersambung, tanpa selang bot mati) dan lot terbaru; grafik ekuitas hanya memakai satu satuan quote (titik ETH-BTC ~0,008 tidak lagi mengotori).
- [x] **Rincian laba kotor / fee / bersih** (`PnlBreakdown`) di tab Transaksi dan kartu laporan Dashboard: laba kotor, fee, fee per volume (bps), dan berapa persen laba kotor yang termakan fee.
- [ ] Efek samping lantai: posisi yang rugi ditahan (bukan dijual rugi), jadi kerugian mengambang bisa membesar saat tren turun. Belum ada batas rugi per posisi.
- [ ] Posisi lama yang biayanya tidak ada di riwayat fill tidak dihitung; lantai memakai biaya posisi yang terlacak. Belum dibuktikan menguntungkan di pasar nyata.

**Tampilan: pink, panduan, pengembang**
- [x] Warna aksen utama diganti dari kuning ke **pink** (gelap `#FF5FA2`, terang `#C2185B`): tombol, tab terpilih, ikon, aksen kartu. Kuning tetap dipakai untuk peringatan (`warn`) dan warna merek koin (mis. BNB).
- [x] Layar **Panduan aplikasi** (Setelan > Bantuan): mulai cepat, tab utama, watchlist dan grafik, strategi, risiko, dry run vs LIVE, membaca PnL, pemecahan masalah; bagian bisa dibuka-tutup. Kelompok **Bantuan** berisi Panduan aplikasi, Panduan baterai, dan Tentang.
- [x] **Tentang** menampilkan kartu Pengembang: Azzurahai.
- [ ] Belum dilihat di layar HP; isi panduan perlu disesuaikan bila fitur berubah.

**Layar Strategi ramah (Market Maker)**
- [x] **Profil** Hati-hati / Seimbang / Agresif (`MarketMakerProfiles`): satu ketukan mengisi jarak kuotasi (batas aman + 20/5/1 bps), ambang requote, umur order, batas inventori, skew, cooldown, dan tahan-beli. Nilai order, fee, margin, level, jendela tren, dan saklar impas tidak diubah. Pengaturan yang diubah sendiri otomatis berlabel **Kustom**. Nilai profil adalah pertimbangan, belum diuji untung.
- [x] **Dasar**: nilai per order dan slider jarak kuotasi 10-80 bps dengan penanda batas aman (fee + margin), disertai persen dan jarak dalam USDT dari harga pasar terkini.
- [x] **Hasil satu putaran**: tangga harga (beli, tengah, jual, zona fee diarsir), bar fee vs bersih, dan hitungan teori (mis. 50 - 20 = 30 bps, ~0,03 USDT per order 10) dengan penilaian Tipis/Rugi dan catatan bahwa kenyataan lebih kecil. **Ringkasan** kalimat sebelum Start.
- [x] **Lanjutan** (tertutup) dikelompokkan: Fee dan margin, Jarak dan level, Pembaruan order, Inventori, Deteksi tren. Tiap kolom punya ikon info (arti, contoh, kisaran disarankan) dan peringatan inline untuk nilai yang hampir pasti merugikan (`MarketMakerAdvice.check`).
- [x] **Tahap 3.** **Grid**: rentang dari harga sekarang (±2/5/10%, dibulatkan ke tick), gambar sumbu harga (level beli/jual, zona di luar rentang, zona bot berhenti, penanda harga), slider jumlah bagian, hasil bersih satu siklus setelah fee, kebutuhan modal beli/jual, ringkasan. **DCA**: pilihan interval, jumlah pembelian dan lama sampai selesai, estimasi fee, catatan bahwa DCA tidak menjual. **Risiko**: kartu ringkasan batas dalam quote memakai ekuitas 24 jam terakhir, kolom persen ditulis sebagai persen (`PercentField`), petunjuk dan peringatan per kolom (`GridAdvice`, `DcaAdvice`, `RiskAdvice`).
- [ ] Belum dilihat di layar HP. Fee maker Grid/DCA memakai pengaturan fee Market Maker yang sama (diisi manual).

**Fase 6 — Opsional**
- [ ] Realtime protobuf (jika .proto tersedia) untuk order book dan saldo; polling tetap sebagai fallback.
- [ ] Ekspor log, widget status, `BootReceiver`.

Kriteria selesai per fase: build hijau, unit test lulus, tidak ada secret di log, uji manual terhadap testnet sesuai checklist.

---

## 15. Strategi Pengujian

- **Unit:** signing (test vector), kanonisasi query, numerik, RiskManager, tiap strategi (input snapshot -> intent), Reconciler.
- **Integrasi:** `ConnectClient` dengan MockWebServer (200, 429 dengan retry-after, 5xx, timeout, permission denied).
- **Simulasi:** `FakeExchange` in-memory (order book, matching sederhana, fee) untuk menguji engine dan strategi tanpa jaringan.
- **Manual testnet:** order kecil, uji kill switch, uji restart proses di tengah order, uji jam dimajukan/dimundurkan, uji kehilangan jaringan.
- **Soak test:** 24 jam di HP nyata dengan pemantauan baterai, memori, dan jumlah request.

---

## 16. Risiko & Mitigasi

| Risiko | Mitigasi |
|---|---|
| API alpha berubah | lapisan `data/api` terisolasi, pin versi, cek CHANGELOG SDK. Contoh nyata: skala harga 1e6 -> 1e9 di 0.2.0 dan host testnet pindah dua kali. Saat start, validasi skala dari katalog dan tolak jika tidak masuk akal |
| Dokumentasi resmi tidak publik | andalkan README/CHANGELOG SDK GitHub, simpan salinan catatan di `docs/api-notes.md` |
| JSON hanya "untuk debugging" | antarmuka `Codec`, jalur pindah ke protobuf siap |
| Realtime hanya protobuf | v1 polling; realtime fase opsional |
| Android mematikan service | Foreground Service, daftar pengaturan per merek, Reconciler saat start |
| Jam HP meleset | cek jam berkala, PAUSED + notifikasi |
| Order ganda saat gagal ambigu | `client_order_id` stabil + `get` sebelum retry |
| Kunci bocor | Keystore, policy minimal, tanpa log, tanpa backup |
| Likuiditas testnet tipis/aneh | batas penyimpangan harga, kecilkan ukuran, jeda saat book anomali |
| Dianggap manipulasi/farming | satu akun, tanpa wash trading, tanpa spam order, ikuti aturan resmi |
| Baterai menggembung | HP khusus, batas pengisian, tidak dipakai di tempat panas |

---

## 17. Pertanyaan Terbuka (isi saat Fase 0)

- ~~Base URL testnet~~ (`api.testnet.polyester.com`). Path WebSocket testnet yang tepat?
- Daftar RPC dan bentuk JSON final untuk setiap panggilan yang dipakai?
- Apakah ada self-trade prevention di server?
- Apakah `gtd` dan `batch_replace` tersedia di testnet?
- Apakah `Me`/`ListBalances` cocok sebagai endpoint uji koneksi?
- Apakah ada endpoint untuk sinkronisasi waktu server?
- Apakah .proto publik tersedia?
- Pasangan (simbol) apa yang aktif di testnet dan berapa tick/step/min notional-nya?
- Batas kuota order per menit untuk akun ini?

---

## 18. Aturan Kerja untuk Claude Code

1. Kerjakan satu fase dalam satu waktu. Berhenti dan ringkas di akhir fase.
2. Jangan menebak bentuk API. Jika belum terverifikasi, tulis di `docs/api-notes.md` sebagai pertanyaan dan minta konfirmasi.
3. Tidak ada Double/Float untuk uang, harga, atau kuantitas.
4. Tidak ada secret di kode, log, atau commit.
5. Setiap perubahan logika risiko atau penandatanganan harus disertai unit test.
6. Commit kecil dengan pesan jelas. Jalankan build + test sebelum menandai item selesai.
7. Jika ada keraguan soal aturan kompetisi atau perilaku yang bisa dianggap manipulasi, pilih opsi yang lebih konservatif dan tandai untuk ditinjau pengguna.
