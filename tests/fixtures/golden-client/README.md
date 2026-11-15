# Client golden fixtures for the Kotlin port

Deterministic outputs of the Expo client's TypeScript code (`apps/client`) that the Kotlin client
must reproduce: the markdown parser and toolbar, the calendar model, the gallery arrangement and
the export ZIP container. Everything except `manifest.json` (the judge's list of required files and
fields) and this README is generated:

```sh
pnpm --filter @kodakiko/client generate-golden-client
# = node --import ./scripts/golden-client/resolve-hooks.ts scripts/generate-golden-client.ts (in apps/client)
```

`apps/client/test/golden-client.test.ts` regenerates into a temp directory and fails if any byte
differs, if a file is missing or extra, or if a written archive does not unzip to its declared
files. Never edit the generated files by hand. `resolve-hooks.ts` only lets plain Node resolve the
client's extensionless relative imports; vitest does not need it.

Generator environment: Node 26.7.0 (ICU 78.3, Unicode 17.0), marked 18.1.3, fflate 0.8.3. The
output does not depend on the machine's time zone or locale (checked with `TZ` set to
`Pacific/Kiritimati`, `America/Los_Angeles` and `Asia/Kolkata`, each combined with `LANG` set to
`C`, `da_DK`, `de_DE`, `sv_SE`, `cs_CZ`, `ja_JP`, `tr_TR` and `lt_LT`): archives are built with
`TZ=UTC` forced inside the generator, the calendar texts use a fixed `en-US` formatter, and the
tag-collation inputs were chosen so that every tested locale sorts them the same way (see
`gallery/arrange.json`).

## Common shape

Same conventions as `tests/fixtures/golden/README.md` ("Function-case files"):

- Every JSON file is `{ "format": 1, "generator": "apps/client/scripts/generate-golden-client.ts",
  "cases": [...] }`, pretty-printed with 2 spaces and a trailing newline.
- A case is `{ "name", "fn", "args", "result" }`. `readArchive` cases add `producer` (see below).
- `name` is unique within its file: `<fn>: <TS test title>` (the `it(...)` text), with a
  parenthesized suffix when one TS test holds several assertions, or `<fn>: edge: <what>` for extra
  cases with no TS test of their own. The archive cases have no TS unit test, so their names only
  describe the scenario. Every file except `markdown/toolbar.json` (whose constants are private
  to the module and spelled out below) ends with one case named `constants` (`fn: "constants"`,
  `args: {}`), whose `result` lists the TS constants the file depends on, so the Kotlin
  `*Constants` objects can be checked against them.
- `args` is an object keyed by the TS parameter names; the call passes them in the order given
  for each `fn` below. Absent optional keys mean "argument not passed" (TS `undefined`).
- `result` is the return value encoded as below. No case in these files throws (there is no
  `{ "error", "message" }` result).
- No clock is read and no unseeded randomness is used.

### Encoding of non-JSON values

| TS value | JSON |
|---|---|
| `ReadonlySet<string>` argument | array sorted by UTF-16 code units (iteration order never matters for these functions) |
| `null` return | `null` |
| object property whose value is `undefined` | key omitted |
| function argument `get: (id) => IndexedEntry \| null` | `entries: IndexedEntry[]`; `get(id)` = the element with that `id`, else `null` (see gallery) |
| binary content | `{ "file", "size", "sha256" }`, `file` relative to `tests/fixtures/golden-client/` |
| lone UTF-16 surrogate inside a string | JSON escape `\udXXX` (JS `JSON.stringify` output); parse it into a single `Char` |

Numbers are JS doubles printed in shortest form (`3`, not `3.0`; `439.9`). Compare parsed JSON
values; key order is the TS object's key order but carries no meaning.

### JS behaviors shared by all files

- String lengths, indexes and `slice` positions count **UTF-16 code units** (Kotlin `String`
  indexes do too). An emoji such as `🌸` is 2 units; `e` + U+0301 is 2 units.
- `<`/`>` on strings and the sorted-set encoding use UTF-16 code-unit order (= Kotlin
  `String.compareTo`).
- JS `String.prototype.lastIndexOf(s, from)` clamps a negative `from` to 0 (it still checks index
  0); Kotlin's `lastIndexOf(s, -1)` returns -1. See the toolbar.

## `markdown/parse.json`

`parseMarkdown(source)` from `apps/client/src/features/markdown/parse.ts`; `args = { source }`,
`result` = the `Block[]` tree. 50 cases: 49 `parseMarkdown` + `constants`.

Coverage: every `it` of `apps/client/test/markdown-parse.test.ts` (11 tests, 13 cases: "turns HTML
into plain text with the raw source" is split into `(block)` and `(inline)`, "maps ordered,
unordered and nested task lists" into `(ordered, start 3)` and `(nested task list)`), plus 36
`edge:` cases: whitespace-only input, every heading level and non-headings, closing hashes and
setext headings, quotes nested exactly 6 and 40 levels, lists nested 6 and 7 levels, mixed
quote/list nesting, lazy continuation, images (alt with markdown, image inside a link, reference
image, empty alt), block/comment/inline HTML, a GFM table, unsafe and relative link targets,
autolinks and bare URLs, link destination processing, mailto links, reference links and their
definitions, task-list variants, ordered start numbers, loose lists, list-marker changes, hard
breaks, kept leading/trailing spaces, code spans, fenced/indented/unclosed code blocks, CRLF and
CR input, emphasis variants, backslash escapes, thematic breaks, blank lines, non-ASCII/NUL/BOM,
entities, footnote syntax, a 100000-character note (`NOTE_MAX_CHARS`) and a 200-line paragraph.

### Result JSON

```
Block  = { "type": "heading", "level": 1|2|3, "children": Inline[] }
       | { "type": "paragraph", "children": Inline[] }
       | { "type": "code", "text": string }
       | { "type": "quote", "blocks": Block[] }
       | { "type": "list", "ordered": bool, "start": number, "items": ListItem[] }
       | { "type": "rule" }
ListItem = { "checked": true | false | null, "blocks": Block[] }
Inline = { "type": "text", "text": string }
       | { "type": "strong" | "em" | "del", "children": Inline[] }
       | { "type": "code", "text": string }
       | { "type": "link", "href": string | null, "children": Inline[] }
       | { "type": "break" }
```

### Mapping (parse.ts)

`marked.lexer(source, { gfm: true, breaks: true })`, then the tokens are mapped with a depth
counter starting at 0:

- Block tokens: `space` → nothing; `heading` → `heading` with `level = min(max(token.depth, 1), 3)`
  (`MAX_HEADING_LEVEL`), setext headings included; `paragraph` → `paragraph`; `text` (block-level
  text inside tight list items) → `paragraph`; `code` → `code` with marked's `text` (info string
  dropped); `hr` → `rule`; `blockquote` → `quote` with its child blocks at `depth + 1`; `list` →
  `list` (`start = Number(token.start)` when ordered, else `1`), each item → `{ checked, blocks }`
  with `checked = item.task ? item.checked === true : null` and the item's child tokens (minus
  `checkbox`) at `depth + 1`.
- Depth cap (`MARKDOWN_MAX_DEPTH = 6`): a `blockquote` or `list` token met at `depth >= 6`
  becomes `plain(token)` = `{ type: "paragraph", children: [{ type: "text", text: raw }] }`, where
  `raw` is marked's `token.raw` with trailing `\n`s removed. Raw is the source of that token as
  marked saw it, so outer quote markers are already stripped (`> > > > > > > deep` gives six
  `quote`s around a paragraph `"> deep"`; 40 levels give six `quote`s around `"> " × 34 + "deep"`;
  a 7th nested list gives the paragraph `"- level 7"` as the 6th list item's second block).
  Lists and quotes count together.
- Every other block token (`html`, `table`, `def`, ...) → `plain(token)`.
- Inline tokens: `text` and `escape` → the inline mapping of their nested `tokens` if they have a
  non-empty one, else `{ text: token.text }`; `strong`/`em`/`del` → same type with mapped
  children; `codespan` → `code`; `br` → `break`; `link` → `{ href: safeHref(token.href),
  children }`; `image` → `{ type: "text", text: alt }`, with `alt = token.text` or `"[image]"`
  (`EN['markdown.image']`) when empty, the URL is never kept; any other inline token (`html`, ...)
  → `{ type: "text", text: token.raw }`.
- `safeHref(href)`: `scheme = /^([a-z][a-z0-9+.-]*:)/` matched on `href.trim().toLowerCase()`
  (JS `trim` whitespace set, locale-independent lowercasing); the href is kept **unchanged**
  (original case and spacing) only if the scheme is `http:`, `https:` or `mailto:`, else `null`.
  No scheme (relative, `#anchor`, `//host`) → `null`.

### marked behaviors to mimic (all visible in the cases)

- `breaks: true`: every single newline inside a paragraph is a `break` (plus the usual
  two-space and backslash hard breaks). Trailing spaces before a newline are dropped, but spaces
  at the very end of a paragraph are kept (`"trail   "`, `"line  "`), and so are leading spaces
  (`"   lead"`); marked does not strip them as CommonMark renderers do.
- Text nodes are **not merged**: each backslash escape is its own text node (`\*not em\*` →
  `"*"`, `"not em"`, `"*"`), inline HTML tags are separate text nodes, and a non-escapable
  backslash stays (`\a` → `" \a"`).
- Named entities stay as typed (`&amp;`, `&copy;`, `&nbsp;`, `&bogus;`), but numeric character
  references are decoded (`&#169;` → `©`, `&#x41;` → `A`). Hrefs keep entities (`&amp;`) but lose
  backslash escapes (`\_` → `_`), angle brackets (`<https://x.org/a b>` → `https://x.org/a b`),
  surrounding spaces and the title.
- `\r\n` and lone `\r` become `\n` before lexing. NUL and a leading BOM are kept in text.
- Headings: levels 4-6 map to 3; `#######` and `#nospace` are paragraph text; closing `##` are
  removed; `===`/`---` under a line make setext headings 1/2.
- GFM tables, HTML blocks, HTML comments, link reference definitions (`[ref]: url "T"`, one
  paragraph per definition line) and footnote definitions become a paragraph with their raw
  source. Reference links still resolve (`[a][ref]`, shortcut `[b]`), with the same scheme check;
  an unknown reference stays literal text.
- GFM autolinks: `<https://…>` and `<mailto:…>` are links whose text is the URL; `<tel:+1>` and
  `<javascript:…>` are links with `href: null`; bare `www.example.com` gets `href
  "http://www.example.com"`, a bare email gets `mailto:`, a bare `https://…` URL excludes a
  trailing `.`.
- `~one~` and `~~two~~` are `del`; `~~~three~~~` is text. `***both***` is `em` around `strong`.
  Intraword `_` does not emphasize. Unclosed markers stay literal.
- Code spans strip one leading and trailing space and keep inner backticks (`` ``a`b`` `` → ``a`b``);
  marked's unmatched-backtick handling is reproduced in the `code spans` edge case.
- Fenced code: info string dropped, a blank line before the closing fence is kept (`"x\n"`), an
  unclosed fence runs to the end, a 4-backtick fence can contain 3-backtick lines. Indented code
  (4 spaces or a tab) drops trailing blank lines.
- Task items: `[x]`/`[X]` → `true`, `[ ]` → `false`, only when followed by a space and content;
  `- [ ]` alone and `- [x]no space` are plain text items (`checked: null`). Ordered lists can be
  tasks. Changing the bullet character (`-`, `+`, `*`) or the ordered delimiter (`.`, `)`)
  starts a new list.
- Ordered start: up to 9 digits (`0`, `007` → 7, `123456789`); 10 digits is paragraph text.
- Loose lists (blank lines between items) produce the same `paragraph` blocks as tight lists.

## `markdown/toolbar.json`

`applyMarkdownAction(action, text, selection, time = '')` from
`apps/client/src/features/markdown/toolbar.ts`; `args = { action, text, selection: { start, end },
time? }` (`time` absent = not passed = `''`), `result = { text, selection: { start, end } }`.
39 cases.

Coverage: every `it` of `apps/client/test/markdown-toolbar.test.ts` (6 tests, 12 cases: the
bold/italic/strike test split in three, the heading cycle in its three chained steps, the
bullet/checklist/quote test in three), plus 27 `edge:` cases (cursor at start/end, whole text,
empty text, multi-line selection, UTF-16 positions after emoji and combining marks, a selection
splitting a surrogate pair, heading prefix removal with the cursor inside it, `###` and `#tag`
lines, multi-line selections, cursor right after/before a line break, a text starting with
`\n`, non-toggling bullets, links with and without a selection, time insertion with an explicit
time, without one, replacing a selection, non-ASCII time).

Selections always satisfy `0 <= start <= end <= text.length` (UTF-16 units). Algorithm:

- `bold`/`italic`/`strike` (markers `**`, `*`, `~~`): `before + m + inner + m + after`;
  `selection = { start + m.length, start + m.length + inner.length }`. Splitting a surrogate pair
  writes the lone halves (`"\ud83c**\udf38**"`).
- `lineStart(position)` = `text.lastIndexOf("\n", position - 1) + 1`, or 0 when not found, with
  JS semantics: for `position = 0` the search index is -1, which JS clamps to 0, so a text
  starting with `\n` has `lineStart(0) = 1` (case `heading at position 0 of a text starting with a
  line break` gives `"\n# abc"`).
- `bullet`/`checklist`/`quote` add `- `, `- [ ] `, `> ` at `lineStart(selection.start)` (no
  toggling: `- milk` becomes `- - milk`). Prefix replacement (`remove`, `add`) at `start =
  lineStart(selection.start)`: `text[0, start) + add + text[start + remove.length, ...)`,
  `shift = add.length - remove.length`, `selection = { max(start, s.start + shift), max(start,
  s.end + shift) }`.
- `heading`: `line = text.slice(lineStart(selection.start))`; `current` = `## ` if the line starts
  with it, else `# ` if it starts with that, else `''`; next = `# ` after `''`, `## ` after `# `,
  `''` after `## `; then prefix replacement (`remove = current`, `add = next`). A `### ` line has
  no recognized prefix, so it becomes `# ### deep`.
- `link`: `head = text[0, start) + "[" + label + "]("` with `label = text[start, end)`; text =
  `head + "url)" + text[end, ...)`; selection = `{ head.length, head.length + 3 }` (on `url`).
- `time`: `head = text[0, start) + time`; text = `head + text[end, ...)`; selection =
  `{ head.length, head.length }`. Without a time argument the selection is just deleted.

## `calendar/model.json`

Pure helpers of `apps/client/src/features/calendar/calendar-model.ts`. 64 cases.

| `fn` | Call | `args` | `result` |
|---|---|---|---|
| `canShowMonth` | `canShowMonth(yearMonth, currentYearMonth, monthsWithEntries)` | `{ yearMonth, currentYearMonth, monthsWithEntries }` | boolean |
| `monthTarget` | `monthTarget(from, delta, currentYearMonth, monthsWithEntries)` | `{ from, delta, currentYearMonth, monthsWithEntries }` | `"YYYY-MM"` or `null` |
| `pagerMonths` | `pagerMonths(currentYearMonth, monthsWithEntries, shownYearMonth)` | `{ currentYearMonth, monthsWithEntries, shownYearMonth }` | `"YYYY-MM"[]` |
| `isValidLocalDate` | `isValidLocalDate(value)` | `{ value }` | boolean |
| `formatMonthTitle` | `formatMonthTitle(yearMonth)` | `{ yearMonth }` | string |
| `weekdayNames` | `weekdayNames(weekStartsOn)` | `{ weekStartsOn }` (0 Sunday, 1 Monday) | string[7] |
| `cellLabel` | `cellLabel(localDate, count)` | `{ localDate, count }` | string |
| `constants` | `PAGER_LEAD_MONTHS` | `{}` | `{ PAGER_LEAD_MONTHS: 12 }` |

`monthsWithEntries` is a `ReadonlySet<string>`, encoded as a sorted array.

Coverage: every assertion of `apps/client/test/calendar-model.test.ts` for these functions
(`canShowMonth` 4, `monthTarget` 7, `pagerMonths` 3, `isValidLocalDate` 8, `formatMonthTitle` 2,
`weekdayNames` 2, `cellLabel` 3), plus edges. **Not covered: the two `monthDayInfo` tests**: the
function takes a stateful `GalleryIndex` (it calls `daysWithEntries` and `query`, both covered by
`tests/fixtures/golden/index/gallery-query.json`); it is not a pure helper. For the `pagerMonths`
test that only checks parts of the list, the golden stores the full list.

Algorithms (string comparisons are UTF-16 code-unit order; `addMonths` is the core function,
see `domain/calendar.json` in the core goldens):

- `canShowMonth = yearMonth <= currentYearMonth || monthsWithEntries.has(yearMonth)`. It is a plain
  string comparison: `"2026-6" <= "2026-06"` is false.
- `monthTarget`: `candidate = addMonths(from, delta)`; return it if `canShowMonth(candidate)`;
  else if `delta < 0` return it anyway; else the smallest month with entries that is `> candidate`,
  or `null`. `delta = 0` on an empty future month therefore jumps forward too.
- `pagerMonths`: `earliest` = the smallest of all `monthsWithEntries`, `shownYearMonth` and
  `currentYearMonth`; list every month from `addMonths(earliest, -12)` up to and including
  `currentYearMonth`, then append the months with entries that are `> currentYearMonth`, sorted. A
  shown future month without entries is **not** listed.
- `isValidLocalDate`: must match `/^\d{4}-\d{2}-\d{2}$/` (ASCII digits only; JS `$` without the
  `m` flag does not match before a final `\n`, so use `matchEntire`/`\z` in Kotlin), then the
  proleptic Gregorian date built with `setUTCFullYear(y, m - 1, d)` must have the same year, month
  and day (no overflow). Years `0000`-`0099` are taken literally (no 1900 shift); `0000` is a leap
  year. Kotlin: `LocalDate.of` in a try/catch over the parsed numbers gives the same answers.
- `formatMonthTitle`, `weekdayNames`, `cellLabel` are **Intl `en-US` with time zone `UTC`** plus the
  `EN` strings (`"<date>, no entries"`, `"<date>, 1 entry"`, `"<date>, <count> entries"`,
  count without grouping separators: `1000 entries`). They are deterministic in Node (ICU 78.3)
  and included so the Kotlin English strings can be checked; month title = `"MMMM y"`
  (`"January 999"` for year 0999: no zero padding), weekday = short English names, cell date =
  `"MMMM d, y"`. A Kotlin port that formats with the device locale only needs to match them in
  English.

## `gallery/arrange.json`

`apps/client/src/features/gallery/gallery-arrange.ts` (`groupSections`, `arrangeSections`,
`galleryAttributes`, `columnsFor`) and `gallery-items.ts` (`buildGalleryItems`). 51 cases.

Coverage: every `it` of `apps/client/test/gallery-arrange.test.ts` (9 tests, 15 cases: the media
count / grouping-off test, the month/year test, the day/none test, the `galleryAttributes` test
and the `columnsFor` test are split; "builds no day header for the ungrouped section" is the
`buildGalleryItems` case), plus 35 `edge:` cases (empty input, empty sections, oldest first,
note-length and seeded random sorts, ids without an entry, local vs UTC month, first-appearance
group order for non-date sorts, locale collation of tags, square and automatic column counts,
row splitting).

### Inputs

- `sections`: `DaySection[]` = `[{ "localDate": string, "entryIds": string[] }]`, used as given
  (in the app they come from `GalleryIndex.query`, newest first; the edge sections are written the
  way the index would produce them, e.g. `2026-12-31` before `2027-01-01` when the later local day
  has the earlier instant).
- `entries`: the full `IndexedEntry` objects (same JSON as the core goldens' index files:
  `id, isOwn, collectionIds, trashed, unreadable, takenAtUtc, utcOffsetMinutes, localDate,
  monthDay, tags, coverMediaId, mediaCount, hasVideo, notePreview, pending`; `localDate` was
  precomputed with `localDateOf(takenAtUtc, utcOffsetMinutes)` and is used as stored). The `get`
  argument is `id -> entries.find { it.id == id } ?: null`. Ids listed in `sections` but absent
  from `entries` (`ghost`) make `get` return `null`.
- `sort`: `GallerySort` = `{ key: "takenAt" | "noteLength" | "mediaCount" | "random", descending }`;
  `seed`: number (0, or 7 for `random`).

### Functions

| `fn` | Call | `args` | `result` |
|---|---|---|---|
| `arrangeSections` | `arrangeSections(sections, get, sort, groupByDay, seed)` | `{ sections, entries, sort, groupByDay, seed }` | `DaySection[]` |
| `groupSections` | `groupSections(sections, get, sort, groupBy, seed)` | `{ sections, entries, sort, groupBy, seed }`, `groupBy` ∈ `day, month, year, tag, none` | `DaySection[]` |
| `galleryAttributes` | `galleryAttributes(filters, hideShared)` | `{ filters, hideShared }` | `AttributeFilters` |
| `columnsFor` | `columnsFor(width, height, portrait, landscape)` | `{ width, height, portrait, landscape }` | number |
| `buildGalleryItems` | `buildGalleryItems(sections, columns, showOnThisDay)` | `{ sections, columns, showOnThisDay }` | `GalleryItem[]` |
| `constants` | group prefixes and grid constants | `{}` | object |

- `arrangeSections`: if `groupByDay && sort.key == "takenAt"`: descending → `sections` returned
  unchanged (empty sections included); ascending → sections in reverse order, each with its
  `entryIds` reversed (no re-sorting, so `2027-01-01` stays after `2026-12-31` reversed). Otherwise
  `ids` = all `entryIds` flattened in order; `[]` if none, else
  `[{ localDate: "", entryIds: sortEntryIds(ids, get, sort, seed) }]` (`UNGROUPED_DATE = ""`).
  `sortEntryIds` (core, `packages/core/src/index/sort.ts`) is specified in
  `tests/fixtures/golden/README.md` ("`sortEntryIds`"): missing entries last, measure, then
  `takenAtUtc` descending, then id; `random` is the seeded double-precision LCG Fisher-Yates.
- `groupSections`: `day` → `arrangeSections(..., groupByDay = true)`. Otherwise `flat =
  arrangeSections(..., groupByDay = false)` and `ids` = its entry ids; `none` or no ids → `flat`.
  Else bucket `ids` in order into an insertion-ordered map (`LinkedHashMap`) of keys, each section
  being `{ localDate: key, entryIds }`:
  - `month`/`year`: key = `"m:"`/`"y:"` + `(get(id)?.localDate ?? "").slice(0, 7 / 4)` (a missing
    entry gives the bare `"m:"`). If `sort.key != "takenAt"` the groups stay in first-appearance
    order. For `takenAt` they are sorted with the comparator `(left.key < right.key ? 1 : -1) *
    (descending ? 1 : -1)` (keys are unique), i.e. key descending for newest first, ascending
    otherwise.
  - `tag`: an entry with tags is listed under `"t:" + tag` for each of its tags; an entry without
    tags or without an entry goes to `"n:"` (`UNTAGGED_GROUP`). Groups are sorted with `"n:"` last
    and the others by `left.key.localeCompare(right.key)`: **ICU collation of the default locale,
    tertiary strength**, not code-unit order (`t:10 < t:9 < t:Apfel < t:apple < t:b c < t:b-c <
    t:beach < t:eclair < t:éclair < t:Zebra`). JS sort is stable, entries inside each group keep
    the arranged order. The tag set was chosen so that root, en, de, sv, cs, ja, tr, lt and da
    collations agree; tags differing only by case (`apple`/`Apple`) were avoided because Danish
    sorts uppercase first. Kotlin: an ICU collator (`android.icu.text.Collator`, or ICU4J on the
    JVM) with tertiary strength reproduces it; the JDK's own `java.text.Collator` is not
    ICU-based and is not guaranteed to give the same order (check it against this case).
- `galleryAttributes(filters, hideShared)`: `filters` is the device setting `galleryFilters` =
  `{ video, note, multiple, shared, pending, keptOffline, favorite }` (tri-states `off`,
  `include`, `exclude`); result `{ video, note, multiple, shared: hideShared ? "exclude" :
  filters.shared, pending, favorite }`, `keptOffline` dropped.
- `columnsFor`: `chosen = width > height ? landscape : portrait` (a square is portrait); if
  `chosen == 0` (`GALLERY_COLUMNS_AUTO`) → `max(3, floor(width / 110))` (`GRID_MIN_COLUMNS`,
  `GRID_TILE_MIN_PX`; doubles, `439.9` → 3, `440` → 4, `0` → 3), else `chosen` (no upper clamp).
- `buildGalleryItems`: `[{ type: "onThisDay" }]` first when `showOnThisDay`; then per section a
  `{ type: "header", localDate }` unless `localDate == ""`, followed by rows `{ type: "row",
  localDate, entryIds: next `columns` ids, firstPosition }` where `firstPosition` counts entries
  across all sections from 0. An empty section yields only its header.

## `archive/zips.json` + `archive/files/*`

The export's ZIP container: `createFflateArchiveWriter` (`apps/client/src/adapters/archive/
fflate-archive-writer.ts`, fflate `Zip` + `ZipDeflate` level 6 / `ZipPassThrough`) and fflate's
`unzipSync`. 20 cases: 8 `writeArchive`, 11 `readArchive` (the 8 written zips plus 3 made by
`zipSync`) and `constants`.

All binary data lives in `archive/files/`: `*.bin` are file contents, `*.zip` are archives.
Identical contents are stored once (`nonascii.bin`, `empty.bin`, `photo.bin` and `readme.bin`
are referenced more than once).

### `writeArchive`

`args = { files: [{ name, mtime, compress, content: { file, size, sha256 } }] }`,
`result = { zip: { file, size, sha256 } }`. Procedure:

1. With the process time zone set to **UTC**, create the writer over an in-memory `PartSink`
   whose `write` appends the chunk.
2. For each file in order: `addFile(name, { compress, mtime: new Date(mtime) })`, then one
   `push(content, final = true)`. The writer sets `mtime = clampZipDate(mtime)`: earlier than
   `1980-01-01T12:00:00.000Z` → that instant, later than `2099-12-31T12:00:00.000Z` → that
   instant (both inclusive bounds, see `export/zip.json` in the core goldens).
3. `finish()`. The zip is the concatenation of every chunk written.

This is what the export does (`apps/client/src/services/export/export-service.ts`): media are
stored and pushed in several chunks (chunking does not change stored bytes), notes, README and
the missing-files list are pushed once; notes and the missing list are deflated.

Cases: `stored and deflated files of one entry plus a README`, `stored files only, as the export
writes media`, `empty files, stored and deflated`, `non-ASCII file names`, `a deflated file
larger than 1 MiB` (1048577 bytes of a repeated 75-byte line), `mtimes before 1980 and after 2099
are clamped, seconds are halved` (10 files: 1970, 1979-12-31T23:59:59.999, 1980-01-01T00:00
and T12:00, odd seconds, 2099-12-31T12:00 and T23:59:59, 2100, 2150), `a deflated file that does
not shrink` (512 noise bytes; fflate still stores it deflated, 540 bytes), `no files` (22-byte
end record only). Stored and deflated entries both appear; the judge unzips each with fflate.

Byte layout written by fflate's streaming `Zip` (little-endian integers):

- Per file, in order: local header = `50 4b 03 04`, version needed `20` (u16), flags (u16),
  method (u16: 0 stored, 8 deflate), DOS time (u16), DOS date (u16), CRC-32 `0`, compressed size
  `0`, uncompressed size `0`, name length (u16, UTF-8 bytes), extra length `0`, name (UTF-8).
  Then the data: stored bytes, or the raw DEFLATE stream (no zlib header). Then a 16-byte data
  descriptor: `50 4b 07 08`, CRC-32, compressed size, uncompressed size (u32 each).
- Flags: `0x0008` (data descriptor) always, plus `0x0800` (UTF-8 name) when the name contains any
  non-ASCII character (fflate tests "UTF-8 length != UTF-16 length"). Deflate level 6 adds no
  level bits.
- DOS date/time from the clamped mtime's **wall clock in the process time zone** (UTC here):
  date = `(year - 1980) << 9 | month << 5 | day`, time = `hour << 11 | minute << 5 | second >> 1`
  (odd seconds round down: `09:30:15` is stored as `09:30:14`).
- Central directory, one record per file in order: `50 4b 01 02`, version made by `20` with host
  byte `0` (u16 `0x0014`), version needed `20`, the same flags, method, time, date, the real
  CRC-32, compressed and uncompressed sizes, name length, extra length `0`, comment length `0`,
  disk `0`, internal attributes `0`, external attributes `0` (u32), local header offset (u32),
  name.
- End record: `50 4b 05 06`, disk numbers `0`, `0`, entry count (u16) twice, central directory size
  (u32), central directory offset (u32), comment length `0`. No ZIP64 (the export splits parts
  below 4 GiB).

Comparing a Kotlin writer: archives whose entries are all stored (`stored-only.zip`,
`no-files.zip`, and the stored entries' headers in the others) can be matched byte for byte. The
DEFLATE streams are fflate's own (level 6) and will not match `java.util.zip.Deflater`; for
deflated entries compare every header field except the compressed size and the data, then inflate
and compare the content (`size`/`sha256` in the `readArchive` case of the same scenario). The
generator forces `TZ=UTC`; a Kotlin test must format the DOS fields in UTC to compare bytes (on a
device the export uses the device time zone, which is why the clamp bounds are at noon UTC).

### `readArchive`

`args = { zip: { file, size, sha256 } }`, `result` = fflate `unzipSync(zip)` as
`[{ name, size, sha256 }]` sorted by `name` in UTF-16 order (the entries' uncompressed content).
Directory entries appear as names ending in `/` with size 0. Extra case field `producer`:
`"writeArchive"` (the 8 archives above, same names) or `"zipSync"` (3 archives made with
fflate's one-shot `zipSync`, all `mtime 2026-11-13T09:30:16.000Z` in UTC, no data descriptors,
sizes and CRC in the local headers):

- `zipSync, all files stored (level 0), sizes in the local headers`: `a.txt`, `b/c.bin`
  (`level: 0`).
- `zipSync level 9 with a directory entry, a file comment, an extra field, Unix attributes and a
  non-ASCII name`: `{ folder: { "Café 東京 🌸.md": … }, "note.md": […, { comment: "a comment ✓",
  extra: { 0x5455: 01 80 1b 02 6a } }] }` with `level: 9, os: 3, attrs: 0o100644 << 16`. The nested
  object adds a `folder/` directory entry (deflated, 2 bytes). The comment is only in the central
  directory; the extended-timestamp extra field is in both headers.
- `zipSync with per-file levels, an empty file and a large file at level 1`: `stored.jpg` at level
  0, `empty.txt` at the default level 9, the 1 MiB+1 file at level 1.

A Kotlin reader (e.g. `java.util.zip.ZipInputStream`/`ZipFile`) must list exactly these entries
with these contents, handle data descriptors, UTF-8 names (flag `0x0800` set for the writer's
non-ASCII names, and also for the `zipSync` ones), extra fields and comments.

### `constants`

`{ EXPORT_MTIME_MIN, EXPORT_MTIME_MAX, timeZone: "UTC" }`. The deflate level (6) is a private
constant of `fflate-archive-writer.ts`.

## Files

| File | Cases | `fn`s |
|---|---|---|
| `markdown/parse.json` | 50 | `parseMarkdown` 49, `constants` |
| `markdown/toolbar.json` | 39 | `applyMarkdownAction` |
| `calendar/model.json` | 64 | `canShowMonth` 6, `monthTarget` 16, `pagerMonths` 7, `isValidLocalDate` 25, `formatMonthTitle` 3, `weekdayNames` 2, `cellLabel` 4, `constants` |
| `gallery/arrange.json` | 51 | `groupSections` 19, `arrangeSections` 12, `galleryAttributes` 4, `columnsFor` 11, `buildGalleryItems` 4, `constants` |
| `archive/zips.json` | 20 | `writeArchive` 8, `readArchive` 11, `constants` |

Total size about 1.6 MB, of which `archive/files/large.bin` is 1 MiB.
