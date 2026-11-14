import type { MediaInput } from '../media/media-toolkit';
import type { TempFiles } from '../temp-files/temp-files';

export declare const clipboardButtonSupported: boolean;
export declare function hasClipboardImage(): Promise<boolean>;
export declare function readClipboardImage(tempFiles: TempFiles): Promise<MediaInput | null>;
export declare function subscribePastedFiles(onInputs: (inputs: readonly MediaInput[]) => void): () => void;
