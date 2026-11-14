# Tags

Tags group entries instead of folders. Like Mihon's categories, they are created, renamed,
reordered and deleted in one place (the tag editor) and picked from that list everywhere else.
Code: `packages/core/src/domain/tags.ts`, `apps/client/src/features/tags/`,
`apps/client/src/services/entries/entries-service.ts` (`renameTag`, `deleteTag`, bulk tag edits).

## Rules

- A tag is normalized: NFKC, trimmed, runs of spaces collapsed to one, lower case. Empty or longer
  than 64 characters is refused.
- An entry has at most 50 tags, stored sorted; the same tag twice is impossible.
- Tags live inside each entry's encrypted metadata, so they sync with the entry and the server
  never sees them.

## The tag list

The list is every tag on your own entries plus empty tags added in the editor, in the editor's
order (the order and the empty tags are a device setting, `tagOrder`). Tags on entries shared with
you show on those entries but are never added to your list.

## The tag editor (Settings › Tags, also More › Tags)

- One row per tag with its entry count ("beach, 2 entries"), move up and move down, **Rename** and
  **Delete**.
- **Add** creates an empty tag that stays in the list until used.
- Rename and delete apply to every own entry that has the tag, through the normal edit path, so the
  change syncs; a renamed tag keeps its place in the order.
- The order drives the gallery's tag tabs.

## Picking tags

The edit page, the gallery filter and the bulk actions never accept typed tags:

- **Choose tags** opens a checkbox dialog of the tag list (no more than 50 can be checked on an
  entry) with **Edit tags**, which opens the editor and comes back. "No tags yet. Create them in
  Settings › Tags." when the list is empty.
- Chosen tags show as chips with a remove ✕.
- Bulk Add Tag… / Remove Tag… pick one tag from the list; entries that already have it (or do not)
  are counted as skipped.

## Where tags appear

Gallery tag tabs (All, Favorites, then the list), the filter (entries that have all the checked
tags), grouping by tag (an entry appears under each of its tags, untagged ones last), viewer chips
(a tap filters the gallery to that tag), statistics (top tags) and exports (`tags:` in `entry.md`).
Sync merges tags as sets: a tag added on one device and another removed on a second both survive.
