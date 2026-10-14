import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

// One fresh, authenticated user per iteration. Never benchmark repeat rejections.
const users = new SharedArray('test-users', () => JSON.parse(open(__ENV.USERS_FILE)));
const base = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const mode = __ENV.MODE || 'async';
const pollResults = __ENV.POLL_RESULTS !== 'false';
const runId = __ENV.RUN_ID;
if (!runId || !__ENV.ACTIVITY_ID || !__ENV.SETMEAL_ID || !['sync', 'async'].includes(mode)) {
  throw new Error('Required: RUN_ID, ACTIVITY_ID, SETMEAL_ID, USERS_FILE; MODE=sync|async');
}
const accepted = new Counter('accepted_orders');
const completed = new Counter('completed_orders');
const success = new Rate('acceptance_success');
const terminalSuccess = new Rate('completion_success');
const latency = new Trend('accepted_latency_ms', true);
const endToEnd = new Trend('order_ready_latency_ms', true);
const rejected = new Counter('rejected_orders');
const capacityRejected = new Counter('capacity_rejected_orders');
const unknown = new Counter('unknown_orders');
const rate = Number(__ENV.RATE || 800);
const duration = Number(__ENV.SECONDS || 60);
const offset = Number(__ENV.USER_OFFSET || 0);
if (!/^[A-Za-z0-9_-]{16,50}$/.test(runId)) throw new Error('RUN_ID must have 16-50 safe characters');
if (users.length < offset + rate * duration) throw new Error('Insufficient unique users for planned arrivals');
export const options = {
  scenarios: { orders: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
    duration: `${duration}s`, preAllocatedVUs: Number(__ENV.VUS || 200),
    maxVUs: Number(__ENV.MAX_VUS || 1200), gracefulStop: '150s' } },
  thresholds: Object.assign({ acceptance_success: ['rate>=0.99'], dropped_iterations: ['count==0'] },
    pollResults ? { completion_success: ['rate>=0.99'] } : {}),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};
function body(response) { try { return response.json(); } catch (_) { return null; } }
export default function () {
  const user = users[offset + exec.scenario.iterationInTest];
  if (!user) throw new Error('Unique test users exhausted');
  const requestId = `${runId}_${exec.scenario.iterationInTest}`;
  const headers = { 'Content-Type': 'application/json' };
  headers[__ENV.TOKEN_HEADER || 'authentication'] = user.token;
  const start = Date.now();
  const response = http.post(`${base}/user/order/seckill/submit`, JSON.stringify({
    activityId: __ENV.ACTIVITY_ID, setmealId: __ENV.SETMEAL_ID,
    canteenId: __ENV.CANTEEN_ID, addressBookId: user.addressBookId,
    requestId, payMethod: 1, deliveryStatus: 1, tablewareNumber: 0,
    tablewareStatus: 1, packAmount: 1, useRedPacket: false,
    remark: `BENCHMARK:${runId}`,
  }), { headers, timeout: '10s', tags: { operation: 'submit' } });
  let result = body(response);
  const ok = response.status === 200 && result && result.code === 1 && result.data &&
    (mode === 'async' ? result.data.submitStatus === 'ACCEPTED' || result.data.submitStatus === 'SUCCEEDED'
      : result.data.id != null);
  success.add(!!ok);
  if (!ok) {
    if (response.status === 0) unknown.add(1); // A timed-out HTTP call may still create an order.
    else {
      rejected.add(1);
      if (result && result.msg === '秒杀建单繁忙，请稍后重试') capacityRejected.add(1);
    }
    return;
  }
  accepted.add(1); latency.add(response.timings.duration);
  if (mode === 'sync') { completed.add(1); terminalSuccess.add(true); endToEnd.add(Date.now()-start); return; }
  if (!pollResults) return; // Observe actual creation independently through the isolated DB.
  const deadline = start + 140000;
  while (Date.now() < deadline) {
    if (result.data.submitStatus === 'SUCCEEDED') {
      const ready = result.data.id != null && result.data.orderAmount != null;
      terminalSuccess.add(ready); if (ready) { completed.add(1); endToEnd.add(Date.now()-start); }
      return;
    }
    if (['FAILED', 'CANCELLED'].includes(result.data.submitStatus)) { terminalSuccess.add(false); return; }
    sleep(1);
    const poll = http.get(`${base}/user/order/seckill/result?requestId=${encodeURIComponent(requestId)}&activityId=${__ENV.ACTIVITY_ID}`,
      { headers, timeout: '10s', tags: { operation: 'poll' } });
    const next = body(poll); if (next && next.code === 1 && next.data) result = next;
  }
  unknown.add(1); terminalSuccess.add(false);
}
export function handleSummary(data) {
  // k6 counter rate includes drain time. Admission QPS uses the planned arrival window.
  const count = name => data.metrics[name] ? data.metrics[name].values.count : 0;
  const report = { mode, pollResults, runId, plannedRate: rate, arrivalSeconds: duration,
    accepted: count('accepted_orders'), completed: count('completed_orders'),
    acceptedPerArrivalSecond: count('accepted_orders') / duration,
    note: 'completed counter rate includes drain; sustainable capacity requires backlog and DB checks',
    raw: data };
  return { [__ENV.REPORT_FILE || `seckill-${mode}-${runId}.json`]: JSON.stringify(report, null, 2) };
}
