import test from 'node:test';
import assert from 'node:assert/strict';
import { TelegramDebtBot } from './telegramBot.js';

test('TelegramDebtBot - Full chat workflow', () => {
  const bot = new TelegramDebtBot();

  // Test start message
  const startMsg = bot.handleMessage('/start');
  assert.ok(startMsg.includes('Buku Hutang Darfin'));

  // Add debt for Budi
  const budiMsg = bot.handleMessage('budi 50rb');
  assert.ok(budiMsg.includes('Budi'));
  assert.ok(budiMsg.includes('Rp 50.000'));

  // Add debt for Tanto (Sulawesi alias)
  const tantoMsg = bot.handleMessage('La tanto 30.000 beras');
  assert.ok(tantoMsg.includes('La Tanto'));
  assert.ok(tantoMsg.includes('Rp 30.000'));
  assert.ok(tantoMsg.includes('beras'));

  // Tanto adds more
  const tantoMsg2 = bot.handleMessage('tanto 20k');
  assert.ok(tantoMsg2.includes('Rp 50.000')); // 30k + 20k = 50k

  // Rekap
  const rekap = bot.handleMessage('/rekap');
  assert.ok(rekap.includes('Total Uang di Luar'));
  assert.ok(rekap.includes('Rp 100.000')); // Budi 50k + Tanto 50k

  // Payment
  const payMsg = bot.handleMessage('budi bayar 20rb');
  assert.ok(payMsg.includes('Rp 30.000')); // Sisa 30k

  // Settle
  const settleMsg = bot.handleMessage('budi lunas');
  assert.ok(settleMsg.includes('LUNAS'));
});
