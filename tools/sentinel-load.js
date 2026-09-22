import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const applicationSuccess = new Counter('application_success');
const applicationFallback = new Counter('application_fallback');

export const options = {
  scenarios: {
    sentinel_pressure: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 100,
      stages: [
        { target: 10, duration: '10s' },
        { target: 100, duration: '20s' },
        { target: 10, duration: '15s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const url = __ENV.SKY_LOAD_URL || 'http://localhost:8080/admin/workspace/businessData';
const token = __ENV.SKY_ADMIN_TOKEN;
const tokenHeader = __ENV.SKY_ADMIN_TOKEN_HEADER || 'token';

export default function () {
  const headers = {};
  if (token) headers[tokenHeader] = token;

  const response = http.get(url, { headers, tags: { operation: 'workspace-business-data' } });
  const successful = response.status >= 200 && response.status < 300 && /"code"\s*:\s*1/.test(response.body);
  const protectedResponse = /"code"\s*:\s*0/.test(response.body) || response.status === 401 || response.status === 429;

  if (successful) applicationSuccess.add(1);
  if (protectedResponse) applicationFallback.add(1);
  check(response, {
    'no 5xx response': (r) => r.status < 500,
    'success or controlled protection response': () => successful || protectedResponse,
  });
  sleep(0.05);
}
