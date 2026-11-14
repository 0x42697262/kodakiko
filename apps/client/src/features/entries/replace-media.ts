type Media = { readonly mediaId: string; readonly caption?: string | undefined };

export type ReplaceDeps<Input, Imported extends { readonly mediaId: string }> = {
  readonly entries: {
    readonly readMetadata: (entryId: string) => Promise<{ readonly media: readonly Media[] }>;
    readonly addMedia: (entryId: string, items: readonly Imported[]) => Promise<void>;
    readonly removeMedia: (entryId: string, mediaId: string) => Promise<void>;
    readonly edit: (entryId: string, edit: { readonly mediaOrder?: readonly string[]; readonly captions?: Readonly<Record<string, string>> }) => Promise<void>;
  };
  readonly importOne: (input: Input, slots: { readonly used: number; readonly limit: number }) => Promise<{ readonly ok: true; readonly media: Imported } | { readonly ok: false }>;
  readonly limit: number;
};

export async function replaceMedia<Input, Imported extends { readonly mediaId: string }>(
  deps: ReplaceDeps<Input, Imported>,
  entryId: string,
  oldMediaId: string,
  input: Input,
): Promise<'replaced' | 'refused'> {
  const before = await deps.entries.readMetadata(entryId);
  const position = before.media.findIndex((item) => item.mediaId === oldMediaId);
  const old = before.media[position];
  if (old === undefined) return 'refused';
  const outcome = await deps.importOne(input, { used: before.media.length - 1, limit: deps.limit });
  if (!outcome.ok) return 'refused';
  const newId = outcome.media.mediaId;
  await deps.entries.addMedia(entryId, [outcome.media]);
  await deps.entries.removeMedia(entryId, oldMediaId);
  const order = before.media.map((item) => (item.mediaId === oldMediaId ? newId : item.mediaId));
  await deps.entries.edit(entryId, { mediaOrder: order, ...(old.caption === undefined ? {} : { captions: { [newId]: old.caption } }) });
  return 'replaced';
}
