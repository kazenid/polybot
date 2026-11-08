# API Notes (Fase 0)

Sumber: README/CHANGELOG `polyester-sdk-python` 0.3.0 dan stub `.pyi` di `src/polyester/gen/` (dibaca 31 Okt 2026).
Belum ada panggilan nyata ke testnet, jadi **tidak ada contoh respons nyata**. Semua bentuk JSON di bawah diturunkan dari stub protobuf dan harus dikonfirmasi saat "Tes koneksi" (Fase 2). Tipe `int64` dipetakan ke Connect JSON sebagai **string desimal** (aturan proto3 JSON); parser harus menerima angka maupun string.

## 1. Environment

| | API | WebSocket | Chain |
|---|---|---|---|
| Testnet | `https://api.testnet.polyester.com` | `wss://api.testnet.polyester.com` | 888169 |
| Devnet | `https://api.devnet.polyester.com` | `wss://api.devnet.polyester.com` | 888168 |

Path WebSocket tidak didefinisikan di `environment.py` (hanya host). Stream privat: `/v1/ws/private`.

## 2. Transport (Connect, unary)

- `POST {api_url}/{service}/{method}`, body = pesan request. Timeout default SDK 10 detik, batas respons 4 MiB.
- Kompresi request **dimatikan**: gzip merusak hash body yang ditandatangani.
- Header `Content-Type: application/json` dan `Connect-Protocol-Version: 1` tidak diset di source SDK (diserahkan ke library `connectrpc`). **[VERIFIKASI]** di Fase 2; spesifikasi Connect untuk unary JSON memang memakai keduanya.
- Decoder JSON SDK memakai `ignore_unknown_fields=True`. Parser Kotlin: `ignoreUnknownKeys = true`, enum tak dikenal -> UNSPECIFIED.
- Penolakan gateway (non-200, JSON) punya field `code` string di level atas, mis. `TIMESTAMP_SKEW`. Status HTTP 429 dan 502-504 diperlakukan sebagai `UNAVAILABLE`.
- Semua RPC bertanda `IdempotencyLevel.UNKNOWN`, jadi pakai POST.

## 3. RPC yang dipakai bot

| Fungsi | Path |
|---|---|
| Uji koneksi | `/auth.v1.AuthService/Me` |
| Katalog | `/marketdata.v1.MarketDataService/GetSpotConfig` |
| Candle | `/marketdata.v1.MarketDataService/GetCandles` (`GetCandlesColumns` = oldest-first) |
| Trade pasar | `/marketdata.v1.MarketDataService/GetTrades` |
| Order book | `/orderbook.v1.OrderbookService/GetOrderBook` |
| Saldo | `/ledger.read.v1.LedgerReadService/GetBalances` |
| Riwayat saldo/ekuitas | `.../GetBalanceHistory`, `.../GetEquityHistorySeries` |
| Buat order | `/orders.v1.OrdersService/CreateOrder` |
| Batal order | `/orders.v1.OrdersService/CancelOrder` |
| Batal semua | `/orders.v1.OrdersService/CancelAllOrders` |
| Batch | `.../BatchCreateOrders`, `.../BatchCancelOrders`, `.../BatchReplaceOrders` |
| Ubah order | `/orders.v1.OrdersService/ModifyOrder` |
| Preview | `/orders.v1.OrdersService/PreviewOrder` (bisa 404/501 di host tertentu) |
| Order terbuka | `/orders.v1.OrdersReadService/GetOpenOrders` |
| Riwayat order | `/orders.v1.OrdersReadService/GetOrderHistory` |
| Satu order | `/orders.v1.OrdersReadService/GetOrder` |
| Fill pengguna | `/orders.v1.OrdersReadService/GetUserTrades` |
| Status batch replace | `/orders.v1.OrdersReadService/GetBatchReplaceStatus` |

Catatan: paket read adalah `orders.v1.OrdersReadService`, **bukan** `orders.read.v1`. Rate limit: `ratelimit.v1.RateLimitService` (path belum dibaca).

## 4. Bentuk pesan (dari stub; nama JSON = camelCase)

### Order

```
CreateOrderRequest  { subaccountId, order: OrderIntent }
OrderIntent {
  symbolId, side: BUY|SELL,
  baseQtyScaled            // atau maxQuoteDebitScaled (tepat salah satu)
  maxQuoteDebitScaled
  // tepat satu bentuk order (oneof):
  marketIoc { maxSlippageTicks, maxSlippageBps, clientRefPriceTicks }
  limitGtc  { priceTicks, postOnly }
  limitIoc  { priceTicks }
  limitFok  { priceTicks }
  limitGtd  { priceTicks, postOnly, expireAt }
  clientOrderId, feeAsset: QUOTE|BASE,
  selfTradePreventionMode: EXPIRE_MAKER|EXPIRE_TAKER|EXPIRE_BOTH,
  attachedRisk
}
CreateOrderResponse { orderId, clientOrderId, acceptedAt, acceptedAtTsNs,
  resolvedBaseQtyScaled, submittedMaxQuoteDebitScaled, ...triggerIds }
CancelOrderRequest  { orderId | clientOrderId, symbolId, subaccountId }
CancelOrderResponse { status: ACCEPTED, orderId, ts, tsNs }
CancelAllOrdersRequest { subaccountId, symbolIds[], side, dryRun, requestId }
BatchCreateOrdersRequest  { subaccountId, requestId, items: OrderIntent[] }
BatchCancelOrdersRequest  { subaccountId, requestId, items: BatchCancelItem[] }
BatchReplaceOrdersRequest { subaccountId, symbolId, requestId, items[] }
```

Temuan penting: **server punya self-trade prevention** (`selfTradePreventionMode`). Bot wajib mengisinya eksplisit (default usulan: `EXPIRE_TAKER`) selain pengecekan di Risk Manager. Nilai `Side`/`OrderType`/`TimeInForce` di stub hanya berupa nama, nomor enum belum diketahui; Connect JSON memakai **nama enum** sebagai string.

### Baca order

```
GetOpenOrdersRequest  { subaccountId, symbolId[], side, limit, pageToken }
GetOpenOrdersResponse { orders: Order[], nextPageToken }
GetOrderRequest       { subaccountId, orderId | clientOrderId, includeExecutionHistory, ... }
GetOrderHistoryRequest{ subaccountId, symbolId[], side, status, startTsNs, endTsNs, limit, pageToken }
Order {
  orderId, symbolId, clientOrderId, side,
  status: PENDING|PENDING_CANCEL|WORKING|FILLED|CANCELED|REJECTED,
  orderType: LIMIT|MARKET, timeInForce: GTC|IOC|FOK|GTD, postOnly,
  origQtyScaled, cumQtyScaled, leavesQtyScaled, avgPriceTicks, priceTicks,
  createdTsNs, terminalTsNs, terminalReasonCode, terminalReason,
  selfTradePreventionMode, feeAsset, expireAt, version, lineage
}
```

### Saldo

```
GetBalancesRequest  { subaccountId }
GetBalancesResponse { balances: AssetBalance[] }
AssetBalance { assetId, trading: U128, funding: U128, reserved: U128, available: U128,
               tradingRevision, fundingRevision }
U128 { hi, lo }          // 128-bit sebagai dua int64; JSON: dua string desimal
```

Skala nilai saldo (kemungkinan 18 desimal, lihat `format_ledger_u128` dan `fee_amount_e18`) **belum terkonfirmasi**; `assetId` dipetakan ke aset lewat `AssetConfig.ledgerId`.

### Katalog (GetSpotConfig, tanpa field request)

```
GetSpotConfigResponse { assets: AssetConfig[], pairs: PairConfig[], tsSec, maxOpenOrdersTotal }
AssetConfig { asset, ledgerId, name, quantityDisplayDecimals, quantityScale, marketDataVolumeScale }
PairConfig {
  symbolId, symbol, baseAsset, quoteAsset,
  tickSize: string, stepSize: string, minNotionalQuote: string, minQtyBase: string,   // desimal string
  baseQuantityScale, quoteQuantityScale, referencePriceScale,
  status: ENABLED|DISABLED|CANCEL_ONLY|POST_ONLY|REDUCE_ONLY|HALTED,
  defaultMarketSlippageBpsBuy/Sell, maxClientRefDriftBps,
  maxLimitQtyBase, maxMarketQtyBase, maxOrderNotionalQuote, maxMarketNotionalQuote,
  maxOpenOrdersPerPair, listingAt, delistingAt, allowBuyFeeFromBase
}
```

Bot hanya boleh trading pair berstatus `ENABLED` (atau `POST_ONLY` untuk quote post-only). `HALTED`/`CANCEL_ONLY` -> PAUSED.

### Market data

```
GetOrderBookRequest  { symbolId, depth: DEPTH_1|5|10|20|50|100|200|500|1000 }
GetOrderBookResponse { symbolId, bookSeq, bids: PriceLevel[], asks: PriceLevel[], ts }
PriceLevel { priceTicks, qtyScaled }
GetCandlesRequest  { symbolId, timeframe, limit, startTime, endTime, includeIncomplete, includeReference, pageToken }
CandlePoint { tsSec, open, high, low, close, volume, isClosed, quoteVolume }
GetTradesResponse { trades: MarketTrade[], nextPageToken, completeThroughTradeId, nextAfterTradeId }
MarketTrade { symbolId, matchId, isBuy, priceTicks, qtyScaled, tsNs }
```

Pagination trades: `pageToken` / `nextAfterTradeId` (bukan `from_match_id`).
OHLC candle bertipe integer; skalanya kemungkinan `referencePriceScale`/ticks. **[VERIFIKASI]**.

### Auth

```
MeRequest {}
MeResponse { accountId, apiKeyId, username, rootSmartAccountAddress, session }
```

`Me` cocok sebagai endpoint "Tes koneksi" (terautentikasi, tanpa efek samping).

## 5. Error

Lihat tabel di plan.md bagian 3. Tambahan dari `errors.py`:
- `*_OUTCOME_UNKNOWN` (cocokkan dengan suffix) -> tidak retryable, baca balik dulu.
- `AUTH_TEMPORARILY_UNAVAILABLE` -> retryable. `TIMESTAMP_SKEW` -> retryable dengan tanda tangan ulang.
- Timeout -> retryable tetapi mutasi mungkin sudah terjadi.
- Kode MFA (`AUTH_MFA_*`, `AUTH_STEP_UP_REQUIRED`) tidak relevan untuk API key.
- Not found: `not_found` atau akhiran `_not_found`; 404 route -> `route_not_found`.

## 5b. Hasil probe endpoint publik testnet (31 Okt 2026, tanpa kredensial)

Request: `POST https://api.testnet.polyester.com/{service}/{method}` dengan `Content-Type: application/json` dan `Connect-Protocol-Version: 1`. Semua berhasil (HTTP 200) tanpa header lain.

- **`int64` dikirim sebagai string** (`"tsSec":"1793461441"`, `"priceTicks":"77512010000000"`, `"qtyScaled":"173683226"`); `int32` dan field kecil sebagai angka (`"symbolId":1`, `"baseQuantityScale":6`). Parser harus menerima keduanya.
- **Enum = nama panjang** (`"status":"PAIR_STATUS_ENABLED"`, `"policyClass":"TRADING_RATE_LIMIT_CLASS_PLACE"`). Nama enum timeframe: `SEC_1, MIN_1, MIN_3, MIN_5, MIN_15, MIN_30, HOUR_1, HOUR_2, HOUR_4, HOUR_6, HOUR_8, HOUR_12, DAY_1, DAY_3, WEEK_1, MONTH_1`. Nama tak dikenal diam-diam dibaca sebagai nilai 0 lalu ditolak validasi (`must not be in list [0]`).
- **Field bernilai nol/false dihilangkan** (`isBuy` tidak ada untuk sell; `maxOpenOrdersTotal`, batas order, `allowBuyFeeFromBase` tidak muncul). Semua DTO wajib punya default.
- **Harga `priceTicks` = 1e9** terkonfirmasi (BTC-USDT 77.512,01 -> `77512010000000`). `qtyScaled` memakai `baseQuantityScale` pair (BTC = 8: `173683226` = 1,73683226 BTC).
- `tickSize`, `stepSize`, `minNotionalQuote`, `minQtyBase` berupa string desimal (`"0.01"`, `"0.00001"`, `"5"`).
- Candle: `GetCandles` newest-first; OHLC dalam ticks (1e9) sebagai string, `volume` dalam skala base, `quoteVolume` string desimal.
- Testnet punya 19 pair (mis. `BTC-USDT` id 1, `ETH-USDT` id 2, `SOL-USDT` id 3); `BTC-USDC` berstatus DISABLED. Aset berawalan `t` (tBTC, ...) adalah aset deposit chain, bukan pair trading.
- **Error format**: gateway `{"code":"UNAUTHENTICATED"}` (HTTP 401, `application/json`) atau problem+json `{"type":...,"title":"Unauthenticated","status":401,"detail":"...","code":"UNAUTHENTICATED"}`. Error Connect biasa `{"code":"invalid_argument","message":"...","details":[{"type":"buf.validate.Violations","value":"<base64>","debug":{...}}]}`.
- **Rate limit** (`ratelimit.v1.RateLimitService/GetRateLimitConfig`, publik): tier 0 PLACE `quotaWeight 250 / 1000 ms`, burst 500; CANCEL `500 / 1000 ms`, burst 1000 (bobot per request belum diketahui). Tier VIP naik sampai 5000.
- Respons membawa header `Date` (dipakai untuk pengecekan selisih jam). Header CORS gateway menyebut `X-API-NONCE` selain tiga header tanda tangan; SDK tidak memakainya, jadi diabaikan.
- Endpoint terautentikasi tanpa kredensial: HTTP 401 `application/problem+json`, `code: UNAUTHENTICATED`.
- `Me`, `GetBalances`, `GetOpenOrders` **belum teruji** (butuh API key): bentuk `U128` (`hi`/`lo`) masih dari stub.
- Tanda tangan API key **diterima server** (1 Nov 2026, uji pengguna di HP): panggilan `Me` lolos autentikasi. Respons `Me` sempat gagal diparse karena `accountId` ternyata bukan angka (Account ID di aplikasi berupa string, mis. `RLxqJGUDg92`); DTO kini menerima string maupun angka. Bentuk lengkap respons masih menunggu konfirmasi.

## 5c. Request order yang dikirim bot (Fase 3, belum teruji ke server)

Diturunkan dari stub protobuf; endpoint mutasi butuh API key, jadi bentuk ini baru terbukti saat "Uji order manual" dijalankan.

```
POST /orders.v1.OrdersService/CreateOrder
{"order":{"symbolId":"1","side":"BUY","baseQtyScaled":"10000",
  "limitGtc":{"priceTicks":"77000000000000","postOnly":true},
  "clientOrderId":"pbab12-1","selfTradePreventionMode":"EXPIRE_TAKER"}}

POST /orders.v1.OrdersService/CancelOrder        {"clientOrderId":"pbab12-1","symbolId":"1"}
POST /orders.v1.OrdersReadService/GetOrder       {"clientOrderId":"pbab12-1"}   -> {"order":{...}}
POST /orders.v1.OrdersReadService/GetOpenOrders  {"limit":100,"pageToken":""}
```

Asumsi yang harus dikonfirmasi: (1) bentuk oneof `limitGtc`/`limitGtd`/`limitIoc`/`limitFok`; (2) `GetOrder` cukup dengan `clientOrderId` tanpa `symbolId`; (3) kode error untuk order tidak ditemukan (`not_found`) dan ID ganda (`CONFLICT_DUPLICATE_CLIENT_ORDER_ID`, detail `existing_order_id`); (4) `selfTradePreventionMode` boleh diisi pada order post-only; (5) apakah `subaccountId` harus dikirim.

Perilaku bot yang bergantung pada asumsi itu gagal aman: error validasi dari server membuat order berstatus REJECTED, hasil ambigu membuat bot membaca balik dulu sebelum mengirim ulang.

## 5d. Request laporan (Fase 4c, belum teruji ke server)

```
POST /orders.v1.OrdersReadService/GetUserTrades
{"symbolId":"1","limit":100,"pageToken":"","afterMatchId":"0"}
-> {"trades":[{"symbolId","matchId","orderId","side":"BUY|SELL","isMaker","priceTicks","qtyScaled",
               "feeAmountE18":{"hi","lo"},"feeAsset":"QUOTE|BASE","feeIsRebate","tsNs"}],"nextPageToken"}

POST /orders.v1.OrdersReadService/GetOrderHistory   {"limit":100,"pageToken":""}   -> {"orders":[Order],"nextPageToken"}
```

Asumsi: (1) `afterMatchId` menyaring fill yang lebih baru dan `pageToken` melanjutkan halaman; sinkron aman diulang karena fill dideduplikasi dengan kunci (matchId, orderId). (2) `feeAmountE18` berskala 18 desimal dalam satuan aset fee; fee aset dasar dikonversi ke quote pada harga fill. (3) `GetOrderHistory` tanpa filter simbol mengembalikan semua pair.

## 6. Pertanyaan yang masih terbuka

1. Representasi JSON `int64` dan `U128` yang sebenarnya (string vs angka) dan skala saldo. -> Fase 2.
2. Header Connect yang disyaratkan gateway. -> Fase 2.
3. Path RateLimitService dan bentuk `RateLimitDetail` di JSON. -> Fase 2.
4. Apakah `GetUserTrades` memakai `pageToken` dan field fee (`feeAmountE18`, `feeIsRebate`). -> Fase 2.
5. Bentuk `BatchReplaceOrderItem` dan `BatchCancelItem`. -> sebelum dipakai di market maker.
6. Daftar pair aktif di testnet beserta tick/step. -> Fase 2 (dari GetSpotConfig).
7. Sumber `.proto` publik: **tidak ada**; hanya stub `.pyi` dan `_pb2.py` (descriptor terserialisasi) di paket SDK. Realtime tetap ditunda.
8. Aturan kompetisi/reward testnet: baca manual di Discord/X Polyester.
