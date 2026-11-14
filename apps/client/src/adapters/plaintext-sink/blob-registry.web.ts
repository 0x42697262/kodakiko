const blobs = new Map<string, Blob>();

export function registerBlob(uri: string, blob: Blob): void {
  blobs.set(uri, blob);
}

export function forgetBlob(uri: string): void {
  blobs.delete(uri);
}

export async function blobForUri(uri: string): Promise<Blob> {
  const known = blobs.get(uri);
  if (known !== undefined) return known;
  return (await fetch(uri)).blob();
}
