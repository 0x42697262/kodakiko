import { EN } from '../../text/en';
import type { BannerKind } from '../../ui/Banner';
import type { SyncStatus } from '../../services/sync/sync-service';

export type SyncBannerId = 'offline' | 'dead' | 'waitingForWifi';

export type SyncBannerModel = {
  readonly id: SyncBannerId;
  readonly kind: BannerKind;
  readonly message: string;
  readonly retry: boolean;
};

export function syncBannerModel(status: SyncStatus): SyncBannerModel | null {
  if (status.offline) {
    return { id: 'offline', kind: 'info', message: EN['sync.offline'], retry: false };
  }
  if (status.dead > 0) {
    return { id: 'dead', kind: 'warning', message: EN['sync.dead'](status.dead), retry: true };
  }
  if (status.waitingForWifi > 0 && status.queued === 0) {
    return {
      id: 'waitingForWifi',
      kind: 'info',
      message: EN['sync.waitingForWifi'](status.waitingForWifi),
      retry: false,
    };
  }
  return null;
}
