import { AndroidHaptics, performAndroidHapticsAsync } from 'expo-haptics';
import { Platform } from 'react-native';

export type HapticKind = 'select' | 'toggle' | 'shutter' | 'recordStart' | 'recordStop' | 'refresh';

const ANDROID_TYPES: Readonly<Record<HapticKind, AndroidHaptics>> = Object.freeze({
  select: AndroidHaptics.Long_Press,
  toggle: AndroidHaptics.Toggle_On,
  shutter: AndroidHaptics.Virtual_Key,
  recordStart: AndroidHaptics.Gesture_Start,
  recordStop: AndroidHaptics.Gesture_End,
  refresh: AndroidHaptics.Confirm,
});

export function createHaptics(platform: string, perform: (type: AndroidHaptics) => Promise<void>) {
  let enabled = true;
  return {
    setEnabled: (next: boolean): void => {
      enabled = next;
    },
    tick: (kind: HapticKind): void => {
      if (!enabled || platform !== 'android') return;
      perform(ANDROID_TYPES[kind]).catch(() => undefined);
    },
  };
}

export const haptics = createHaptics(Platform.OS, performAndroidHapticsAsync);
