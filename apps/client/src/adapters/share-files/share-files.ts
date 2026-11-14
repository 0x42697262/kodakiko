export type SharedFile = { readonly uri: string; readonly mimeType: string; readonly fileName: string };
export type ShareResult = 'shared' | 'cancelled' | 'unsupported';

export declare function canShareFiles(): boolean;
export declare function shareFiles(files: readonly SharedFile[], text: string | null): Promise<ShareResult>;
export declare function saveToDevice(file: SharedFile): Promise<ShareResult>;
