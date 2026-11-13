import { Platform, Share } from 'react-native';

export async function copyText(text: string): Promise<boolean> {
  if (Platform.OS === 'web' && typeof navigator !== 'undefined' && navigator.clipboard !== undefined) {
    await navigator.clipboard.writeText(text);
    return true;
  }
  return false;
}

export async function shareText(text: string): Promise<void> {
  await Share.share({ message: text });
}
