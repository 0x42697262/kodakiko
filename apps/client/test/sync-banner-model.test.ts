import { describe, expect, it } from 'vitest';
import { syncBannerModel } from '../src/features/sync/sync-banner-model';
import type { SyncStatus } from '../src/services/sync/sync-service';

function status(overrides: Partial<SyncStatus> = {}): SyncStatus {
  return { running: false, queued: 0, dead: 0, waitingForWifi: 0, lastSuccessAt: null, offline: false, ...overrides };
}

describe('syncBannerModel', () => {
  it('is hidden when everything is fine', () => {
    expect(syncBannerModel(status())).toBeNull();
    expect(syncBannerModel(status({ queued: 3, running: true }))).toBeNull();
  });

  it('shows the offline info first, ahead of dead items', () => {
    expect(syncBannerModel(status({ offline: true, dead: 2 }))).toEqual({
      id: 'offline',
      kind: 'info',
      message: "Offline. Changes will sync when you're connected.",
      retry: false,
    });
  });

  it('shows a warning with a Retry action for dead items, singular and plural', () => {
    expect(syncBannerModel(status({ dead: 1 }))).toEqual({
      id: 'dead',
      kind: 'warning',
      message: "Couldn't sync 1 item.",
      retry: true,
    });
    expect(syncBannerModel(status({ dead: 4 }))?.message).toBe("Couldn't sync 4 items.");
  });

  it('shows the Wi-Fi info only when nothing else is queued', () => {
    expect(syncBannerModel(status({ waitingForWifi: 1 }))).toEqual({
      id: 'waitingForWifi',
      kind: 'info',
      message: 'Waiting for Wi-Fi to upload 1 large item.',
      retry: false,
    });
    expect(syncBannerModel(status({ waitingForWifi: 3 }))?.message).toBe('Waiting for Wi-Fi to upload 3 large items.');
    expect(syncBannerModel(status({ waitingForWifi: 3, queued: 2 }))).toBeNull();
  });

  it('prefers dead items over the Wi-Fi hint', () => {
    expect(syncBannerModel(status({ dead: 1, waitingForWifi: 2 }))?.id).toBe('dead');
  });
});
