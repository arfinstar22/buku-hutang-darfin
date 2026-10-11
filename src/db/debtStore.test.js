import test from 'node:test';
import assert from 'node:assert/strict';
import { DebtStore } from './debtStore.js';

test('DebtStore - Accumulates debt with Sulawesi alias matching', () => {
  const store = new DebtStore();

  const r1 = store.processMessage('La tanto 50rb');
  assert.equal(r1.success, true);
  assert.equal(r1.newTotal, 50000);

  // Tanto adds 20k -> should accumulate on the SAME person
  const r2 = store.processMessage('tanto 20k telur');
  assert.equal(r2.success, true);
  assert.equal(r2.newTotal, 70000);

  // latanto pays 30k -> should deduct from the SAME person
  const r3 = store.processMessage('latanto cicil 30.000');
  assert.equal(r3.success, true);
  assert.equal(r3.newTotal, 40000);

  const contact = store.getContact('La Tanto');
  assert.equal(contact.totalDebt, 40000);
  assert.equal(contact.transactions.length, 3);
});

test('DebtStore - Keeps Mama Yesti and Bapak Yesti independent', () => {
  const store = new DebtStore();

  store.processMessage('mama yesti 35rb');
  store.processMessage('bapak yesti 50rb');

  const mama = store.getContact('mama yesti');
  const bapak = store.getContact('bapak yesti');

  assert.equal(mama.totalDebt, 35000);
  assert.equal(bapak.totalDebt, 50000);

  const summary = store.getSummary();
  assert.equal(summary.count, 2);
  assert.equal(summary.grandTotal, 85000);
});
