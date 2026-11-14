import type { MediaInput } from '../media/media-toolkit';

const PASTE_EVENT = 'paste';

export const clipboardButtonSupported = false;

export async function hasClipboardImage(): Promise<boolean> {
  return false;
}

export async function readClipboardImage(): Promise<MediaInput | null> {
  return null;
}

export function subscribePastedFiles(onInputs: (inputs: readonly MediaInput[]) => void): () => void {
  const listener = (event: ClipboardEvent) => {
    const files = [...(event.clipboardData?.files ?? [])];
    if (files.length === 0) return;
    event.preventDefault();
    onInputs(files.map((file) => ({ origin: 'paste', handle: { blob: file }, fileName: file.name === '' ? null : file.name, pickerExif: null })));
  };
  document.addEventListener(PASTE_EVENT, listener);
  return () => document.removeEventListener(PASTE_EVENT, listener);
}
