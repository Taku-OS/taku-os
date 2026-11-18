// SPDX-License-Identifier: Apache-2.0
// Read-only smoke test after explicit deployment; never installs/restarts or picks a device.
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';
import assert from 'node:assert/strict';
const args=process.argv.slice(2), options={};
for(let i=0;i<args.length;i+=2){if(!['--serial','--adb','--output'].includes(args[i])||!args[i+1])throw Error('Use --serial ID --adb PATH --output FILE'); options[args[i]]=args[i+1];}
const serial=options['--serial']??process.env.TAKU_OS_SERIAL;
if(!serial||!/^[A-Za-z0-9._:-]+$/.test(serial))throw Error('Explicit serial required');
const adb=options['--adb']??process.env.ADB??'adb';
const execute=promisify(execFile), evidence={checkedAt:new Date().toISOString(),scope:'Android device shell CLI on explicitly selected development emulator; not ROM/physical-device acceptance',serial,calls:{}};
for(const method of ['system.info','displays.status','tasks.status']){
 const {stdout}=await execute(adb,['-s',serial,'shell','sh','/data/local/tmp/taku-os/taku-device.sh',method],{timeout:10000,maxBuffer:1024*1024});
 const response=JSON.parse(stdout.trim());assert.equal(response.ok,true);evidence.calls[method]=response;
}
assert.equal(evidence.calls['system.info'].result.callerUid,'0');
try{await execute(adb,['-s',serial,'shell','su','2000','sh','/data/local/tmp/taku-os/taku-device.sh','tasks.status'],{timeout:10000});throw Error('Expected permission refusal');}
catch(error){const denied=JSON.parse(error.stdout?.trim());assert.equal(denied.error.code,'PERMISSION_DENIED');assert.equal(error.code,4);evidence.permissionDenied=denied;evidence.permissionDeniedExit=error.code;}
const output=options['--output']??'out/device-cli-smoke.json';await mkdir(dirname(output),{recursive:true});await writeFile(output,JSON.stringify(evidence,null,2)+'\n');console.log('Device CLI methods and UID 2000 denial verified; evidence: '+output);
