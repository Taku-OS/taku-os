// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import {TakuFacade} from '../core/facade.mjs';
import {AndroidShell} from '../adapters/android-shell.mjs';
import {TakuClient} from '../sdk/client.mjs';
const request = method => ({protocol:'taku/0.1',type:'request',id:'test',method,params:{}});
test('bad request and params rejected before any device access', async () => {
 const facade = new TakuFacade({call() { throw Error('Must not run'); }});
 assert.equal((await facade.handle({...request('system.info'),params:{shell:'rm'}})).error.code,'INVALID_PARAMS');
 assert.equal((await facade.handle({...request('system.info'),protocol:'old'})).error.code,'INVALID_REQUEST');
 assert.equal((await facade.handle({...request('system.info'),method:'system.info;id'})).error.code,'INVALID_REQUEST');
});
test('unknown and absent transport produce explicit errors',async()=>{
 const facade = new TakuFacade();
 assert.equal((await facade.handle(request('system.shell'))).error.code,'METHOD_NOT_FOUND');
 assert.equal((await facade.handle(request('system.info'))).error.code,'DEVICE_REQUIRED');
 assert.equal((await facade.handle(request('methods.list'))).ok,true);
});
test('explicit adb device and closed methods required', async()=>{
 assert.throws(()=>new AndroidShell({serial:''}),/Explicit/);
 assert.throws(()=>new AndroidShell({serial:'x;id'}),/Explicit/);
 await assert.rejects(()=>new AndroidShell({serial:'emulator-5582'}).call('system.shell'),/Unsupported probe/);
});
test('SDK preserves protocol error code without leaking implementation details',async()=>{
 const client = new TakuClient();
 await assert.rejects(()=>client.call('system.info'),error=>error.code==='DEVICE_REQUIRED');
 const response = await new TakuFacade({call(){throw Error('secret internal pathname');}}).handle(request('system.info'));
 assert.equal(response.error.code,'INTERNAL_ERROR');
 assert.equal(JSON.stringify(response).includes('secret'),false);
});
