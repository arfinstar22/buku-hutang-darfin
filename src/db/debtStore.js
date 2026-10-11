import { parseDebtMessage, normalizeContactKey } from '../parser/smartParser.js';

/**
 * In-memory & JSON/SQLite compatible debt ledger store
 */
export class DebtStore {
  constructor() {
    this.contacts = new Map(); // canonicalKey -> { id, name, canonicalKey, totalDebt, transactions: [] }
    this.nextId = 1;
  }

  processMessage(text) {
    const parsed = parseDebtMessage(text);
    if (!parsed) {
      return { success: false, error: 'Format pesan tidak dikenali' };
    }

    const { name, canonicalKey, amount, type, note, raw } = parsed;
    
    let contact = this.contacts.get(canonicalKey);
    if (!contact) {
      contact = {
        id: this.nextId++,
        name,
        canonicalKey,
        totalDebt: 0,
        transactions: [],
      };
      this.contacts.set(canonicalKey, contact);
    }

    let previousDebt = contact.totalDebt;
    let delta = 0;

    if (type === 'DEBT') {
      delta = amount;
      contact.totalDebt += amount;
    } else if (type === 'PAYMENT') {
      delta = -amount;
      contact.totalDebt = Math.max(0, contact.totalDebt - amount);
    } else if (type === 'SETTLED') {
      delta = -contact.totalDebt;
      contact.totalDebt = 0;
    }

    const tx = {
      id: Date.now(),
      type,
      amount,
      note: note || '',
      date: new Date().toISOString(),
      balanceAfter: contact.totalDebt,
    };
    contact.transactions.push(tx);

    return {
      success: true,
      parsed,
      contact: {
        name: contact.name,
        totalDebt: contact.totalDebt,
      },
      previousDebt,
      delta,
      newTotal: contact.totalDebt,
    };
  }

  getSummary() {
    const debtors = Array.from(this.contacts.values())
      .filter(c => c.totalDebt > 0)
      .sort((a, b) => b.totalDebt - a.totalDebt);

    const grandTotal = debtors.reduce((sum, c) => sum + c.totalDebt, 0);

    return {
      debtors,
      count: debtors.length,
      grandTotal,
    };
  }

  getContact(nameOrKey) {
    const key = normalizeContactKey(nameOrKey);
    return this.contacts.get(key) || null;
  }
}
