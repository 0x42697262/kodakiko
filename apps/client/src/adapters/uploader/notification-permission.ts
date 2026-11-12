export type NotificationPermissionRequest = (() => Promise<boolean>) | null;

export declare const requestNotificationPermission: NotificationPermissionRequest;
