export type DeviceStorage = { readonly free: number; readonly total: number };

export declare function readDeviceStorage(): Promise<DeviceStorage | null>;
