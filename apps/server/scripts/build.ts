import { build } from 'esbuild';
import { BUILD_OPTIONS } from './build-options.ts';

await build(BUILD_OPTIONS);
