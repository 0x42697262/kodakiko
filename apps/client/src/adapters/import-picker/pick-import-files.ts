import type { ImportFile } from '../../services/import/journal-import';

export declare function pickImportFiles(): Promise<readonly ImportFile[] | null>;
