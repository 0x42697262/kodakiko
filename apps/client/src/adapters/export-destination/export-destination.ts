import type { ExportDestination } from '../../services/export/export-service';

export type DestinationChoice =
  | { readonly kind: 'chosen'; readonly destination: ExportDestination; readonly downloads: boolean }
  | { readonly kind: 'cancelled' };

export declare function pickExportDestination(): Promise<DestinationChoice>;
