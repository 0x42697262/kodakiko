export type TagSuggestionSource = {
  readonly suggest: (prefix: string) => readonly string[];
};

export const EMPTY_TAG_SUGGESTIONS: TagSuggestionSource = Object.freeze({
  suggest: () => [],
});
