import type { CropRect } from '../../features/entries/crop-math';
import type { MediaInput } from '../media/media-toolkit';

export declare function cropImage(uri: string, rect: CropRect, fileName: string | null): Promise<MediaInput>;
