export type ByteSource = {
  readonly size: number;
  readonly read: (offset: number, length: number) => Promise<Uint8Array>;
};

export function byteSourceFromBytes(bytes: Uint8Array): ByteSource {
  return {
    size: bytes.length,
    read: async (offset, length) => {
      const start = Math.max(0, offset);
      return bytes.slice(start, start + Math.max(0, length));
    },
  };
}
