export const ENTRY_TEXT = Object.freeze({
  mediaLimit: (max: number) => `An entry can have at most ${max} media items (maxMediaPerEntry).`,
  invalidCursor: 'The cursor is not valid.',
  duplicateMedia: 'The same media id is listed more than once.',
});

export const ENTRY_ISSUE_PATHS = Object.freeze({
  media: ['media'],
  cursor: ['cursor'],
  duplicateMedia: (index: number) => ['media', String(index), 'mediaId'],
});
