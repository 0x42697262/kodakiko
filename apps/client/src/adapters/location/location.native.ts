import * as Location from 'expo-location';
import type { LocationProvider } from './location';

function withTimeout<Value>(promise: Promise<Value>, timeoutMs: number): Promise<Value | null> {
  return Promise.race([
    promise,
    new Promise<null>((resolve) => setTimeout(() => resolve(null), timeoutMs)),
  ]);
}

export function createLocationProvider(): LocationProvider {
  return {
    currentPosition: async (timeoutMs) => {
      try {
        const permission = await Location.requestForegroundPermissionsAsync();
        if (!permission.granted) return null;
        const position = await withTimeout(
          Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
          timeoutMs,
        );
        if (position === null) return null;
        return {
          point: { latitude: position.coords.latitude, longitude: position.coords.longitude },
          accuracyMeters: position.coords.accuracy,
        };
      } catch {
        return null;
      }
    },
  };
}
