import type { ReactElement } from 'react';
import type { MediaInput } from '../media/media-toolkit';

export type CaptureSurfaceProps = {
  readonly onInputs: (inputs: readonly MediaInput[]) => void;
  readonly remainingSlots: number;
  readonly maxUploadBytes: number;
  readonly onRecordingChange?: (recording: boolean) => void;
  readonly initialMode?: 'photo' | 'video';
};

export declare function CaptureSurface(props: CaptureSurfaceProps): ReactElement;
