export const USER_ROLES = ['admin', 'member'] as const;
export type UserRole = (typeof USER_ROLES)[number];

export const USER_STATUSES = ['active', 'pending_deletion'] as const;
export type UserStatus = (typeof USER_STATUSES)[number];

export const COLLECTION_KINDS = ['home', 'shared', 'link'] as const;
export type CollectionKind = (typeof COLLECTION_KINDS)[number];

export const ENTRY_PURPOSES = ['journal', 'link_copy'] as const;
export type EntryPurpose = (typeof ENTRY_PURPOSES)[number];

export const OBJECT_VARIANTS = ['original', 'thumbnail'] as const;
export type ObjectVariant = (typeof OBJECT_VARIANTS)[number];

export const OBJECT_STATES = ['pending', 'uploaded', 'deleting'] as const;
export type ObjectState = (typeof OBJECT_STATES)[number];
