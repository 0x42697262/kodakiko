import { Linking } from 'react-native';
import { nativeMapUrl } from '../../keys/map-keys';

export async function openMap(latitude: number, longitude: number): Promise<void> {
  await Linking.openURL(nativeMapUrl(latitude, longitude));
}
