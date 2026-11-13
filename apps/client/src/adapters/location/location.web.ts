import type { LocationProvider } from './location';

export function createLocationProvider(): LocationProvider {
  return {
    currentPosition: (timeoutMs) =>
      new Promise((resolve) => {
        if (typeof navigator === 'undefined' || navigator.geolocation === undefined) {
          resolve(null);
          return;
        }
        navigator.geolocation.getCurrentPosition(
          (position) =>
            resolve({
              point: { latitude: position.coords.latitude, longitude: position.coords.longitude },
              accuracyMeters: position.coords.accuracy,
            }),
          () => resolve(null),
          { timeout: timeoutMs },
        );
      }),
  };
}
