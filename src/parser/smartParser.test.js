import test from 'node:test';
import assert from 'node:assert/strict';
import { parseDebtMessage, normalizeContactKey, areNamesEquivalent } from './smartParser.js';

test('Smart Debt Parser - Currency & Amount variations', () => {
  const cases = [
    { input: 'budi 50rb', expectedAmount: 50000, expectedType: 'DEBT' },
    { input: 'budi 50000', expectedAmount: 50000, expectedType: 'DEBT' },
    { input: 'budi 50.000', expectedAmount: 50000, expectedType: 'DEBT' },
    { input: 'budi Rp.50000', expectedAmount: 50000, expectedType: 'DEBT' },
    { input: 'budi Rp 50.000,-', expectedAmount: 50000, expectedType: 'DEBT' },
    { input: 'budi 50k', expectedAmount: 50000, expectedType: 'DEBT' },
    { input: 'budi 1.5jt', expectedAmount: 1500000, expectedType: 'DEBT' },
    { input: 'budi 200 ribu', expectedAmount: 200000, expectedType: 'DEBT' },
    { input: 'budi 25rb rokok surya', expectedAmount: 25000, expectedNote: 'rokok surya', expectedType: 'DEBT' },
  ];

  for (const c of cases) {
    const res = parseDebtMessage(c.input);
    assert.ok(res, `Should parse: ${c.input}`);
    assert.equal(res.amount, c.expectedAmount, `Amount mismatch for ${c.input}`);
    assert.equal(res.type, c.expectedType, `Type mismatch for ${c.input}`);
    if (c.expectedNote) {
      assert.equal(res.note, c.expectedNote, `Note mismatch for ${c.input}`);
    }
  }
});

test('Smart Debt Parser - Payment and Settle actions', () => {
  const bayar1 = parseDebtMessage('budi bayar 20rb');
  assert.equal(bayar1.type, 'PAYMENT');
  assert.equal(bayar1.amount, 20000);
  assert.equal(bayar1.name, 'Budi');

  const cicil1 = parseDebtMessage('la tanto cicil 10k');
  assert.equal(cicil1.type, 'PAYMENT');
  assert.equal(cicil1.amount, 10000);

  const lunas1 = parseDebtMessage('budi lunas');
  assert.equal(lunas1.type, 'SETTLED');
  assert.equal(lunas1.amount, 0);
  assert.equal(lunas1.name, 'Budi');
});

test('Smart Debt Parser - Sulawesi naming rule: La tanto == latanto == tanto', () => {
  assert.equal(normalizeContactKey('La tanto'), 'tanto');
  assert.equal(normalizeContactKey('latanto'), 'tanto');
  assert.equal(normalizeContactKey('tanto'), 'tanto');
  assert.equal(normalizeContactKey('La Ode tanto'), 'tanto');
  assert.equal(normalizeContactKey('laode tanto'), 'tanto');

  assert.ok(areNamesEquivalent('La tanto', 'tanto'));
  assert.ok(areNamesEquivalent('latanto', 'tanto'));
  assert.ok(areNamesEquivalent('latanto', 'La tanto'));
  assert.ok(areNamesEquivalent('La Ode Tanto', 'tanto'));
});

test('Smart Debt Parser - Family distinctions: mama yesti != bapak yesti', () => {
  const mamaKey = normalizeContactKey('mama yesti');
  const bapakKey = normalizeContactKey('bapak yesti');

  assert.notEqual(mamaKey, bapakKey, 'Mama Yesti must be different from Bapak Yesti');
  assert.equal(mamaKey, 'mama_yesti');
  assert.equal(bapakKey, 'bapak_yesti');

  assert.ok(!areNamesEquivalent('mama yesti', 'bapak yesti'));
  assert.ok(areNamesEquivalent('mama yesti', 'ibu yesti'));
  assert.ok(areNamesEquivalent('bapak yesti', 'pak yesti'));
});

test('Smart Debt Parser - Real-world complex inputs', () => {
  // Parsing complex real messages
  const msg1 = parseDebtMessage('la tanto 25rb telur 1 rak');
  assert.equal(msg1.canonicalKey, 'tanto');
  assert.equal(msg1.amount, 25000);
  assert.equal(msg1.note, 'telur 1 rak');

  const msg2 = parseDebtMessage('latanto cicil 15.000');
  assert.equal(msg2.canonicalKey, 'tanto');
  assert.equal(msg2.type, 'PAYMENT');
  assert.equal(msg2.amount, 15000);

  const msg3 = parseDebtMessage('mama yesti 35k minyak goreng 2 liter');
  assert.equal(msg3.canonicalKey, 'mama_yesti');
  assert.equal(msg3.amount, 35000);
  assert.equal(msg3.note, 'minyak goreng 2 liter');

  const msg4 = parseDebtMessage('bapak yesti 50rb rokok surya');
  assert.equal(msg4.canonicalKey, 'bapak_yesti');
  assert.equal(msg4.amount, 50000);
  assert.equal(msg4.note, 'rokok surya');

  // Verify separation between Mama Yesti and Bapak Yesti
  assert.notEqual(msg3.canonicalKey, msg4.canonicalKey);
});
