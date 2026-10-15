// Yük testi: hesaplar arası transfer (EFT) akışı ve test sonunda para doğrulaması.
//
//   k6 run -e SCENARIO=steady -e RATE=50 -e DURATION=2m transfers.js
//
// SCENARIO: smoke | steady | ramp | spike | hot | idempotency   (README'ye bak)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  ACCOUNT, TRANSFER, ADMIN_PASSWORD, TELLER_PASSWORD, RUN_ID,
  headers, login, createUsers, ledgerSummary, sagaStats, inFlight,
} from './lib.js';

const SCENARIO = __ENV.SCENARIO || 'steady';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '2m';
const USERS = Number(__ENV.USERS || 100);
const INITIAL_BALANCE = __ENV.INITIAL_BALANCE || '1000000.0000';
const MAX_AMOUNT = Number(__ENV.MAX_AMOUNT || 10);
const E2E_TIMEOUT_S = Number(__ENV.E2E_TIMEOUT_S || 30);
const DRAIN_TIMEOUT_S = Number(__ENV.DRAIN_TIMEOUT_S || 180);
const CHAOS = __ENV.CHAOS === '1'; // kaos testi: servisler kasten öldürülecek, gecikme/hata eşikleri anlamsız
const RECON_GRACE_WAIT_S = Number(__ENV.RECON_GRACE_WAIT_S || 35); // transfer-service reconciliation.grace-ms (30 sn) + pay

// ---- özel metrikler
const sagaE2e = new Trend('saga_e2e_ms', true);                 // POST başlangıcından terminal duruma kadar
const transfersCompleted = new Counter('transfers_completed');
const transfersUnexpected = new Counter('transfers_unexpected_result'); // beklenmedik FAILED/COMPENSATED
const sagaTimeouts = new Counter('saga_timeouts');                // E2E_TIMEOUT_S içinde bitmeyen
const acceptRejected = new Counter('transfers_not_accepted');     // POST 202 dönmedi
const idempotencyViolations = new Counter('idempotency_violations');
const invariantViolations = new Counter('invariant_violations');  // Σ bakiye != 0, clearing != 0, muhasebe ihlali
const reconFindings = new Counter('reconciliation_findings');
const drainMs = new Trend('drain_wait_ms', true);                 // yük bitince bekleyen saga'ların boşalma süresi

// ---- senaryolar
const scenarios = {
  smoke: { executor: 'per-vu-iterations', vus: 1, iterations: 5, maxDuration: '2m', exec: 'steady' },
  steady: {
    executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 50, maxVUs: 1000, exec: 'steady',
  },
  ramp: {
    executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 2000, exec: 'steady',
    stages: [
      { target: Math.max(1, Math.round(RATE / 4)), duration: '1m' },
      { target: Math.max(1, Math.round(RATE / 2)), duration: '1m' },
      { target: RATE, duration: '1m' },
      { target: RATE * 2, duration: '1m' },
      { target: RATE * 4, duration: '1m' },
    ],
  },
  spike: {
    executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 3000, exec: 'steady',
    stages: [
      { target: 10, duration: '1m' }, { target: RATE * 5, duration: '10s' },
      { target: RATE * 5, duration: '30s' }, { target: 10, duration: '10s' }, { target: 10, duration: '1m' },
    ],
  },
  hot: { // hepsi AYNI kaynak hesaptan: satır kilidi (hot row) çekişmesi
    executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 50, maxVUs: 1000, exec: 'hotAccount',
  },
  idempotency: { executor: 'per-vu-iterations', vus: 10, iterations: 20, maxDuration: '5m', exec: 'idempotency' },
};

if (!scenarios[SCENARIO]) {
  throw new Error(`Bilinmeyen SCENARIO=${SCENARIO}; seçenekler: ${Object.keys(scenarios).join(', ')}`);
}

// Eşikler. Rampa/spike'ta gecikme eşikleri bilgilendiricidir (sistemi sınırına kadar zorlamak amaçtır).
const stress = SCENARIO === 'ramp' || SCENARIO === 'spike';
const thresholds = {
  'invariant_violations': ['count==0'],
  'reconciliation_findings': ['count==0'],
  'idempotency_violations': ['count==0'],
  // Kaosta (ve stres testlerinde) kesinti sırasında bitmeyen saga olması doğaldır; asıl ölçüt test sonundaki doğrulamalardır.
  'saga_timeouts': stress || CHAOS ? [] : ['count==0'],
  'transfers_unexpected_result': ['count==0'],
};
if (!stress && !CHAOS && SCENARIO !== 'idempotency') {
  thresholds['http_req_duration{name:transfer_create}'] = ['p(95)<300'];
  thresholds['http_req_failed{name:transfer_create}'] = ['rate<0.01'];
  thresholds['saga_e2e_ms'] = ['p(95)<3000'];
  thresholds['dropped_iterations'] = ['count==0']; // sistem verilen hızı kaldıramadıysa iterasyon düşer
}

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds,
  setupTimeout: '10m',
  teardownTimeout: '15m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ---- kurulum
export function setup() {
  const admin = login('admin', ADMIN_PASSWORD);
  const teller = login('teller', TELLER_PASSWORD);
  const before = ledgerSummary(admin);
  console.log(`Kurulum: ${USERS} kullanıcı oluşturuluyor (başlangıç bakiyesi ${INITIAL_BALANCE} TRY)...`);
  const users = createUsers(USERS, teller, INITIAL_BALANCE);
  console.log(`Kurulum tamam. Senaryo=${SCENARIO} hız=${RATE}/sn süre=${DURATION}; başlangıç Σ bakiye=${before && before.totalBalance}`);
  return { users, admin };
}

// ---- iş akışı
function amount() {
  return (1 + Math.floor(Math.random() * MAX_AMOUNT)).toFixed(4);
}

function pickOther(n, not) {
  let j = Math.floor(Math.random() * n);
  if (j === not) j = (j + 1) % n;
  return j;
}

const TERMINAL = { COMPLETED: true, FAILED: true, COMPENSATED: true };
const POLL_DELAYS = [0.1, 0.1, 0.2, 0.2, 0.3, 0.3, 0.5];

function runTransfer(data, fromIdx, toIdx) {
  const from = data.users[fromIdx];
  const to = data.users[toIdx];
  const key = `k6-${RUN_ID}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 10)}`;
  const started = Date.now();

  const res = http.post(`${TRANSFER}/api/transfers`, JSON.stringify({
    fromAccountId: from.accountId, toAccountId: to.accountId, amount: amount(), currency: 'TRY', type: 'EFT',
  }), { headers: headers(from.token, { 'Idempotency-Key': key }), tags: { name: 'transfer_create' } });

  if (!check(res, { 'transfer kabul edildi (202)': (r) => r.status === 202 })) {
    acceptRejected.add(1);
    return;
  }
  const id = res.json('id');

  // Sonuçlanana kadar sorgula (saga uçtan uca süresi).
  let state = res.json('sagaState');
  const deadline = started + E2E_TIMEOUT_S * 1000;
  let i = 0;
  while (!TERMINAL[state] && Date.now() < deadline) {
    sleep(POLL_DELAYS[Math.min(i++, POLL_DELAYS.length - 1)]);
    const r = http.get(`${TRANSFER}/api/transfers/${id}`,
      { headers: headers(from.token), tags: { name: 'transfer_poll' } });
    if (r.status === 200) state = r.json('sagaState');
  }
  if (!TERMINAL[state]) {
    sagaTimeouts.add(1);
    return;
  }
  sagaE2e.add(Date.now() - started);
  if (state === 'COMPLETED') {
    transfersCompleted.add(1);
  } else {
    transfersUnexpected.add(1); // bakiye yeterli ve hesaplar açık: başarısızlık beklenmez
  }
}

export function steady(data) {
  const from = Math.floor(Math.random() * data.users.length);
  runTransfer(data, from, pickOther(data.users.length, from));
}

export function hotAccount(data) {
  runTransfer(data, 0, pickOther(data.users.length, 0));
}

/** Aynı Idempotency-Key ile eşzamanlı 5 istek: hepsi AYNI transferi dönmeli, tek saga çalışmalı. */
export function idempotency(data) {
  const from = Math.floor(Math.random() * data.users.length);
  const to = pickOther(data.users.length, from);
  const key = `k6-idem-${RUN_ID}-${__VU}-${__ITER}`;
  const body = JSON.stringify({
    fromAccountId: data.users[from].accountId, toAccountId: data.users[to].accountId,
    amount: '1.0000', currency: 'TRY', type: 'EFT',
  });
  const params = { headers: headers(data.users[from].token, { 'Idempotency-Key': key }), tags: { name: 'transfer_create' } };
  const responses = http.batch([...Array(5)].map(() => ['POST', `${TRANSFER}/api/transfers`, body, params]));

  const ids = {};
  responses.forEach((r) => {
    if (r.status !== 200 && r.status !== 201 && r.status !== 202) {
      idempotencyViolations.add(1); // beklenen: ilk istek 202, tekrarlar 200 (replay)
    } else {
      ids[r.json('id')] = true;
    }
  });
  if (Object.keys(ids).length !== 1) {
    idempotencyViolations.add(1); // birden çok farklı transfer oluşmuş
  }
}

// ---- yük bittikten sonra doğrulama: para kaybolmadı mı?
export function teardown(data) {
  // 1) Bekleyen saga'lar boşalsın.
  const drainStart = Date.now();
  let stats = sagaStats(data.admin);
  while (inFlight(stats) !== 0 && Date.now() - drainStart < DRAIN_TIMEOUT_S * 1000) {
    sleep(2);
    stats = sagaStats(data.admin);
  }
  drainMs.add(Date.now() - drainStart);
  if (inFlight(stats) !== 0) {
    invariantViolations.add(1);
    console.error(`DOĞRULAMA: ${DRAIN_TIMEOUT_S} sn sonra hâlâ bitmemiş saga var: ${JSON.stringify(stats)}`);
  }

  // 2) Σ bakiye = 0 ve clearing = 0.
  const summary = ledgerSummary(data.admin);
  if (!summary || Number(summary.totalBalance) !== 0) {
    invariantViolations.add(1);
    console.error(`DOĞRULAMA: Σ bakiye 0 değil: ${summary && summary.totalBalance}`);
  }
  if (!summary || Number(summary.clearingBalance) !== 0) {
    invariantViolations.add(1);
    console.error(`DOĞRULAMA: clearing 0 değil (yolda para var): ${summary && summary.clearingBalance}`);
  }

  // 3) Muhasebe kontrolü (account-service) ve servisler arası mutabakat (transfer-service).
  const ledger = http.get(`${ACCOUNT}/api/admin/reconciliation`,
    { headers: headers(data.admin), tags: { name: 'verify_ledger_reconciliation' } });
  if (ledger.status !== 200 || ledger.json('violations').length !== 0) {
    invariantViolations.add(1);
    console.error(`DOĞRULAMA: muhasebe ihlali: ${ledger.body}`);
  }

  sleep(RECON_GRACE_WAIT_S); // mutabakat, yeni bitmiş transferleri grace süresi dolana kadar atlar
  let checked = 0;
  for (let round = 0; round < 100; round++) {
    const run = http.post(`${TRANSFER}/api/admin/reconciliation/run`, '{}',
      { headers: headers(data.admin), tags: { name: 'verify_reconciliation_run' }, timeout: '120s' });
    if (run.status !== 200 || run.json('checked') === 0) break;
    checked += run.json('checked');
  }
  const recon = http.get(`${TRANSFER}/api/admin/reconciliation`,
    { headers: headers(data.admin), tags: { name: 'verify_reconciliation_summary' } });
  const open = recon.status === 200 ? recon.json('openFindings') : -1;
  if (open !== 0) {
    reconFindings.add(Math.max(open, 1));
    console.error(`DOĞRULAMA: ${open} açık mutabakat bulgusu: ${recon.body}`);
  }
  console.log(`DOĞRULAMA TAMAM: ${checked} transfer mutabakattan geçti, açık bulgu=${open}, Σ bakiye=${summary && summary.totalBalance}, `
    + `clearing=${summary && summary.clearingBalance}, saga durumları=${JSON.stringify(stats)}`);
}
