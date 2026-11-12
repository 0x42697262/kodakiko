import { fileURLToPath } from 'node:url';
import { cleanAndroid } from './clean-android-lib.ts';

const CLIENT_ROOT = fileURLToPath(new URL('..', import.meta.url));

const { bytesFreed, removed } = await cleanAndroid(CLIENT_ROOT);
console.log(`Removed ${removed.length} directories, freed ${bytesFreed} bytes.`);
