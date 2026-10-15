// Ortak yardımcılar: adresler, kimlik doğrulama, test verisi kurulumu.
import http from 'k6/http';
import { check, fail } from 'k6';

export const ACCOUNT = __ENV.ACCOUNT_URL || 'http://localhost:8081';
export const TRANSFER = __ENV.TRANSFER_URL || 'http://localhost:8082';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Admin123!';
export const TELLER_PASSWORD = __ENV.TELLER_PASSWORD || 'Teller123!';
export const RUN_ID = __ENV.RUN_ID || String(Date.now());

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function headers(token, extra) {
  return Object.assign({}, JSON_HEADERS, token ? { Authorization: 'Bearer ' + token } : {}, extra || {});
}

export function login(username, password) {
  const res = http.post(`${ACCOUNT}/api/auth/login`, JSON.stringify({ username, password }),
    { headers: JSON_HEADERS, tags: { name: 'setup_login' } });
  if (res.status !== 200) {
    fail(`${username} girişi başarısız (${res.status}): servisler ayakta mı, şifre doğru mu? ${res.body}`);
  }
  return res.json('token');
}

function chunk(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) {
    out.push(list.slice(i, i + size));
  }
  return out;
}

/**
 * Test kullanıcılarını kurar: her biri kendi müşterisi, kendi hesabı ve başlangıç bakiyesi olan `count` kullanıcı.
 * Kayıt BCrypt kullandığı için yavaştır; istekler 10'arlı paralel gruplar halinde gönderilir.
 */
export function createUsers(count, tellerToken, initialBalance) {
  const users = [];
  for (const group of chunk([...Array(count).keys()], 10)) {
    const regs = http.batch(group.map((i) => ['POST', `${ACCOUNT}/api/auth/register`, JSON.stringify({
      fullName: `Load User ${i}`, email: `k6-${RUN_ID}-${i}@load.test`, password: 'Passw0rd!x',
    }), { headers: JSON_HEADERS, tags: { name: 'setup_register' } }]));
    regs.forEach((r) => {
      if (r.status !== 201) fail(`kayıt başarısız (${r.status}): ${r.body}`);
    });

    const accs = http.batch(regs.map((r) => ['POST', `${ACCOUNT}/api/accounts`,
      JSON.stringify({ currency: 'TRY', customerId: r.json('customerId') }),
      { headers: headers(tellerToken), tags: { name: 'setup_open_account' } }]));
    accs.forEach((r) => {
      if (r.status !== 201) fail(`hesap açma başarısız (${r.status}): ${r.body}`);
    });

    const deps = http.batch(accs.map((r, idx) => ['POST', `${TRANSFER}/api/transfers/deposit?wait=15000`,
      JSON.stringify({ accountId: r.json('id'), amount: initialBalance, currency: 'TRY', description: 'k6 seed' }),
      { headers: headers(tellerToken, { 'Idempotency-Key': `k6-seed-${RUN_ID}-${group[idx]}` }),
        tags: { name: 'setup_deposit' } }]));
    deps.forEach((r) => {
      if (r.status !== 201) fail(`para yatırma başarısız (${r.status}): ${r.body}`);
    });

    regs.forEach((r, idx) => users.push({ token: r.json('token'), accountId: accs[idx].json('id') }));
  }
  return users;
}

export function ledgerSummary(adminToken) {
  const res = http.get(`${ACCOUNT}/api/admin/ledger-summary`,
    { headers: headers(adminToken), tags: { name: 'verify_ledger_summary' } });
  check(res, { 'ledger-summary 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json() : null;
}

export function sagaStats(adminToken) {
  const res = http.get(`${TRANSFER}/api/admin/saga-stats`,
    { headers: headers(adminToken), tags: { name: 'verify_saga_stats' } });
  return res.status === 200 ? res.json() : null;
}

export function inFlight(stats) {
  return stats ? (stats.STARTED || 0) + (stats.DEBITED || 0) + (stats.COMPENSATING || 0) : -1;
}
