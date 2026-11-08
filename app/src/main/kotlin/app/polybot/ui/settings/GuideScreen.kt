package app.polybot.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.polybot.ui.components.NoticeBanner
import app.polybot.ui.theme.trade

private sealed interface GuideItem {
    data class Paragraph(val text: String) : GuideItem
    data class Steps(val steps: List<String>) : GuideItem
    data class Bullets(val points: List<Pair<String, String>>) : GuideItem
}

private data class GuideSection(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val items: List<GuideItem>)

private val sections = listOf(
    GuideSection(
        "Mulai cepat", Icons.Rounded.RocketLaunch,
        listOf(
            GuideItem.Steps(
                listOf(
                    "Setelan > Akun & API Key: pilih environment, isi API key id dan private key dari Polyester, tekan Simpan lalu Tes koneksi. Private key disimpan terenkripsi di Android Keystore dan tidak pernah ditampilkan lagi.",
                    "Di aplikasi Polyester, pindahkan Paper Assets dari Funding ke Unified Trading. Tab Saldo hanya menampilkan aset yang punya nilai.",
                    "Setelan > Strategi: pilih strategi dan simbol dari daftar.",
                    "Setelan > Risiko: periksa batas order, eksposur, dan rugi harian. Nilai bawaan sengaja kecil.",
                    "Setelan > Kontrol Bot: mulai dengan mode Dry run. Setelah paham, matikan Dry run lalu tekan Mulai (ada konfirmasi mode LIVE).",
                    "Setelan > Panduan baterai: bebaskan aplikasi dari optimasi baterai supaya bot tetap berjalan saat layar mati.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Tab utama", Icons.Rounded.SpaceDashboard,
        listOf(
            GuideItem.Bullets(
                listOf(
                    "Dashboard" to "Grafik ekuitas, status bot, watchlist pasar, ringkasan laporan transaksi, dan status koneksi.",
                    "Transaksi" to "Riwayat fill dengan filter rentang waktu, sisi, dan simbol. Tombol sinkron menarik fill terbaru; ekspor CSV tersedia. Ringkasan menampilkan laba kotor, fee, dan PnL bersih.",
                    "Order" to "Order terbuka (diperbarui tiap 10 detik) dan riwayat order, termasuk alasan order ditutup.",
                    "Saldo" to "Saldo akun Unified Trading per aset.",
                    "Log" to "Catatan kegiatan bot: order dikirim, dibatalkan, ditolak, dan peringatan. Mulai dari sini bila ada yang janggal.",
                    "Setelan" to "Semua kontrol dan konfigurasi bot ada di sini.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Watchlist dan grafik harga", Icons.Rounded.CandlestickChart,
        listOf(
            GuideItem.Paragraph("Watchlist di Dashboard menampilkan beberapa pair sekaligus: harga tengah order book dan perubahan 24 jam."),
            GuideItem.Bullets(
                listOf(
                    "Panah hijau ke atas" to "Harga naik dibanding 24 jam lalu.",
                    "Panah merah ke bawah" to "Harga turun dibanding 24 jam lalu.",
                    "Ubah daftar" to "Pilih pair yang dipantau dari daftar. Pilihan tersimpan di HP.",
                    "Ketuk sebuah pair" to "Membuka grafik candlestick dengan volume. Lilin hijau berarti harga tutup di atas harga buka, merah berarti di bawah.",
                    "Timeframe dan mode" to "Pilih 1, 5, 15 menit, 1 jam, 4 jam, atau 1 hari, atau ganti ke grafik Garis. Ketuk atau geser pada grafik untuk melihat Buka, Tinggi, Rendah, dan Tutup per candle. Order book 6 level ada di bawah grafik.",
                ),
            ),
            GuideItem.Paragraph("Watchlist hanya untuk memantau. Bot tetap memperdagangkan satu simbol per sesi, yaitu yang dipilih di Setelan > Strategi."),
        ),
    ),
    GuideSection(
        "Strategi", Icons.Rounded.Tune,
        listOf(
            GuideItem.Bullets(
                listOf(
                    "Market Maker ringan" to "Memasang order beli di bawah dan jual di atas harga tengah, lalu mengambil selisihnya. Setiap fill kena fee, jadi selisih yang tertangkap harus lebih besar dari fee. Half spread tidak pernah di bawah fee maker ditambah margin minimum. Beli ditahan saat inventori sudah bertambah dan harga turun cepat. Saklar \"Jual tidak di bawah impas\" mencegah menjual di bawah biaya pembelian terbaru ditambah fee.",
                    "Grid" to "Memasang tangga order beli dan jual di antara batas bawah dan batas atas. Bot berhenti bila harga keluar rentang melewati jarak yang diatur.",
                    "DCA terjadwal" to "Membeli nilai tetap setiap interval sampai total anggaran habis.",
                ),
            ),
            GuideItem.Paragraph("Di layar Strategi, mulai dari Profil (Hati-hati, Seimbang, Agresif) lalu atur Nilai per order dan Jarak kuotasi. Kartu \"Hasil satu putaran\" menghitung teori untung bersih setelah fee, dan ikon info di tiap kolom Lanjutan menjelaskan artinya."),
            GuideItem.Paragraph("Grid: pilih rentang dari harga sekarang (±2%, ±5%, ±10%) atau isi batasnya, lalu atur jumlah bagian. Gambar rentang menunjukkan level beli (hijau), level jual (merah), harga sekarang, dan zona tempat bot berhenti. Kartu hasil menghitung laba bersih satu siklus setelah fee dan modal yang dibutuhkan."),
            GuideItem.Paragraph("DCA: pilih interval, nilai per pembelian, dan total anggaran. Layar menghitung jumlah pembelian, lama sampai selesai, dan estimasi fee. DCA hanya membeli."),
            GuideItem.Paragraph("Fee maker diisi manual (Market Maker: Lanjutan; Grid: kolom Fee maker) dan dipakai bersama. Testnet sekitar 10 bps per fill. Bila tarif server berbeda, sesuaikan angkanya."),
        ),
    ),
    GuideSection(
        "Risiko dan pengaman", Icons.Rounded.Security,
        listOf(
            GuideItem.Bullets(
                listOf(
                    "Ringkasan batas" to "Layar Risiko menerjemahkan persen ke nilai quote memakai ekuitas terakhir yang terekam, mis. 2% ekuitas ≈ 139 USDT. Bila belum ada, jalankan bot sebentar. Persen ditulis sebagai persen, dan ikon info di tiap kolom menjelaskan artinya.",
                    "Batas order" to "Order ditolak bila nilainya melebihi batas per order, batas persen ekuitas, batas eksposur aset dasar, atau harganya menyimpang jauh dari harga tengah.",
                    "Jeda otomatis" to "Bot dijeda saat error beruntun mencapai batas, jam HP melenceng, jaringan terputus, atau pair tidak menerima order baru. Bot lanjut sendiri saat kondisi pulih, kecuali dijeda karena error beruntun (menunggu Anda menekan Lanjut).",
                    "Berhenti otomatis" to "Rugi harian maksimum tercapai: bot berhenti dan membatalkan ordernya.",
                    "Kill switch" to "Membatalkan order milik bot. Bila opsinya diaktifkan di Kontrol Bot, semua order di pair itu dibatalkan, termasuk order manual Anda.",
                    "Simbol diizinkan" to "Bot menolak berjalan di simbol yang tidak ada di daftar Simbol yang diizinkan.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Dry run dan LIVE", Icons.Rounded.Science,
        listOf(
            GuideItem.Bullets(
                listOf(
                    "Dry run" to "Keputusan bot hanya dicatat di Log. Tidak ada order dikirim dan fill tidak disimulasikan, jadi tidak ada PnL. Cocok untuk memeriksa apakah harga dan jumlah order masuk akal.",
                    "LIVE" to "Order sungguhan dikirim ke testnet memakai Paper Assets. Order dipasang sebagai post-only dan diberi client order ID unik; hasil yang meragukan selalu dicek ulang sebelum order baru dikirim.",
                ),
            ),
            GuideItem.Paragraph("Mode dan pengaturan hanya bisa diubah saat bot berhenti."),
        ),
    ),
    GuideSection(
        "Membaca PnL", Icons.Rounded.Assessment,
        listOf(
            GuideItem.Bullets(
                listOf(
                    "Laba kotor" to "Selisih harga jual dan beli per lot. Jual dicocokkan dengan pembelian terbaru yang masih terbuka, jadi lot lama yang mahal tidak menyeret putaran baru.",
                    "Fee" to "Dihitung terpisah dari setiap fill. Satu putaran beli lalu jual kena fee dua kali (sekitar 20 bps di testnet).",
                    "PnL bersih" to "Laba kotor dikurangi fee. Bila fee memakan hampir seluruh laba kotor, strategi belum punya keunggulan setelah biaya.",
                    "Perubahan ekuitas" to "Selisih ekuitas selama bot berjalan, termasuk naik turunnya harga aset yang dipegang. Waktu bot mati tidak dihitung.",
                    "Lot lama" to "Pembelian lama yang belum terjual tetap ada di posisi sebagai untung atau rugi mengambang sampai terjual.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Pemecahan masalah", Icons.Rounded.Build,
        listOf(
            GuideItem.Bullets(
                listOf(
                    "HTTP 503 atau \"Service temporarily unavailable\"" to "Gangguan sementara di server. Bot mencoba lagi pada siklus berikutnya; bila errornya beruntun sampai batas, bot dijeda.",
                    "\"Hasil order belum pasti\"" to "Respons order tidak terbaca. Bot memeriksa order itu lewat client order ID sebelum mengirim yang baru. Tidak perlu tindakan.",
                    "\"Duplicate client order ID\"" to "Ditolak server. Bot memeriksa order terkait; bila sering muncul, lihat Log.",
                    "Selisih jam" to "Aktifkan waktu otomatis di HP. Tanda tangan permintaan ditolak bila jam melenceng.",
                    "Notifikasi tidak muncul" to "Periksa Setelan > Polling & Notifikasi.",
                    "Bot berhenti di latar belakang" to "Ikuti Setelan > Panduan baterai untuk merek HP Anda.",
                    "Order tidak terisi" to "Harga bergeser menjauhi order. Market Maker membatalkan dan memasang ulang order yang terlalu lama atau terlalu jauh.",
                ),
            ),
        ),
    ),
)

@Composable
fun GuideScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Cara memakai PolyBot dari awal sampai membaca hasilnya. Ketuk judul untuk membuka atau menutup bagian.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NoticeBanner(
            "PolyBot hanya untuk testnet (Paper Assets). Hasilnya tidak mencerminkan pasar sungguhan dan bukan saran keuangan.",
            Icons.Rounded.WarningAmber, MaterialTheme.trade.warn,
        )
        sections.forEachIndexed { index, section -> SectionCard(section, startOpen = index == 0) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(section: GuideSection, startOpen: Boolean) {
    var open by rememberSaveable(section.title) { mutableStateOf(startOpen) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Surface(onClick = { open = !open }, color = androidx.compose.ui.graphics.Color.Transparent) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(36.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(section.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                    Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    section.items.forEach { Item(it) }
                }
            }
        }
    }
}

@Composable
private fun Item(item: GuideItem) {
    when (item) {
        is GuideItem.Paragraph -> Text(item.text, style = MaterialTheme.typography.bodyMedium)
        is GuideItem.Steps -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(24.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary) }
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        is GuideItem.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.points.forEach { (term, text) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
