import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import type { ComponentProps } from 'react';

export type MaterialIconName = ComponentProps<typeof MaterialIcons>['name'];

export const ICONS = Object.freeze({
  back: 'arrow-back',
  overflow: 'more-vert',
  more: 'more-horiz',
  gallery: 'photo-library',
  calendar: 'calendar-month',
  shared: 'people',
  settings: 'settings',
  lock: 'lock',
  trash: 'delete',
  storage: 'storage',
  info: 'info',
  search: 'search',
  filter: 'filter-list',
  close: 'close',
  selectAll: 'select-all',
  selectInverse: 'flip-to-back',
  tags: 'label',
  share: 'share',
  pin: 'offline-pin',
  capture: 'add-a-photo',
  previous: 'chevron-left',
  next: 'chevron-right',
  today: 'today',
  add: 'add',
  edit: 'edit',
  save: 'check',
  restore: 'restore-from-trash',
  deleteForever: 'delete-forever',
  forward: 'arrow-forward',
  statistics: 'query-stats',
  appearance: 'palette',
  camera: 'photo-camera',
  sync: 'sync',
  security: 'security',
  account: 'account-circle',
  link: 'link',
  admin: 'admin-panel-settings',
  play: 'play-arrow',
  upload: 'cloud-upload',
  note: 'notes',
  video: 'videocam',
  triOff: 'check-box-outline-blank',
  triInclude: 'check-box',
  triExclude: 'disabled-by-default',
  ascending: 'arrow-upward',
  descending: 'arrow-downward',
  random: 'casino',
  signOut: 'logout',
  flip: 'flip-camera-android',
} satisfies Record<string, MaterialIconName>);

export type IconKey = keyof typeof ICONS;

export function Icon({ name, size, color }: { readonly name: IconKey; readonly size: number; readonly color: string }) {
  return <MaterialIcons name={ICONS[name]} size={size} color={color} />;
}

export function PaperIcon({ name, size, color }: { readonly name: string; readonly size: number; readonly color?: string }) {
  return <MaterialIcons name={name as MaterialIconName} size={size} color={color} />;
}
