import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import path from 'node:path';

// Run against a disposable dsh web profile: this creates and archives a Session.
const [origin, workspace, gatewayDirectory, mode] = process.argv.slice(2);
assert.ok(origin && workspace && gatewayDirectory,
  'Usage: node scripts/check-harness-mobile.mjs <http-origin> <workspace> <gateway-directory>');
const base = new URL(origin);
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname), 'Use an isolated loopback Host');
const require = createRequire(path.resolve(gatewayDirectory, 'package.json'));
const WebSocket = require('ws');
const wsUrl = new URL('/ws/mobile', base);
wsUrl.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
let protocols = ['dsh-mobile-v1'];
const headers = { 'X-DSH-Device-ID': randomUUID() };
if (mode === '--account') {
  assert.equal(base.protocol, 'https:');
  assert.ok(process.env.DSH_MOBILE_USERNAME && process.env.DSH_MOBILE_PASSWORD, 'Set disposable test account credentials');
  const challengeResponse = await fetch(new URL('/gateway/mobile/v1/auth/challenge', base), { redirect: 'error' });
  assert.equal(challengeResponse.status, 200);
  const { challenge } = await challengeResponse.json();
  const cookie = challengeResponse.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
  const login = await fetch(new URL('/gateway/mobile/v1/auth/login', base), {
    method: 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json', Cookie: cookie, 'X-Dsh-Csrf': challenge },
    body: JSON.stringify({ username: process.env.DSH_MOBILE_USERNAME, password: process.env.DSH_MOBILE_PASSWORD }),
  });
  assert.equal(login.status, 200, 'Account login');
  const account = await login.json();
  wsUrl.pathname = account.mobileGateway.path;
  headers.Authorization = `Bearer ${account.accessToken}`;
} else {
const response = await fetch(new URL('/mgw/pair', base), {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Origin: base.origin },
  body: JSON.stringify({ name: 'Compatibility smoke', publicUrl: wsUrl.href }),
  signal: AbortSignal.timeout(10_000),
});
assert.equal(response.status, 201, 'The isolated Gateway must be enabled with device authentication');
const { payload } = await response.json();
protocols.push(`dsh-pair.${payload.pairingCode}`);
}
const ws = new WebSocket(wsUrl, protocols, { headers });
const pending = new Map();
const responseKind = type => ({ 'session-rename': 'session-renamed', 'session-archive': 'session-archived', subscribe: 'subscribed' }[type] ?? type);
const failPending = error => {
  for (const entry of pending.values()) entry.reject(error);
  pending.clear();
};
ws.on('error', failPending);
ws.on('close', () => failPending(new Error('Gateway disconnected')));
ws.on('message', data => {
  const frame = JSON.parse(data.toString());
  if (frame.kind === 'error' && !frame.requestType && !frame.requestId) {
    failPending(new Error(`Gateway stream: ${frame.message}`));
    return;
  }
  const key = frame.requestId ?? (frame.kind === 'error' ? responseKind(frame.requestType) : frame.kind);
  pending.get(key)?.resolve(frame);
});
function wait(key) {
  return new Promise((resolve, reject) => {
    const finish = callback => value => {
      clearTimeout(timer);
      pending.delete(key);
      callback(value);
    };
    const timer = setTimeout(() => pending.get(key)?.reject(new Error(`Timeout: ${key}`)), 10_000);
    pending.set(key, { resolve: finish(resolve), reject: finish(reject) });
  });
}
async function request(type, fields = {}) {
  const requestId = randomUUID();
  const key = ['session-create', 'session-agent-preset'].includes(type) ? requestId
    : responseKind(type);
  const result = wait(key);
  ws.send(JSON.stringify({ type, requestId, ...fields }));
  const frame = await result;
  assert.notEqual(frame.kind, 'error', `${type}: ${frame.code}: ${frame.message}`);
  console.log(`PASS ${type}`);
  return frame;
}
try {
  const hello = await wait('hello');
  assert.equal(hello.authenticated, true);
  assert.equal(hello.historyFormatVersion, 4);
  for (const type of ['host', 'workspaces', 'sessions', 'agent-presets', 'providers', 'permission-options']) {
    await request(type);
  }
  if (hello.capabilities.includes('schedule-management')) await request('schedule-catalog');
  else console.log('PASS Host declares scheduling unavailable');
  const { sessionId } = await request('session-create', { cwd: path.resolve(workspace) });
  assert.ok(sessionId);
  const history = await request('history', { sessionId });
  assert.equal(history.historyFormatVersion, 4);
  await request('session-agent-preset', { sessionId });
  await request('commands', { sessionId });
  const opening = wait('session-snapshot');
  await request('subscribe', { sessionId, assistantStream: true });
  const snapshot = await opening;
  assert.equal(snapshot.sessionId, sessionId);
  assert.equal(snapshot.historyFormatVersion, 4);
  await request('session-rename', { sessionId, title: 'Mobile compatibility smoke' });
  await request('session-archive', { sessionId });
} finally {
  if (ws.readyState !== WebSocket.CLOSED) {
    const closed = once(ws, 'close');
    ws.terminate();
    await closed;
  }
}
