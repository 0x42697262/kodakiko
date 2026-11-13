import { describe, expect, it } from 'vitest';
import { nativeMapUrl, webMapUrl } from '../src/keys/map-keys';

describe('map url templates', () => {
  it('builds the Android geo uri with the coordinates as query', () => {
    expect(nativeMapUrl(48.8584, 2.2945)).toBe('geo:48.8584,2.2945?q=48.8584,2.2945');
  });

  it('builds the OpenStreetMap url with marker and zoom 16', () => {
    expect(webMapUrl(48.8584, 2.2945)).toBe(
      'https://www.openstreetmap.org/?mlat=48.8584&mlon=2.2945#map=16/48.8584/2.2945',
    );
  });

  it('keeps negative coordinates', () => {
    expect(nativeMapUrl(-33.8688, -151.2093)).toBe('geo:-33.8688,-151.2093?q=-33.8688,-151.2093');
    expect(webMapUrl(-33.8688, -151.2093)).toContain('mlat=-33.8688&mlon=-151.2093');
  });
});
