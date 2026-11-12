import type { Migration } from 'kysely/migration';
import * as initial from './0001_initial.ts';

export const MIGRATIONS: Record<string, Migration> = {
  '0001_initial': initial,
};
