// SPDX-License-Identifier: Apache-2.0
// Read-only API health probe; never prints app contents or calls a write method.
import http from 'node:http';
const [port, app, method, expectedPid] = process.argv.slice(2);
if (!/^\d+$/.test(port) || !/^[A-Za-z0-9_-]+$/.test(app) || !/^[A-Za-z0-9_.-]{1,64}$/.test(method || '')) throw Error('Invalid probe configuration');
async function request(path, body) {
  return await new Promise((resolve, reject) => {
    const req = http.request({host: '127.0.0.1', port: Number(port), path,
      method: body ? 'POST' : 'GET', headers: body ? {'Content-Type': 'application/json'} : {}}, res => {
      let raw = '';
      res.setEncoding('utf8');
      res.on('data', data => {raw += data; if (raw.length > 4 * 1024 * 1024) req.destroy(Error('Probe response too large'));});
      res.on('end', () => {
        try {
          const value = JSON.parse(raw);
          if (res.statusCode !== 200 || value.ok !== true) throw Error('API reported unhealthy');
          resolve(value);
        } catch (error) { reject(error); }
      });
    });
    req.setTimeout(8000, () => req.destroy(Error('API probe timeout')));
    req.on('error', reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
}
const health = await request('/health');
if (health.pid !== Number(expectedPid)) throw Error('Health PID does not match deployed service');
const response = await request('/v1/apps/' + encodeURIComponent(app) + '/call', {method, params: {}});
if (response.app !== app || response.method !== method || !response.result || typeof response.result !== 'object') throw Error('State read failed');
console.log('API_HEALTHY');
