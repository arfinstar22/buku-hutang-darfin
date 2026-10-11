/**
 * Smart Debt Parser - Indonesian & Sulawesi Localized Naming & Money Parser
 * Designed for "Buku Hutang Darfin"
 */

/**
 * Normalizes contact name into a canonical key.
 * Rules:
 * - Sulawesi male prefixes: "la tanto", "latanto", "la ode tanto", "tanto" -> "tanto"
 * - Family roles: "mama yesti", "ibu yesti" -> "mama_yesti"
 * - Family roles: "bapak yesti", "pak yesti" -> "bapak_yesti"
 * - Maintains separation between distinct gender/family entities (mama != bapak).
 */
export function normalizeContactKey(rawName) {
  if (!rawName) return '';
  let str = rawName.toLowerCase().trim();

  // 1. Detect female family title
  const femaleMatch = str.match(/^(mama|ibu|mak|mami|mbak|tante)\s+(.+)$/i);
  if (femaleMatch) {
    const core = femaleMatch[2].trim().replace(/\s+/g, '_');
    return `mama_${core}`;
  }

  // 2. Detect male family title
  const maleMatch = str.match(/^(bapak|pak|papi|om|mas|bang)\s+(.+)$/i);
  if (maleMatch) {
    const core = maleMatch[2].trim().replace(/\s+/g, '_');
    return `bapak_${core}`;
  }

  // 3. Handle Sulawesi Honorifics: "La", "La Ode", "Wa" (perempuan)
  // Check "La Ode" or "laode"
  if (/^la\s*ode\s+/i.test(str)) {
    str = str.replace(/^la\s*ode\s+/i, '');
  } else if (/^laode\s*/i.test(str)) {
    str = str.replace(/^laode\s*/i, '');
  } else if (/^la\s+/i.test(str)) {
    // "La tanto" with space
    str = str.replace(/^la\s+/i, '');
  } else if (/^la[a-z]{3,}/i.test(str)) {
    // "latanto" without space (word starting with "la" followed by at least 3 letters)
    str = str.replace(/^la/i, '');
  }

  return str.trim().replace(/\s+/g, '_');
}

/**
 * Compares two names to check if they point to the same person.
 */
export function areNamesEquivalent(nameA, nameB) {
  return normalizeContactKey(nameA) === normalizeContactKey(nameB);
}

/**
 * Parses monetary string into integer number (IDR).
 * Handles: 50rb, 50k, 50000, 50.000, Rp.50.000,-, 1.5jt, 200 ribu.
 */
export function parseRupiahAmount(amountStr) {
  if (!amountStr) return 0;
  let clean = amountStr.toLowerCase().replace(/rp\.?\s*/i, '').replace(/,-\s*$/, '').trim();

  let multiplier = 1;
  if (/(\d+(?:[.,]\d+)?)\s*(jt|juta)\b/i.test(clean)) {
    const val = clean.replace(/\s*(jt|juta).*/i, '').replace(',', '.');
    return Math.round(parseFloat(val) * 1000000);
  } else if (/(\d+(?:[.,]\d+)?)\s*(rb|ribu|k)\b/i.test(clean)) {
    const val = clean.replace(/\s*(rb|ribu|k).*/i, '').replace(',', '.');
    return Math.round(parseFloat(val) * 1000);
  }

  // Standard numeric with dots / commas
  // e.g. 50.000 or 50000
  const digitsOnly = clean.replace(/[^0-9]/g, '');
  return parseInt(digitsOnly, 10) || 0;
}

/**
 * Capitalizes display name nicely.
 */
function formatDisplayName(name) {
  return name
    .split(/\s+/)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ');
}

/**
 * Main parser: Parses natural language debt input.
 * e.g. "budi 50rb beras", "la tanto cicil 10k", "mama yesti lunas"
 */
export function parseDebtMessage(text) {
  if (!text || typeof text !== 'string') return null;
  const raw = text.trim();

  // Detect Action Type
  const isSettled = /\b(lunas|selesai|clear)\b/i.test(raw);
  const isPayment = /\b(bayar|cicil|angsur|kurang)\b/i.test(raw);

  let type = 'DEBT';
  if (isSettled) {
    type = 'SETTLED';
  } else if (isPayment) {
    type = 'PAYMENT';
  }

  // If settled with no amount (e.g. "budi lunas")
  if (isSettled && !/\d/.test(raw)) {
    const rawName = raw.replace(/\b(lunas|selesai|clear)\b/gi, '').trim();
    const namePart = rawName || 'Tanpa Nama';
    return {
      raw,
      type: 'SETTLED',
      name: formatDisplayName(namePart),
      canonicalKey: normalizeContactKey(namePart),
      amount: 0,
      note: 'Lunas',
    };
  }

  // Extract Amount Pattern
  // Matches: Rp. 50.000 / 50rb / 50k / 1.5jt / 50000 / 200 ribu
  const moneyRegex = /(?:rp\.?\s*)?(\d+(?:[.,]\d+)?\s*(?:jt|juta|rb|ribu|k)|(?:\d{1,3}(?:\.\d{3})+|\d{4,}))(?:\s*,-)?/i;
  const match = raw.match(moneyRegex);

  if (!match) {
    return null;
  }

  const rawAmountStr = match[0];
  const amount = parseRupiahAmount(rawAmountStr);
  const matchIndex = match.index;

  // Everything before amount is typically Name (+ optional action word like "bayar")
  let before = raw.slice(0, matchIndex).trim();
  // Everything after amount is optional note/keterangan
  let after = raw.slice(matchIndex + rawAmountStr.length).trim();

  // Strip action words from name or note
  const actionCleanRegex = /\b(bayar|cicil|angsur|lunas|utang|hutang)\b/gi;
  let namePart = before.replace(actionCleanRegex, '').trim();
  let notePart = after.replace(actionCleanRegex, '').trim();

  // If before was empty, maybe name is in after? e.g. "bayar 50rb budi"
  if (!namePart && after) {
    const parts = after.split(/\s+/);
    namePart = parts[0];
    notePart = parts.slice(1).join(' ').trim();
  }

  if (!namePart) {
    namePart = 'Tanpa Nama';
  }

  return {
    raw,
    type,
    name: formatDisplayName(namePart),
    canonicalKey: normalizeContactKey(namePart),
    amount,
    note: notePart || (type === 'PAYMENT' ? 'Pembayaran' : ''),
  };
}
