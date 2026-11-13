import { fitWithin } from '@kodakiko/core';
import { File } from 'expo-file-system';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { MIME_EXTENSIONS, VIEW_FALLBACK_EXTENSION } from '../../keys/mime-extensions';
import { LINK_PHOTO_MAX_EDGE, LINK_PHOTO_QUALITY, type PhotoReencoder } from '../../services/links/link-stripper';
import type { TempFiles } from '../temp-files/temp-files';

export function createPhotoReencoder(deps: { readonly tempFiles: TempFiles }): PhotoReencoder {
  return async (bytes, mimeType) => {
    const input = await deps.tempFiles.create('view', MIME_EXTENSIONS[mimeType] ?? VIEW_FALLBACK_EXTENSION);
    let outputUri: string | null = null;
    try {
      await input.write(bytes);
      await input.close();
      const probe = await ImageManipulator.manipulate(input.uri).renderAsync();
      const context = ImageManipulator.manipulate(input.uri);
      context.resize(fitWithin(probe.width, probe.height, LINK_PHOTO_MAX_EDGE));
      const rendered = await context.renderAsync();
      const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, compress: LINK_PHOTO_QUALITY });
      outputUri = saved.uri;
      return { bytes: await new File(saved.uri).bytes(), width: saved.width, height: saved.height };
    } finally {
      await input.delete();
      if (outputUri !== null) {
        const output = new File(outputUri);
        if (output.exists) output.delete();
      }
    }
  };
}
