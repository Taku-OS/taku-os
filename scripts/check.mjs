// SPDX-License-Identifier: Apache-2.0
import {readFile} from 'node:fs/promises';
for (const file of ['package.json','spec/protocol.schema.json','spec/audio.schema.json','spec/capability.schema.json','spec/proactivity-v1.schema.json','spec/taku-island-v1.schema.json','spec/extensions.json','core/methods.json']) JSON.parse(await readFile(new URL('../'+file,import.meta.url),'utf8'));
console.log('JSON protocols, registry and package metadata parse successfully. Full inventory validation belongs to inventory validator.');
