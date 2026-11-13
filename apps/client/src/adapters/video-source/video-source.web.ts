import type { ObjectRow } from '../rowstore/rowstore';
import { WEB_BLOB_PLAYBACK_MAX_BYTES, MEDIA_STREAM_PATH } from '../../constants/video-constants';
import { OBJECT_CALLS } from '../../services/downloads/object-calls';
import { createDecryptingVideoSource, VideoMissingError } from '../../services/video/decrypting-video-source';
import type { PreparedVideo, VideoPrepareInput, VideoSource } from '../../services/video/video-source';
import { createMediaWorker } from './media-worker.web';
import { fetchOriginalWith, StreamingUnavailableError, type VideoSourceDeps } from './video-source-shared';

type Streaming = {
  readonly key: Uint8Array;
  readonly row: ObjectRow;
  readonly mimeType: string;
};

export function createVideoSource(deps: VideoSourceDeps): VideoSource {
  const streams = new Map<string, Streaming>();

  async function freshUrl(objectId: string): Promise<string> {
    return (await deps.http.request(OBJECT_CALLS.download(objectId))).url;
  }

  async function register(playbackId: string, stream: Streaming): Promise<void> {
    const key = stream.key.slice().buffer;
    worker.post(
      {
        type: 'register',
        playbackId,
        key,
        objectId: stream.row.id,
        downloadUrl: await freshUrl(stream.row.id),
        extraHeaders: {},
        plaintextSize: stream.row.plaintextSize,
        ciphertextSize: stream.row.sizeBytes,
        mimeType: stream.mimeType,
      },
      [key],
    );
  }

  const worker = createMediaWorker(
    {
      onNeedKey: (playbackId) => {
        const stream = streams.get(playbackId);
        if (stream !== undefined) register(playbackId, stream).catch(() => undefined);
      },
      onNeedUrl: (playbackId) => {
        const stream = streams.get(playbackId);
        if (stream === undefined) return;
        freshUrl(stream.row.id)
          .then((downloadUrl) => worker.post({ type: 'refreshUrl', playbackId, downloadUrl }))
          .catch(() => undefined);
      },
    },
    deps.randomId,
  );

  const blobFiles = createDecryptingVideoSource({
    rowStore: deps.rowStore,
    fileStore: deps.fileStore,
    entryKeys: deps.entryKeys,
    sodium: deps.sodium,
    readMetadata: deps.readMetadata,
    fetchOriginal: fetchOriginalWith(deps.downloader),
    createFile: async (_extension, mimeType) => {
      const parts: Uint8Array<ArrayBuffer>[] = [];
      let uri = '';
      return {
        get uri() {
          return uri;
        },
        write: async (bytes) => {
          parts.push(bytes.slice());
        },
        close: async () => {
          if (uri === '') uri = URL.createObjectURL(new Blob(parts, { type: mimeType }));
        },
        delete: async () => {
          parts.length = 0;
          if (uri !== '') URL.revokeObjectURL(uri);
        },
      };
    },
    freeSpaceBytes: async () => Number.MAX_SAFE_INTEGER,
    marginBytes: 0,
  });

  async function stream(input: VideoPrepareInput): Promise<PreparedVideo> {
    const rows = await deps.rowStore.transaction((tx) => tx.objects.byMedia(input.mediaId));
    const row = rows.find((candidate) => candidate.variant === 'original');
    const descriptor = (await deps.readMetadata(input.entryId)).media.find((item) => item.mediaId === input.mediaId);
    if (row === undefined || descriptor === undefined || row.uploadState !== 'uploaded') throw new VideoMissingError();
    const playbackId = deps.randomId();
    const entry: Streaming = {
      key: await deps.entryKeys.variantKey(input.entryId, input.mediaId, 'original'),
      row,
      mimeType: descriptor.mimeType,
    };
    streams.set(playbackId, entry);
    await register(playbackId, entry);
    input.onProgress(1);
    let released = false;
    return {
      uri: `${MEDIA_STREAM_PATH}${playbackId}`,
      release: async () => {
        if (released) return;
        released = true;
        streams.delete(playbackId);
        entry.key.fill(0);
        worker.post({ type: 'forget', playbackId });
      },
    };
  }

  return {
    prepare: async (input) => {
      if (await worker.isAvailable()) return stream(input);
      const rows = await deps.rowStore.transaction((tx) => tx.objects.byMedia(input.mediaId));
      const row = rows.find((candidate) => candidate.variant === 'original');
      if (row !== undefined && row.plaintextSize > WEB_BLOB_PLAYBACK_MAX_BYTES) throw new StreamingUnavailableError();
      return blobFiles.prepare(input);
    },
    releaseAll: async () => {
      for (const entry of streams.values()) entry.key.fill(0);
      streams.clear();
      worker.post({ type: 'forgetAll' });
      await blobFiles.releaseAll();
    },
  };
}
