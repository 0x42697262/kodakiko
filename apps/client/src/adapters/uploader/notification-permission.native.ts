import { requestNotificationPermission as requestNative } from '@kodakiko/native-upload';
import type { NotificationPermissionRequest } from './notification-permission';

export const requestNotificationPermission: NotificationPermissionRequest = requestNative;
