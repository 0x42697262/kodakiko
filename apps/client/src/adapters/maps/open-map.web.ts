import { WEB_MAP_FEATURES, WEB_MAP_TARGET, webMapUrl } from '../../keys/map-keys';

export async function openMap(latitude: number, longitude: number): Promise<void> {
  window.open(webMapUrl(latitude, longitude), WEB_MAP_TARGET, WEB_MAP_FEATURES);
}
