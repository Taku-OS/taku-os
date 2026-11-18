#!/usr/bin/env node
import {readFile} from 'node:fs/promises';
import {validateManifest} from './manifest-v1.mjs';

try {
  if (process.argv.length !== 3) throw new Error('usage: node sdk/plugins/validate.mjs MANIFEST.json');
  const manifest = JSON.parse(await readFile(process.argv[2], 'utf8'));
  const errors = validateManifest(manifest);
  console.log(JSON.stringify({ok: errors.length === 0, errors}));
  if (errors.length) process.exitCode = 1;
} catch (error) {
  console.log(JSON.stringify({ok: false, errors: [{path: '$', message: error.message}]}));
  process.exitCode = 1;
}
