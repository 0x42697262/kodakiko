export function nativeMapUrl(latitude: number, longitude: number): string {
  return `geo:${latitude},${longitude}?q=${latitude},${longitude}`;
}

export function webMapUrl(latitude: number, longitude: number): string {
  return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=16/${latitude}/${longitude}`;
}

export const WEB_MAP_TARGET = '_blank';
export const WEB_MAP_FEATURES = 'noopener,noreferrer';
