import { createKdkxWriter, deriveKdkxKey, KDKX_EXTENSION, KDKX_SALT_BYTES, type Sodium } from '@kodakiko/core';
import type { ExportDestination } from './export-service';

export async function encryptedDestination(
  sodium: Sodium,
  inner: ExportDestination,
  passphrase: string,
  cost: { readonly opsLimit: number; readonly memLimitBytes: number },
): Promise<ExportDestination> {
  const params = { salt: sodium.randomBytes(KDKX_SALT_BYTES), ...cost };
  const key = await deriveKdkxKey(sodium, passphrase, params);
  return {
    partMaxBytes: inner.partMaxBytes,
    createPart: async (name) => {
      const sink = await inner.createPart(`${name}${KDKX_EXTENSION}`);
      const writer = createKdkxWriter(sodium, key, params);
      await sink.write(writer.header);
      return {
        write: async (bytes) => {
          for (const chunk of writer.push(bytes)) await sink.write(chunk);
        },
        close: async () => {
          await sink.write(writer.finish());
          await sink.close();
        },
        abort: () => sink.abort(),
      };
    },
  };
}
