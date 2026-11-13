import type { PhotoReencoder } from '../../services/links/link-stripper';
import type { TempFiles } from '../temp-files/temp-files';

export declare function createPhotoReencoder(deps: { readonly tempFiles: TempFiles }): PhotoReencoder;
