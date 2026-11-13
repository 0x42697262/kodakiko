import type { VideoSource } from '../../services/video/video-source';
import type { VideoSourceDeps } from './video-source-shared';

export { StreamingUnavailableError, fetchOriginalWith, type VideoSourceDeps } from './video-source-shared';

export declare function createVideoSource(deps: VideoSourceDeps): VideoSource;
