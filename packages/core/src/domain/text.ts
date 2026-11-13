export const DOMAIN_TEXT = Object.freeze({
  mediaOrderNotPermutation: 'The media order must list every media item exactly once.',
  removeLastMedia: 'An entry must keep at least one photo or video.',
  invalidTag: 'A tag is empty or too long after normalizing.',
  unknownMedia: 'That media item is not part of this entry.',
} as const);
