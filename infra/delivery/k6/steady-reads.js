// Stage 14: steady read traffic through the ledger's Service while a rolling update replaces its
// pods. Every response is counted by class; a status of 0 is a request that got no HTTP answer at
// all (refused or reset connection), which is what a pod killed under a request produces.
//
//   k6 run -e BASE_URL=http://sl-ledger:8080 -e TOKEN=... -e RATE=50 -e DURATION=4m steady-reads.js
//
// The token is passed in the environment of the k6 process, never in this file.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const ok = new Counter('status_2xx');
const clientError = new Counter('status_4xx');
const serverError = new Counter('status_5xx');
const noAnswer = new Counter('status_none');

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '4m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

const params = { headers: { Authorization: `Bearer ${__ENV.TOKEN}` }, timeout: '10s' };

export default function () {
  const res = http.get(`${__ENV.BASE_URL}/loans?size=20`, params);
  if (res.status === 0) {
    noAnswer.add(1);
    console.warn(`no answer at ${new Date().toISOString()}: ${res.error}`);
  } else if (res.status >= 500) {
    serverError.add(1);
    console.warn(`${res.status} at ${new Date().toISOString()}`);
  } else if (res.status >= 400) {
    clientError.add(1);
  } else {
    ok.add(1);
  }
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const d = data.metrics.http_req_duration.values;
  const line =
    `requests ${count('http_reqs')}: 2xx ${count('status_2xx')}, 4xx ${count('status_4xx')}, ` +
    `5xx ${count('status_5xx')}, no answer ${count('status_none')}; ` +
    `latency med ${d.med.toFixed(1)} ms, p95 ${d['p(95)'].toFixed(1)} ms, ` +
    `p99 ${d['p(99)'].toFixed(1)} ms, max ${d.max.toFixed(1)} ms\n`;
  return { stdout: line };
}
