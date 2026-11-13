export const SHARING_TEXT = Object.freeze({
  sharedLimit: (max: number) => `You can own at most ${max} shared journals (maxSharedCollectionsPerUser).`,
  recipientLimit: (max: number) => `A journal can be shared with at most ${max} people (maxRecipientsPerCollection).`,
  keyLength: (bytes: number) => `The key must be exactly ${bytes} bytes.`,
  notYourself: 'You cannot share a journal with yourself.',
  recipientInactive: 'That user cannot receive shares.',
});

export const SHARING_ISSUE_PATHS = Object.freeze({
  id: ['id'],
  keyByOwner: ['keyByOwner'],
  entryKeyByCollection: ['entryKeyByCollection'],
  recipientUserId: ['recipientUserId'],
  sealedCollectionKey: ['sealedCollectionKey'],
});
