import type { MediaInput } from '../media/media-toolkit';

export type RecordedVoice = { readonly input: MediaInput; readonly durationMs: number; readonly mimeType: string };
export type VoiceRecorder = {
  readonly supported: boolean;
  readonly recording: boolean;
  readonly elapsedMs: number;
  readonly start: () => Promise<void>;
  readonly stop: () => Promise<RecordedVoice | null>;
};

export declare function useVoiceRecorder(): VoiceRecorder;
