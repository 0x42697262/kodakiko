# UI strings and test tags kept from v0.3.1 (K157 judge fixture)

The Kotlin client must show the same English texts and expose the same test ids as the v0.3.1 Expo
client, for every flow that v0.4.0 keeps (`docs/planning/v0.4.0/parity.md`). This folder holds the
list the Kotlin judges in `apps/android/ui/core/src/test` read. Everything except this README and
`generate.ts` is generated:

```sh
node tests/fixtures/ui-strings/generate.ts           # rewrite strings.json and test-tags.json
node tests/fixtures/ui-strings/generate.ts --check   # exit 1 when a file is out of date
```

Node 26 runs the TypeScript directly (type stripping); the generator reads
`apps/client/src/**` with the repository's `typescript` package (AST, not regular expressions)
and imports `apps/client/src/text/en.ts` to evaluate every text. It needs `apps/client`, which the
cut-over (K261) deletes; the committed JSON files stay and the Kotlin judges never run Node.

## Which files count as kept

Every `.ts`/`.tsx` file under `apps/client/src` except:

- `*.web.ts`, `*.web.tsx` (web platform halves, parity.md section 2);
- `text/en.ts` (the table itself);
- `features/link-viewer/**` (the public link viewer moves to `apps/link-viewer`, D-108);
- `adapters/memory/**` (in-memory test doubles);
- the dropped diagnostics runners `features/diagnostics/{filestore-self-test,rowstore-self-test,upload-self-test,index-benchmark,self-test-*}.ts`
  (diagnostics keeps only the vector self-test and debug info, D-135).

## strings.json

- A key is kept when its exact string appears as a string literal anywhere in a kept file
  (`EN['…']`, but also indirect uses such as `PASSWORD_RULE_KEYS`, `testId('…')`, `StaticTextKey`
  tables), plus `admin.people`, `admin.invites`, `admin.server` (built as ``EN[`admin.${value}`]``),
  minus `droppedKeys` (each with its reason: web-only branches of shared screens, B-029, B-105,
  idle auto-lock, U-20 download fallback, browser storage persistence, D-135 diagnostics).
- `unusedKeys` lists keys that no v0.3.1 file uses at all (dead in v0.3.1); they are not required.
- Resource name: each dot-separated part of the key in snake case, joined with `_`
  (`onThisDay.yearsAgo` → `on_this_day_years_ago`, `prefs.time24` → `prefs_time24`).
- A function whose text depends on `x === 1` becomes `<plurals>`; the quantity is that argument and
  the format arguments are all the TypeScript arguments in order (`tags.deleteConfirm(tag, count)`
  is `getQuantityString(id, count, tag, count)`). Cases use quantities 1, 2, 0 and 5.
- Other functions become `<string>` with format arguments in the TypeScript order. Functions with a
  non-plural branch are split into one resource per branch (`variant` names the branch; the branch
  argument is removed from the format arguments):
  `errors.recoveryWordsInvalid` → `errors_recovery_words_invalid` (null) and
  `errors_recovery_words_invalid_position`; `diagnostics.vectorCase` → `_pass` / `_fail`;
  `stats.hours` → `stats_hours` (hours > 0) and `stats_hours_minutes_only`;
  `share.itemLabel` → `share_item_label` and `share_item_label_selected`.
- Every case's `text` is the TypeScript function's output for the listed arguments (sample strings
  `Alpha`, `Bravo`, …; sample numbers 7, 13, 42, …), so the judge compares what Android renders
  (`getString` / `getQuantityString`) with what v0.3.1 rendered. Placeholder syntax, escaping
  (apostrophes, quotes, `%`, `…`, `—`, curly quotes) and plural forms are therefore checked by result, not
  by spelling of the XML.
- American English: the only British spelling in `en.ts` is "cancelled"
  (`settings.exportCancelled`); the expected text uses "canceled" (`americanSpelling`).

## test-tags.json

A tag is kept when a kept file uses it as a static test id:

- the value of a `*_IDS` object property (`TEST_IDS`, `GALLERY_IDS`, `CALENDAR_IDS`, `ENTRY_IDS`,
  `CAPTURE_IDS` in `src/keys`, and the feature-level `ADMIN_IDS`, `LINK_IDS`, `SHARING_IDS`,
  `VIEWER_IDS`, `VIDEO_IDS`, `EDIT_PAGE_IDS`, `MARKDOWN_IDS`, …) that a kept file reads
  (`X_IDS.prop`);
- the literal argument of `testId('…')` (ids equal to text keys);
- a literal `testID="…"` / `testID: '…'`;
- a template id with a static two-part base, `` `edit.play.${position}` `` → `edit.play` (lists
  are `base.position`, see `listTestId`), or a resolved `` `${ADMIN_IDS.role}.member` `` →
  `admin.role.member`;
- the auth error banner ids (every text key in `features/auth/error-text.ts`, since
  `errorBanner().testID` is the key), the sync banner ids `sync.offline`, `sync.dead`,
  `sync.waitingForWifi` (`` `sync.${model.id}` ``) and `diagnostics.runVectors`
  (`testID={action.key}`);

minus `droppedTags` (web-only branches and D-135). `dynamicFamilies` lists template ids that are
not pinned here (single-part bases such as `` `crop.${corner}` ``, `` `toolbar.${action}` `` and
the settings-DSL suffixes `.dialog`, `.switch`, `.${value}`, `.mute`); the screen tasks that build
those widgets pin them. `maestroUncovered` lists ids used by `tests/ui/android/flows/*.yaml` that
no v0.3.1 source file produces (`tags.field` in U-10 is stale since D-87).

## Android resources

`emit-android.ts` turns `strings.json` into `apps/android/ui/core/src/main/res/values/strings.xml`
(869 strings, 21 plurals). Run `node tests/fixtures/ui-strings/emit-android.ts` after regenerating
the fixture, and `--check` to verify the XML is current. Numbers use `%N$s` so digits stay ASCII on
every device locale, like v0.3.1.
