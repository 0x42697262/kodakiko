import type { ReactElement } from 'react';
import type { MediaInput } from '../media/media-toolkit';

export type CaptureSurfaceProps = {
  readonly onInputs: (inputs: readonly MediaInput[]) => void;
  readonly remainingSlots: number;
  readonly maxUploadBytes: number;
};

export declare function CaptureSurface(props: CaptureSurfaceProps): ReactElement;
