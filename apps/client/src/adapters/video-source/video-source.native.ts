import { Paths } from 'expo-file-system';
import { VIDEO_FREE_SPACE_MARGIN_BYTES } from '../../constants/video-constants';
import { createDecryptingVideoSource } from '../../services/video/decrypting-video-source';
import type { VideoSource } from '../../services/video/video-source';
import { fetchOriginalWith, type VideoSourceDeps } from './video-source-shared';

export function createVideoSource(deps: VideoSourceDeps): VideoSource {
  return createDecryptingVideoSource({
    rowStore: deps.rowStore,
    fileStore: deps.fileStore,
    entryKeys: deps.entryKeys,
    sodium: deps.sodium,
    readMetadata: deps.readMetadata,
    fetchOriginal: fetchOriginalWith(deps.downloader),
    createFile: (extension) => deps.tempFiles.create('play', extension),
    freeSpaceBytes: async () => Paths.availableDiskSpace,
    marginBytes: VIDEO_FREE_SPACE_MARGIN_BYTES,
    releaseFiles: () => deps.tempFiles.wipePurpose('play'),
  });
}
