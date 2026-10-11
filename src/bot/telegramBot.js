import { DebtStore } from '../db/debtStore.js';

/**
 * Format integer amount to Indonesian Rupiah string
 * e.g. 50000 -> "Rp 50.000"
 */
export function formatRupiah(amount) {
  return 'Rp ' + (amount || 0).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

/**
 * Telegram Bot Controller for "Buku Hutang Darfin"
 */
export class TelegramDebtBot {
  constructor(debtStore = new DebtStore()) {
    this.store = debtStore;
  }

  /**
   * Handle text message received from Telegram
   */
  handleMessage(text) {
    if (!text || typeof text !== 'string') {
      return 'Format pesan kosong.';
    }

    const trimmed = text.trim();

    // 1. Command /start or /help
    if (trimmed === '/start' || trimmed === '/help' || trimmed.toLowerCase() === 'bantuan') {
      return [
        '📗 *Selamat Datang di Buku Hutang Darfin* 📗',
        '',
        'Ketik langsung catatan hutang seperti biasa, bot akan otomatis mencatat:',
        '• Catat hutang: `budi 50rb` atau `la tanto 20k telur`',
        '• Catat bayar: `budi bayar 20rb` atau `latanto cicil 10k`',
        '• Pelunasan: `budi lunas`',
        '',
        'Perintah bot:',
        '• `/rekap` : Lihat total uang di luar dan daftar yang berhutang',
        '• `/cek <nama>` : Cek sisa hutang orang tertentu',
      ].join('\n');
    }

    // 2. Command /rekap
    if (trimmed === '/rekap' || trimmed.toLowerCase() === 'rekap') {
      const summary = this.store.getSummary();
      if (summary.count === 0) {
        return '🎉 *Alhamdulillah!* Saat ini tidak ada catatan hutang yang belum lunas.';
      }

      let res = `📊 *Rekap Buku Hutang Darfin*\n`;
      res += `💰 *Total Uang di Luar:* *${formatRupiah(summary.grandTotal)}*\n`;
      res += `👥 *Jumlah Orang:* ${summary.count} orang\n\n`;
      res += `*Daftar Hutang:*\n`;

      summary.debtors.forEach((d, idx) => {
        res += `${idx + 1}. *${d.name}*: ${formatRupiah(d.totalDebt)}\n`;
      });

      return res;
    }

    // 3. Command /cek <nama>
    if (trimmed.startsWith('/cek')) {
      const targetName = trimmed.replace('/cek', '').trim();
      if (!targetName) return 'Silakan sebutkan nama, contoh: `/cek budi`';
      
      const contact = this.store.getContact(targetName);
      if (!contact || contact.totalDebt === 0) {
        return `✅ *${targetName}* tidak memiliki catatan hutang saat ini.`;
      }
      return `👤 *${contact.name}*\n💰 Total sisa hutang: *${formatRupiah(contact.totalDebt)}*`;
    }

    // 4. Natural Language Parsing (Tahap 1 Engine)
    const result = this.store.processMessage(trimmed);

    if (!result.success) {
      return (
        '⚠️ *Pesan Tidak Dikenali*\n' +
        'Ketik nama dan nominal, contoh:\n' +
        '• `budi 50rb`\n' +
        '• `la tanto 20.000 beras`\n' +
        '• `budi bayar 15rb`'
      );
    }

    const { parsed, previousDebt, newTotal } = result;
    const { name, amount, type, note } = parsed;

    if (type === 'DEBT') {
      let msg = `✅ *Catatan Hutang Ditambahkan!*\n`;
      msg += `👤 Nama: *${name}*\n`;
      msg += `➕ Nominal: *+${formatRupiah(amount)}*`;
      if (note) msg += ` (${note})`;
      msg += `\n💰 Total Hutang Sekarang: *${formatRupiah(newTotal)}*`;
      return msg;
    } else if (type === 'PAYMENT') {
      let msg = `💚 *Pembayaran Dicatat!*\n`;
      msg += `👤 Nama: *${name}*\n`;
      msg += `➖ Bayar: *-${formatRupiah(amount)}*\n`;
      msg += `💰 Sisa Hutang: *${formatRupiah(newTotal)}*`;
      return msg;
    } else if (type === 'SETTLED') {
      return `🎉 *LUNAS!* Hutang atas nama *${name}* telah dilunasi semua (Sisa: ${formatRupiah(0)}).`;
    }

    return 'Catatan berhasil diperbarui.';
  }
}
