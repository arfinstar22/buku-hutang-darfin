// Buku Hutang Darfin - Lightweight Real-Time Cloud Sync Server
// Zero-dependency Node.js HTTP server deployable to Render / Railway / Local

const http = require('http');
const fs = require('fs');
const path = require('path');
const { parseDebtInput, normalizeSulawesiName } = require('../parser/smartParser');

const PORT = process.env.PORT || 3000;
const DB_FILE = path.join(__dirname, 'sync_data.json');

// In-memory state with file persistence
let state = {
  transactions: [],
  debtors: []
};

// Load saved data
if (fs.existsSync(DB_FILE)) {
  try {
    const raw = fs.readFileSync(DB_FILE, 'utf8');
    state = JSON.parse(raw);
    console.log(`[SyncServer] Loaded ${state.transactions.length} transactions from disk.`);
  } catch (err) {
    console.error('[SyncServer] Error reading db file:', err.message);
  }
}

function saveToDisk() {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(state, null, 2), 'utf8');
  } catch (err) {
    console.error('[SyncServer] Failed to save db:', err.message);
  }
}

function recalculateDebtors() {
  const debtorMap = new Map();

  // Sort transactions by timestamp ascending
  state.transactions.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));

  for (const tx of state.transactions) {
    const key = tx.canonicalKey || normalizeSulawesiName(tx.debtorName);
    if (!debtorMap.has(key)) {
      debtorMap.set(key, {
        name: tx.debtorName,
        canonicalKey: key,
        totalDebt: 0,
        lastNote: tx.note || '',
        updatedAt: tx.timestamp || Date.now()
      });
    }

    const debtor = debtorMap.get(key);
    debtor.lastNote = tx.note || debtor.lastNote;
    debtor.updatedAt = tx.timestamp || Date.now();

    const amt = Number(tx.amount) || 0;
    if (tx.type === 'DEBT') {
      debtor.totalDebt += amt;
    } else if (tx.type === 'PAYMENT') {
      debtor.totalDebt = Math.max(0, debtor.totalDebt - amt);
    } else if (tx.type === 'SETTLED') {
      debtor.totalDebt = 0;
    }
  }

  state.debtors = Array.from(debtorMap.values()).sort((a, b) => b.totalDebt - a.totalDebt);
}

const server = http.createServer((req, res) => {
  // CORS Headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host}`);

  // Health check
  if (url.pathname === '/health' || url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      service: 'Buku Hutang Darfin Realtime Sync Server',
      transactions: state.transactions.length,
      debtors: state.debtors.length,
      uptime: process.uptime()
    }));
    return;
  }

  // GET /api/sync
  if (req.method === 'GET' && url.pathname === '/api/sync') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      success: true,
      allTransactions: state.transactions,
      debtors: state.debtors
    }));
    return;
  }

  // POST /api/sync (Merge batch of transactions)
  if (req.method === 'POST' && url.pathname === '/api/sync') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        const payload = JSON.parse(body || '{}');
        const incoming = Array.isArray(payload.transactions) ? payload.transactions : [];

        const existingUuids = new Set(state.transactions.map(t => t.uuid));
        let newCount = 0;

        for (const tx of incoming) {
          if (tx.uuid && !existingUuids.has(tx.uuid)) {
            state.transactions.push({
              uuid: tx.uuid,
              debtorName: tx.debtorName,
              canonicalKey: tx.canonicalKey || normalizeSulawesiName(tx.debtorName),
              amount: Number(tx.amount) || 0,
              type: tx.type,
              note: tx.note || '',
              createdAt: tx.createdAt,
              timestamp: Number(tx.timestamp) || Date.now()
            });
            existingUuids.add(tx.uuid);
            newCount++;
          }
        }

        recalculateDebtors();
        saveToDisk();

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: true,
          mergedCount: newCount,
          totalTransactions: state.transactions.length,
          allTransactions: state.transactions,
          debtors: state.debtors
        }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: err.message }));
      }
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Endpoint not found' }));
});

if (require.main === module) {
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[Buku Hutang Darfin] Sync Server running on port ${PORT}`);
  });
}

module.exports = { server, state, recalculateDebtors };
