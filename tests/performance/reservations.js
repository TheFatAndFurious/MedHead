import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const mode = __ENV.MODE || 'success';
const smoke = __ENV.SMOKE === '1';
if (!['success', 'mixed'].includes(mode)) throw new Error('MODE must be success or mixed');
const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:58080';
const accessToken = __ENV.MEDHEAD_ACCESS_TOKEN;
if (!accessToken) throw new Error('MEDHEAD_ACCESS_TOKEN is required');
const created = new Counter('reservations_created');
const refused = new Counter('reservations_refused');
const successCallback = http.expectedStatuses(201);
const unavailableCallback = http.expectedStatuses(404);
const phases = smoke
  ? [{ name: 'smoke', start: 0, duration: 10, rate: 10 }]
  : [
      { name: 'warmup', start: 0, duration: 120, rate: 80, ramp: true },
      { name: 'nominal', start: 120, duration: 300, rate: 80 },
      { name: 'peak', start: 420, duration: 60, rate: 800 },
      { name: 'recovery', start: 480, duration: 300, rate: 80 },
    ];
const scenarios = {};
const thresholds = {
  checks: ['rate==1'],
  http_req_failed: ['rate==0'],
  dropped_iterations: ['count==0'],
};
for (const phase of phases) {
  const flows = mode === 'mixed' ? ['available', 'unavailable'] : ['available'];
  for (const flow of flows) {
    const rate = phase.rate / flows.length;
    const name = `${phase.name}_${flow}`;
    scenarios[name] = {
      executor: phase.ramp ? 'ramping-arrival-rate' : 'constant-arrival-rate',
      exec: flow === 'available' ? 'reserve' : 'noBed',
      startTime: `${phase.start}s`,
      timeUnit: '1s',
      preAllocatedVUs: smoke ? 10 : Math.max(20, Math.ceil(rate * 0.5)),
      maxVUs: smoke ? 20 : Math.max(40, rate * 2),
      gracefulStop: '5s',
      tags: { phase: phase.name, flow },
      ...(phase.ramp
        ? { startRate: 0, stages: [{ target: rate, duration: `${phase.duration}s` }] }
        : { rate, duration: `${phase.duration}s` }),
    };
    if (!phase.ramp) {
      thresholds[`http_req_duration{scenario:${name}}`] = ['p(95)<200'];
      const expectedStatus = flow === 'available' ? 201 : 404;
      thresholds[`http_req_duration{scenario:${name},status:${expectedStatus}}`] = ['p(95)<200'];
      // One HTTP request per iteration; verify the phase was actually exercised.
      thresholds[`http_reqs{scenario:${name}}`] = [`count>=${rate * phase.duration}`];
    }
  }
  if (!phase.ramp) thresholds[`http_req_duration{phase:${phase.name}}`] = ['p(95)<200'];
}

export const options = {
  scenarios, thresholds,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  summaryTimeUnit: 'ms',
};

function request(available) {
  // Visit the same deterministic coordinate set in each phase, across all VUs.
  const n = (exec.scenario.iterationInTest % 1600) + 1;
  const specialtyCode = available ? 'PERF_AVAILABLE' : 'PERF_UNAVAILABLE';
  const response = http.post(`${baseUrl}/api/v1/hospitals/reservations`, JSON.stringify({
    longitude: -5.8 + ((n * 37) % 850) / 100,
    latitude: 50.1 + ((n * 53) % 880) / 100,
    specialtyCode,
  }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
      // Every iteration is a new reservation request; k6 never retries it.
      'Idempotency-Key': crypto.randomUUID(),
    },
    timeout: '2s',
    redirects: 0,
    responseCallback: available ? successCallback : unavailableCallback,
    tags: { name: 'POST reservations' },
  });
  check(response, { 'expected business status': (r) => r.status === (available ? 201 : 404) });
  if (response.status === 201) {
    created.add(1);
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    check(body, {
      'valid reservation': (b) => b !== null
        && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(b.reservationId)
        && b.specialtyCode === specialtyCode
        && Number.isInteger(b.hospitalId) && b.hospitalId > 0
        && b.availableBeds >= 0 && b.availableBeds < b.totalBeds
        && Number.isFinite(Date.parse(b.reservedAt)),
    });
  } else if (response.status === 404) {
    refused.add(1);
  }
}

export function reserve() { request(true); }
export function noBed() { request(false); }
