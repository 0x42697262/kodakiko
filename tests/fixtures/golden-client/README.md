# Client golden fixtures for the Kotlin port

Deterministic outputs of the Expo client's TypeScript code (`apps/client`) that the Kotlin client
must reproduce: the markdown parser and toolbar, the calendar model, the gallery arrangement,
the export ZIP container, the v0.3.1 device settings (`settings.device`) and the v0.3.1 API call
table (`api/calls.json`). Everything except `manifest.json` (the judge's list of required files and
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
  describe the scenario; `api/calls.json` uses `<GROUP>.<call>#<n>`. Every file except
  `markdown/toolbar.json` (whose constants are private to the module and spelled out below),
  `settings/device.json` (whose constants are spelled
  out in its section, because the Kotlin judge replays every case of that file) and `api/calls.json` (whose Kotlin judge
  requires every case to be `fn: "call"`; its constants are spelled out in its section) ends with one case named `constants` (`fn: "constants"`,
  `args: {}`), whose `result` lists the TS constants the file depends on, so the Kotlin
  `*Constants` objects can be checked against them.
- `args` is an object keyed by the TS parameter names; the call passes them in the order given
  for each `fn` below. Absent optional keys mean "argument not passed" (TS `undefined`).
- `result` is the return value encoded as below. No case in these files throws (there is no
  `{ "error", "message" }` result); `settings/device.json` reports a thrown `update` as
  `rejected: true` instead.
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

## `settings/device.json`

The device settings of v0.3.1 (`apps/client/src/services/settings/device-settings.ts`), stored as
JSON under the kv key `settings.device` of the account's row store. The Kotlin client installs as
an update over v0.3.1 and must read what v0.3.1 wrote and write what v0.3.1 would read. 140 cases:
`nativeDefaults` 1, `resolveStored` 84, `update` 55. No `constants` case (the Kotlin judge would
reject an unknown `fn`); the constants are listed below.

### Functions

| `fn` | `args` | `result` |
|---|---|---|
| `nativeDefaults` | `{}` | `DEVICE_SETTINGS_DEFAULTS` of `device-settings-defaults.native.ts` (the Android defaults), no `schemaVersion` |
| `resolveStored` | `{ raw }` | `{ value, invalid, loaded }` |
| `update` | `{ raw, partial }` | `{ rejected, value, stored, notified }` |

`raw` is what the kv table holds under `settings.device` before the call:

- `{ "$absent": true }`: no row (the key was never written).
- `{ "$bytes": "<hex>" }`: exactly these bytes are stored (`kvSet`). Used for bytes that are not
  valid UTF-8 or not valid JSON, and for valid JSON that cannot be written by `JSON.stringify`
  (`1.0`, `-0`, `1e400`, duplicate keys, escaped key names, a byte order mark, extra whitespace).
- any other JSON value: stored as `JSON.stringify(raw)` in UTF-8 (`kvSetJson`). The Kotlin judge
  stores `raw.toString()`, which is the same JSON (no raw value contains a lone surrogate).

Procedure (TS): an in-memory `RowStore` (`createMemoryRowStore`, opened for one account) is seeded
with `raw`, then `createDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS (native),
onInvalidFields })`.

- `resolveStored`: `value` = `get()`; `invalid` = the list passed to `onInvalidFields` (`[]` when
  it is not called; it is called at most once, only with a non-empty list); `loaded` = `loaded()`.
- `update`: no `onInvalidFields`; one listener that counts its calls is subscribed, then
  `await update(partial)` (a throw is caught). `rejected` = whether it threw; `value` = `get()`
  afterwards; `stored` = the bytes now under `settings.device`, parsed as JSON (`null` when there
  is no row); `notified` = the listener's call count (1 after a successful update, 0 after a
  rejected one). No case rejects an update over `$bytes` that are not JSON, so `stored` always
  parses.

`value` and the `stored` object (minus `schemaVersion`) are `DeviceSettingsValue`:

```
{ encryptThumbnails: bool, originalCacheCapBytes: int, largeUploadsOnWifiOnly: bool,
  largeUploadThresholdBytes: int, locationForCaptures: bool, autoLockMinutes: int,
  appTheme: "system"|"light"|"dark", dateFormat: "system"|"iso"|"dmy"|"mdy", relativeDates: bool,
  startScreen: "gallery"|"calendar",
  galleryDisplayMode: "comfortableGrid"|"compactGrid"|"photoGrid"|"list",
  galleryColumnsPortrait: int, galleryColumnsLandscape: int, galleryGroupByDay: bool,
  galleryGroupBy: "day"|"month"|"year"|"tag"|"none",
  galleryBadges: { video, photoCount, note, pending, keptOffline, favorite: bool },
  galleryShowTagTabs: bool, galleryShowCounts: bool,
  gallerySort: { key: "takenAt"|"noteLength"|"mediaCount"|"random", descending: bool },
  galleryFilters: { video, note, multiple, shared, pending, keptOffline, favorite: "off"|"include"|"exclude" },
  hideShared, onThisDay, confirmTrash: bool, captureStartCamera: "back"|"front",
  captureKeepScreenOn, hideNotePreviews, secureScreen, fullScreen, hideBarsOnScroll,
  showTechnicalDetails, holdToRecordOn: bool, holdToRecordMaxSeconds: 5|10|15|30|60|120,
  cameraApp: "kodakiko"|"system", syncPaused: bool, tagOrder: string[], pureBlack,
  hapticFeedback, dynamicColor, autoplayVideos, autoplayOnMobileData: bool,
  timeFormat: "24h"|"12h" }
```

This is also the key order of the zod schema shape, and therefore of the stored JSON:
`{"schemaVersion":1,"encryptThumbnails":…,…,"timeFormat":…}` (compact `JSON.stringify`, nested
objects in the order shown). The key order of `value` in the file is the defaults' order (the
same); compare parsed JSON.

Constants: `DEVICE_SETTINGS_SCHEMA_VERSION = 1`, `MIN_LARGE_UPLOAD_THRESHOLD_BYTES = 1048576`,
`AUTO_LOCK_MINUTES_MIN = 1`, `AUTO_LOCK_MINUTES_MAX = 60`, `GALLERY_COLUMNS_AUTO = 0`,
`GALLERY_COLUMNS_MAX = 10`, `HOLD_TO_RECORD_SECONDS = [5, 10, 15, 30, 60, 120]`; Android defaults
`originalCacheCapBytes = 2147483648`, `largeUploadThresholdBytes = 104857600`,
`autoLockMinutes = 5`, `holdToRecordMaxSeconds = 15` (the rest in the `nativeDefaults` case).

### Reading (`createDeviceSettings`)

1. Read: `kvGetJson(settings.device, z.unknown())` = no row → `null`; else
   `JSON.parse(utf8Decode(bytes))`. `utf8Decode` is `TextDecoder('utf-8', { fatal: true })`: invalid
   UTF-8 (lone continuation byte, overlong form, encoded surrogate, truncated sequence) throws,
   and **one leading byte order mark is removed** (a second one is not whitespace and fails
   `JSON.parse`). Any throw → `loaded = false`, the value is the defaults, `onInvalidFields` is not
   called. Otherwise `loaded = true` (also when there is no row).
2. A value that is not a plain object (`null`, array, string, number, boolean) → the defaults,
   `invalid = []`.
3. `raw.schemaVersion !== 1` (missing, `"1"`, `0`, `null`, `2`) → the defaults and
   `invalid = ["schemaVersion"]`, even when every other field is valid. `1.0` is the number 1.
4. Otherwise start from the defaults and, for each schema field in shape order that is a key of
   `raw`, parse `raw[field]` with the field's zod schema: success replaces the default, failure
   keeps the default and appends the field name to `invalid`. Absent fields keep the default and
   are not reported. Unknown keys (`holdToRecord`, `sparkle`, `__proto__` as a JSON key) are
   ignored.

### Writing (`update(partial)`)

`next = deviceSettingsSchema.parse({ schemaVersion: 1, ...current, ...partial })` (a shallow
merge: a nested object in `partial` replaces the whole nested object; `schemaVersion` in `partial`
overrides the 1, so `2` is rejected). If the parse throws, nothing is written, `current` and
`loaded` do not change and no listener runs. Otherwise `kvSetJson(settings.device, next)`, then
`current = next` without `schemaVersion`, `loaded = true`, and every subscribed listener is called
once. An update over unreadable bytes, a newer `schemaVersion` or a non-object value overwrites it
with a complete version-1 value (the defaults plus `partial`); fields that were invalid when read
are written with their defaults.

### zod behaviors to mimic (zod 4.6.7)

- `z.boolean()`: only JSON `true`/`false` (`1`, `"true"`, `null`, `[]` fail).
- `z.enum([...])`: only those exact strings (case-sensitive; numbers and `null` fail).
- `z.number()` rejects non-numbers, `NaN` and ±Infinity (`1e400` parses to Infinity and fails).
  `.int()` in zod 4 is a **safe integer** check: `Number.isSafeInteger`, so `1.5` fails,
  `9007199254740991` passes, `9007199254740992` and `1e20` fail. Integral doubles pass whatever
  their spelling: `3.0`, `1e3`, `2E+6`, `10.000`, `1.2e2`, and `-0` (written back as `0`).
- Ranges are inclusive: `originalCacheCapBytes >= 0` (0 = no limit), `largeUploadThresholdBytes
  >= 1048576`, `1 <= autoLockMinutes <= 60`, `0 <= galleryColumns* <= 10` (0 = automatic). There
  is no upper bound other than the safe-integer limit on the two byte counts.
- `holdToRecordMaxSeconds`: `z.number()` refined to `[5, 10, 15, 30, 60, 120].includes(value)`:
  `"15"`, `7`, `0` and `15.5` fail; `120.0` passes.
- `tagOrder: z.array(z.string()).readonly()`: any array of strings, kept as is (empty strings,
  duplicates, non-ASCII, control characters, lone surrogates); one non-string element fails the
  whole field; an object with index keys is not an array.
- `z.object(...)` (nested `galleryBadges`, `gallerySort`, `galleryFilters`, and the whole schema
  on write): every non-default key is required; **unknown keys are stripped** (never written
  back); a non-object (array, string, `null`) fails. `galleryBadges.favorite` has
  `.default(true)` and `galleryFilters.favorite` `.default('off')`: applied only when the key is
  **absent** (settings stored before favorites existed); an explicit `null` fails the nested
  object. One bad nested key fails the whole nested field.
- `z.literal(1)` for `schemaVersion` on write.

### JSON behaviors to mimic

- `JSON.parse`: whitespace is only space, tab, LF and CR (U+00A0 fails); no trailing commas,
  comments, single quotes, unquoted keys or values, `NaN`, `True`, leading zeros (`01`), leading
  `+`, `1.`; raw control characters (< U+0020) inside strings fail; escapes are `\" \\ \/ \b \f \n
  \r \t \uXXXX` only (`\x41`, `\u41` fail); `\uXXXX` may produce lone surrogates; escaped key
  names are ordinary keys (`"schema\u0056ersion"` is `schemaVersion`); for duplicate keys the last
  value wins; empty input and whitespace-only input fail.
- `JSON.stringify` (what is written): compact, no spaces; integers printed in decimal; strings
  escape `"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t` with their short forms, other characters below
  U+0020 and **lone surrogates** as lowercase `\u00XX` / `\udXXX`; everything else (U+007F,
  U+2028, U+2029, non-ASCII, paired surrogates) is written as UTF-8. Kotlin's
  `String.encodeToByteArray()` would replace a lone surrogate by U+FFFD, so the writer must
  escape it first (case `update: edge: tagOrder with special characters …`).

### Coverage

Every `it` of `apps/client/test/device-settings.test.ts`, against the **native** defaults:

- `has the Android defaults` → `nativeDefaults`. `has the web defaults` is **not covered**: the
  Kotlin client is Android only.
- `keeps stored gallery filters and badges from before favorites …`, `fills newer fields …`,
  `returns the platform defaults when nothing is stored`, `falls back to the defaults when the
  stored value is not readable` (`$bytes 010203`), `replaces an invalid field …`, `uses the
  defaults and reports schemaVersion …`, `uses the defaults when the stored value has no
  schemaVersion`, `fills fields that are missing …` → `resolveStored` (the last one with native
  instead of web defaults).
- `merges an update …` → `update`. `notifies subscribers after an update and stops after
  unsubscribe` → `update` (`notified: 1`); the unsubscribe half is not expressible in this case
  shape (the judge subscribes once and never unsubscribes).
- `loads what an earlier instance stored` and `is loaded after a successful read, with or without
  a stored value` → an `update` case for the first instance plus a `resolveStored` case whose `raw`
  is that update's `stored` value (and a `$absent` case).
- `is not loaded after a read error and serves the defaults`: the TS test makes `kvGetJson` throw;
  here the read fails because the bytes are not valid UTF-8 (`$bytes 7bff7d`), which takes the same
  `catch` path.
- `rejects unknown choices and out-of-range columns` (7 assertions; the `holdToRecord` key
  assertion is covered by `nativeDefaults`), `accepts the defaults`, `rejects an auto-lock of %s
  minutes` (0, 61, 1.5, -5), `accepts an auto-lock of %i minutes` (1, 60), the upload threshold,
  cache cap and non-boolean switch tests (`deviceSettingsSchema.safeParse`) → `update` cases
  (a failing `safeParse` is a rejected update with nothing stored and no notification).

Edge cases (`edge:`): `schemaVersion` as a string, 0, `null`, `1.0`, newer with valid fields,
missing; an empty object; every boolean field with a wrong type and every boolean flipped; every
enum with an unknown and with another valid value; int boundaries (lowest/highest accepted, just
outside, fractions, unsafe integers, strings, `null`, integral spellings, overflow to infinity);
columns automatic/1/10/11/-1; `holdToRecordMaxSeconds` allowed (5, 10, 30, 60, 120) and not
(7, 0, `"15"`, 15.5); `tagOrder` variants; nested objects with and without the defaulted
`favorite`, with unknown keys, missing keys, `null` favorite, wrong types and non-objects;
unknown top-level keys and `__proto__`; duplicate keys; non-object raw values (array, array of a
settings object, string, number, boolean, `null`); 20 kinds of invalid JSON and 4 of invalid
UTF-8; a byte order
mark; JSON whitespace; escaped key names; updates merging several fields, touching only nested
objects, nested objects without `favorite`, a partial nested object (rejected, no deep merge),
unknown keys (dropped, also those stored earlier), an empty partial (still writes and
notifies), one invalid field among valid ones, a rejected update over a stored value (kept
byte for byte), `schemaVersion` 1 and 2 in the partial, `null`, updates over unreadable bytes,
over invalid fields, over a newer version and over a non-object value, the largest and an
unsafe cache cap, and strings that `JSON.stringify` escapes.

## `api/calls.json`

The v0.3.1 API call table: every `ApiCall` builder of the Expo client, invoked with fixed
arguments, and the call it builds (method, path, auth, retry flag, timeout, JSON body, response
schema). The Kotlin client (`:data:net`) must build the same calls; its `HttpClient` then sends
them like `apps/client/src/adapters/http/http.ts`. 69 cases, all `fn: "call"`.

### Case shape

```
{ "name": "<GROUP>.<call>#<n>",          n = 1, 2, ... per builder, in file order
  "fn": "call",
  "args": { "group": "<GROUP>", "call": "<call>", "args": [ ...positional arguments as JSON... ] },
  "result": { "method": "GET"|"POST"|"PATCH"|"DELETE",
              "path": string,              relative to the server base URL, query string included
              "auth": "session" | "none" | { "linkToken": string, "viewToken": string|null },
              "idempotent": bool,          true = HttpClient may retry it
              "timeoutMs": number|null,    null = the default HTTP_TIMEOUT_MS (20000)
              "body": JSON|null,           null = no body (TS `body` undefined)
              "responseSchema": string } }
```

`responseSchema` is the name under which the zod schema is exported from `@kodakiko/contract`
(found by identity over the module's exports; the Kotlin `ContractSchemas.<name>` has the same
`name`), or one of two local schemas: `unknown` (`z.unknown()`: any JSON or an empty body; the
Kotlin `UnknownResponse`) and `void` (`z.undefined()`: only an empty body; the Kotlin
`VoidResponse`).

Every request-body argument is parsed with its contract request schema in the generator, so a
Kotlin replay can decode it with `ContractSchemas.<schema>`; ids match `idSchema` and byte strings
`bytesSchema`.

### Groups

| Group | v0.3.1 source | Calls (cases) | How the generator reaches it |
|---|---|---|---|
| `AUTH` | `services/auth-calls.ts` `AUTH_CALLS` | `params` 2, `signUp` 2, `signIn`, `session`, `keyBundle`, `recoveryStart`, `recoveryComplete`, `signOut`, `changePassword`, `requestDeletion`, `cancelDeletion` | direct call |
| `SYNC` | `services/sync/sync-calls.ts` `SYNC_CALLS` | `syncState`, `collection` 2, `diff` 3, `createEntry` 2, `getEntry`, `patchEntry` 2, `trashEntry`, `restoreEntry`, `deleteEntry`, `addMedia`, `removeMedia`, `presign` 2, `parts`, `confirm` | direct call |
| `OBJECT` | `services/downloads/object-calls.ts` `OBJECT_CALLS` | `download` | direct call |
| `SHARING` | `services/sharing/sharing-calls.ts` `SHARING_CALLS` | `create` 2, `rename`, `remove`, `addEntry`, `removeEntry`, `publicKey` 2, `share`, `listShares`, `unshare` | direct call |
| `CONFIG` | `services/config/config-service.ts`, inline call in `refresh` | `get` | `createConfigService` with a capturing `HttpClient` (it refreshes once on creation) |
| `LINK` | `services/links/public-links-service.ts` `LINK_CALLS` (not exported) | `create` 7 (one per `LINK_EXPIRIES` value), `list`, `revoke` | the `const LINK_CALLS = Object.freeze({ ... });` block is read from the source file, its types are removed with `node:module` `stripTypeScriptTypes` and it is evaluated with the real `ROUTES`, `publicLinkDto`, `publicLinkListResponse` and `z` (`create` cannot be reached through the service without a full link upload) |
| `ACCOUNT` | `services/account/account-service.ts` `ME_CALL` (not exported) | `me` | `createAccountService(...).refreshMe()` with a capturing `HttpClient` |
| `ADMIN` | `services/admin/admin-service.ts` `createAdminClient` (inline `call` helper) | `listUsers` 5, `patchUser` 3, `createInvite` 2, `listInvites`, `revokeInvite`, `stats` | each `createAdminClient` method with a capturing `HttpClient` |

The capturing `HttpClient` records the `ApiCall` passed to `request` and rejects; the generator
fails if a service makes other than exactly one request, if a builder of a group has no case, or if
a schema is neither a contract export nor one of the two local schemas.

**Not included: the link viewer** (`features/link-viewer/link-session.ts`: `openCall`
`POST /public-links/:linkId/open` with `{ linkToken }`, `entriesCall` `GET
/public-links/:linkId/entries[?cursor=]` with `{ linkToken, viewToken }`). In v0.4.0 the public
link viewer is a standalone web page (`apps/link-viewer`, D-108) that keeps the TypeScript
link-session logic; the Android app never opens a link, so the Kotlin client has no link-viewer
calls.

### Behaviors pinned by the cases

- Path ids are inserted **unencoded** (`ROUTES` does no escaping); `SHARING.publicKey#2` uses
  `Bob Smith/ä?#` to show it. Only the admin user-list cursor is escaped, with JS
  `encodeURIComponent` (unreserved `A-Z a-z 0-9 - _ . ! ~ * ' ( )`, everything else as UTF-8
  `%XX` with uppercase hex): `ADMIN.listUsers#2`-`#5` (plain, reserved characters, the unreserved
  set, non-ASCII with an astral character, NBSP and a newline). A `null` cursor adds no query.
- `SYNC.diff` builds `?since=<since>&limit=<limit>` with JS number formatting (integers in
  decimal; `9007199254740991` in `#3`).
- `idempotent`: as written in each table; admin calls are idempotent unless `POST`.
- Timeouts: only `AUTH.session` (`RESTORE_TIMEOUT_MS`) and `AUTH.signOut`
  (`SIGN_OUT_TIMEOUT_MS`) set one, 5000 each.
- Bodies built inline (`SHARING.rename`, `addEntry`, `share`, `LINK.create`) are the
  `collectionPatchRequest`, `collectionEntryAddRequest`, `shareCreateRequest` and
  `publicLinkCreateRequest` objects; `ADMIN.patchUser` keeps an explicit `quotaBytes: null`.

## Files

| File | Cases | `fn`s |
|---|---|---|
| `markdown/parse.json` | 50 | `parseMarkdown` 49, `constants` |
| `markdown/toolbar.json` | 39 | `applyMarkdownAction` |
| `calendar/model.json` | 64 | `canShowMonth` 6, `monthTarget` 16, `pagerMonths` 7, `isValidLocalDate` 25, `formatMonthTitle` 3, `weekdayNames` 2, `cellLabel` 4, `constants` |
| `gallery/arrange.json` | 51 | `groupSections` 19, `arrangeSections` 12, `galleryAttributes` 4, `columnsFor` 11, `buildGalleryItems` 4, `constants` |
| `archive/zips.json` | 20 | `writeArchive` 8, `readArchive` 11, `constants` |
| `settings/device.json` | 140 | `nativeDefaults` 1, `resolveStored` 84, `update` 55 |
| `api/calls.json` | 69 | `call` 69 (`AUTH` 13, `SYNC` 20, `OBJECT` 1, `SHARING` 11, `CONFIG` 1, `LINK` 9, `ACCOUNT` 1, `ADMIN` 13) |

Total size about 2.0 MB (`settings/device.json` about 0.4 MB, because every case lists the full
settings value), of which `archive/files/large.bin` is 1 MiB.
