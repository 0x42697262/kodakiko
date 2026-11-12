import type { Migration } from 'kysely/migration';
import * as initial from './0001_initial.ts';
import * as objectsPartSize from './0002_objects_part_size.ts';

export const MIGRATIONS: Record<string, Migration> = {
  '0001_initial': initial,
  '0002_objects_part_size': objectsPartSize,
};
