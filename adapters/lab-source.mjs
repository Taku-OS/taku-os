// SPDX-License-Identifier: Apache-2.0
// Source lookup only. Active runtime source is now owned by this repository.
import {access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const repositoryRoot=fileURLToPath(new URL('../',import.meta.url));
export async function locateLabModules(root = repositoryRoot) {
 const modules = ['system/assistant/src','system/display/src','tooling/app-kit'];
 const sources = [];
 for (const module of modules) {const path=resolve(root,module);await access(path);sources.push({module,path});}
 return sources;
}
