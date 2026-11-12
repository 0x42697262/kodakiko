import { afterEach, describe, expect, it, vi } from 'vitest';
import { deviceLabel } from '../src/adapters/device-label/device-label.web';
import { deviceLabel as nativeDeviceLabel } from '../src/adapters/device-label/device-label.native';

function withUserAgent(userAgent: string): string {
  vi.stubGlobal('navigator', { userAgent });
  return deviceLabel();
}

describe('device label', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('names the browser by the first matching token, Edge before Chrome before Safari', () => {
    expect(withUserAgent('Mozilla/5.0 Chrome/120 Safari/537 Edg/120')).toBe('Edge');
    expect(withUserAgent('Mozilla/5.0 Firefox/121')).toBe('Firefox');
    expect(withUserAgent('Mozilla/5.0 Chrome/120 Safari/537')).toBe('Chrome');
    expect(withUserAgent('Mozilla/5.0 Version/17 Safari/605')).toBe('Safari');
  });

  it('falls back to Browser and names Android natively', () => {
    expect(withUserAgent('curl/8')).toBe('Browser');
    expect(nativeDeviceLabel()).toBe('Android');
  });
});
