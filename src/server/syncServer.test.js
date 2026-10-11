const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const { server, state } = require('./syncServer');

test('Cloud Sync Server - POST /api/sync merges transactions and recalculates totals', async () => {
  state.transactions = [];
  state.debtors = [];
  await new Promise(resolve => server.listen(0, resolve));
  const testPort = server.address().port;

  const payload = JSON.stringify({
    transactions: [
      {
        uuid: 'tx-001',
        debtorName: 'La tanto',
        canonicalKey: 'tanto',
        amount: 50000,
        type: 'DEBT',
        note: 'Beli beras',
        createdAt: '11 Okt 2026, 12:00',
        timestamp: 1700000000000
      },
      {
        uuid: 'tx-002',
        debtorName: 'tanto',
        canonicalKey: 'tanto',
        amount: 20000,
        type: 'PAYMENT',
        note: 'Cicil',
        createdAt: '11 Okt 2026, 14:00',
        timestamp: 1700000001000
      }
    ]
  });

  const response = await new Promise((resolve, reject) => {
    const req = http.request({
      hostname: 'localhost',
      port: testPort,
      path: '/api/sync',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload)
      }
    }, res => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => resolve({ status: res.statusCode, data: JSON.parse(body) }));
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });

  assert.equal(response.status, 200);
  assert.equal(response.data.success, true);
  assert.equal(response.data.mergedCount, 2);

  // Check calculated debtor balance
  const tanto = response.data.debtors.find(d => d.canonicalKey === 'tanto');
  assert.ok(tanto, 'Debtor Tanto must exist');
  assert.equal(tanto.totalDebt, 30000, '50rb debt - 20rb payment = 30rb');

  await new Promise(resolve => server.close(resolve));
});
