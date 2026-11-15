import { MARKDOWN_MAX_DEPTH, MAX_HEADING_LEVEL, SAFE_LINK_SCHEMES } from '../../src/features/markdown/constants';
import { parseMarkdown } from '../../src/features/markdown/parse';
import { applyMarkdownAction, type MarkdownAction, type Selection } from '../../src/features/markdown/toolbar';
import { EN } from '../../src/text/en';
import { functionCase, setJson, type GoldenCase, type GoldenWriter, type Json } from './shared';

const PATHS = Object.freeze({ parse: 'markdown/parse.json', toolbar: 'markdown/toolbar.json' });
const PARSE = 'parseMarkdown';
const TOOLBAR = 'applyMarkdownAction';
const LONG_WORD_UNIT = 'Lorem ipsum dolor sit amet, consectetur adipiscing elit. ';
const LONG_NOTE_CHARS = 100_000;
const LONG_LINE_COUNT = 200;
const DEEP_QUOTE_LEVELS = 40;
const QUOTE_MARKER = '> ';
const LIST_INDENT = '  ';

type ParseSpec = readonly [name: string, source: string];

function nestedQuotes(levels: number, text: string): string {
  return `${QUOTE_MARKER.repeat(levels)}${text}`;
}

function nestedList(levels: number): string {
  return Array.from({ length: levels }, (_, level) => `${LIST_INDENT.repeat(level)}- level ${level + 1}`).join('\n');
}

function longNote(): string {
  return LONG_WORD_UNIT.repeat(Math.ceil(LONG_NOTE_CHARS / LONG_WORD_UNIT.length)).slice(0, LONG_NOTE_CHARS);
}

function manyLines(): string {
  return Array.from({ length: LONG_LINE_COUNT }, (_, line) => `line ${line + 1} with *em*`).join('\n');
}

const PARSE_SPECS: readonly ParseSpec[] = [
  ['maps headings, capping depth at 3', '# One\n\n##### Five'],
  ['maps strong, em, del and code spans inside a paragraph', '**b** *i* ~~d~~ `c`'],
  ['keeps text unescaped as typed', 'a < b & "c" &amp;'],
  ['turns a single newline into a break', 'one\ntwo'],
  [
    'keeps http, https and mailto links and drops other schemes',
    '[a](https://example.com) [b](mailto:x@example.com) [c](javascript:alert(1)) [d](HTTP://x.org)',
  ],
  ['renders images as their alt text and never keeps the URL', '![a cat](https://example.com/a.png) ![](https://example.com/b.png)'],
  ['turns HTML into plain text with the raw source (block)', '<script>alert(1)</script>'],
  ['turns HTML into plain text with the raw source (inline)', 'x <b>y</b>'],
  ['maps code blocks, quotes and rules', '```\nlet a = 1;\n```\n\n> quoted\n\n---'],
  ['maps ordered, unordered and nested task lists (ordered, start 3)', '3. three\n4. four'],
  ['maps ordered, unordered and nested task lists (nested task list)', '- [x] done\n- [ ] open\n  - inner'],
  ['turns content nested deeper than 6 levels into plain text', '> > > > > > > deep'],
  ['returns nothing for an empty note', ''],
  ['edge: whitespace-only note', '   \n\n  \t\n'],
  ['edge: all heading levels and non-headings', '## Two\n### Three\n#### Four\n###### Six\n####### Seven\n#nospace'],
  ['edge: closing hashes, setext headings and inline formatting in a heading', '## Title ##\n\nSetext one\n===\n\nSetext two\n---\n\n# A **bold** `code` [link](https://x.org)'],
  ['edge: quotes nested exactly 6 levels', nestedQuotes(MARKDOWN_MAX_DEPTH, 'six')],
  ['edge: quotes nested 40 levels', nestedQuotes(DEEP_QUOTE_LEVELS, 'deep')],
  ['edge: lists nested 7 levels', nestedList(MARKDOWN_MAX_DEPTH + 1)],
  ['edge: lists nested 6 levels', nestedList(MARKDOWN_MAX_DEPTH)],
  ['edge: quote and list nesting count together', '> - a\n>   > - b\n>   >   > - c\n>   >   >   > d'],
  ['edge: lazy quote continuation', '> quote\nlazy continuation'],
  ['edge: image alt text keeps markdown source, image in link, reference image', '![**bold** alt](u "t") [![inner](i.png)](https://x.org) ![ref][r] ![](x)\n\n[r]: https://img'],
  ['edge: block HTML, comment and inline tags', '<div>\nhello\n</div>\n\n<!-- comment -->\n\nx<br>y <img src=x onerror=alert(1)>'],
  ['edge: GFM table stays raw text', '| a | b |\n|:--|--:|\n| 1 | 2 |\n\nafter'],
  [
    'edge: unsafe and relative link targets',
    '[js](javascript:alert(1)) [JS](JAVASCRIPT:x) [ws]( javascript:x) [data](data:text/html,hi) [vb](vbscript:x) [rel](/path) [anchor](#top) [proto](//x.org) [ftp](ftp://x.org) [tel](tel:+1) [file](file:///etc/passwd) [none]()',
  ],
  ['edge: autolinks and bare URLs', '<https://auto.link> <javascript:alert(1)> <mailto:a@b.c> <tel:+1> www.example.com x@y.com https://bare.org/path.'],
  [
    'edge: link destinations are kept as marked returns them',
    '[a](<https://x.org/a b>) [b](https://x.org/\\_x?a=1&amp;b=2) [c]( https://x.org ) [d](https://x.org "title") [**e** `f`](https://x.org) [](https://empty.org)',
  ],
  ['edge: mailto links', '[mail](mailto:someone@example.com?subject=Hi) [MAIL](MAILTO:X@Y.Z) <someone@example.com>'],
  ['edge: reference links and their definitions', '[a][ref] [b] [missing][nope]\n\n[ref]: https://x.org "T"\n[b]: javascript:alert(1)'],
  ['edge: task list variants', '- [X] upper\n- [ ]\n- [ ] \n* [x] star\n1. [x] ordered task\n- [x]no space'],
  ['edge: ordered list start numbers', '0. zero\n1. one\n\ntext\n\n007. seven\n\ntext\n\n1) paren\n\ntext\n\n123456789. nine\n\n1234567890. ten'],
  ['edge: loose lists and continuation paragraphs', '- a\n\n- b\n\n  para2\n\n1. x\n1. y\n\n   - [x] nested task\n3. z'],
  ['edge: list markers switch lists', '- a\n+ b\n* c\n1. d\n2) e'],
  ['edge: hard breaks', 'two spaces  \nbackslash\\\nplain\nend'],
  ['edge: trailing and leading spaces are kept', '   lead\n\ntrail   \n\nline  \n'],
  ['edge: code spans', '``a`b`` ` lead ` `unclosed `` ` `` `**not bold**`'],
  ['edge: fenced code with info string, tilde fence and blank lines', '```js\nx\n\n```\n\n~~~\ntilde\n~~~\n\n````\n```\nnested\n```\n````'],
  ['edge: indented and unclosed code blocks', '    indented\n    code\n\n\nafter\n\n\ttab code\n\n```\nunclosed'],
  ['edge: CRLF and CR line endings', 'a\r\nb\r\n\r\n```\r\nc\r\nd\r\n```\r\n\r\n> q\r\n\r\ne\rf'],
  ['edge: emphasis variants', '***both*** __strong__ _under_ snake_case_word **unclosed *em ~one~ ~~two~~ ~~~three~~~'],
  ['edge: backslash escapes split text nodes', '\\*not em\\* a\\_b a\\\\b \\# \\` \\a'],
  ['edge: thematic breaks', '***\n___\n- - -\n* * *'],
  ['edge: multiple blank lines between paragraphs', 'a\n\n\n\nb'],
  ['edge: non-ASCII text, NUL and BOM', '\uFEFFHello 🌍 مرحبا e\u0301 日本\u0000end'],
  ['edge: named entities are kept, numeric references are decoded', '&copy; &#169; &#x41; &nbsp; &bogus;'],
  ['edge: footnote syntax is plain text', 'Text[^1]\n\n[^1]: The note.'],
  ['edge: long note of 100000 characters', longNote()],
  ['edge: 200 lines in one paragraph', manyLines()],
];

function parseCases(): GoldenCase[] {
  const cases = PARSE_SPECS.map(([name, source]) => functionCase(`${PARSE}: ${name}`, PARSE, { source }, () => parseMarkdown(source)));
  cases.push({
    name: 'constants',
    fn: 'constants',
    args: {},
    result: {
      MARKDOWN_MAX_DEPTH,
      MAX_HEADING_LEVEL,
      SAFE_LINK_SCHEMES: setJson(SAFE_LINK_SCHEMES),
      imagePlaceholder: EN['markdown.image'],
    },
  });
  return cases;
}

type ToolbarSpec = {
  readonly name: string;
  readonly action: MarkdownAction;
  readonly text: string;
  readonly selection: Selection;
  readonly time?: string;
};

const at = (start: number, end = start): Selection => ({ start, end });

const HEADING_CYCLE_TEXTS = ['one\ntwo', 'one\n# two', 'one\n## two'] as const;
const HEADING_CYCLE_SELECTIONS = [at(5), at(7), at(8)] as const;

const TOOLBAR_SPECS: readonly ToolbarSpec[] = [
  { name: 'wraps a selection in bold, italic and strikethrough markers (bold)', action: 'bold', text: 'a big day', selection: at(2, 5) },
  { name: 'wraps a selection in bold, italic and strikethrough markers (italic)', action: 'italic', text: 'a big day', selection: at(2, 5) },
  { name: 'wraps a selection in bold, italic and strikethrough markers (strike)', action: 'strike', text: 'a big day', selection: at(2, 5) },
  { name: 'inserts an empty pair and puts the cursor between it when nothing is selected', action: 'bold', text: 'ab', selection: at(1) },
  ...HEADING_CYCLE_TEXTS.map((text, step): ToolbarSpec => ({
    name: `cycles a heading at the start of the current line (step ${step + 1})`,
    action: 'heading',
    text,
    selection: HEADING_CYCLE_SELECTIONS[step] ?? at(0),
  })),
  { name: 'adds list, checklist and quote prefixes to the current line (bullet)', action: 'bullet', text: 'milk', selection: at(2) },
  { name: 'adds list, checklist and quote prefixes to the current line (checklist)', action: 'checklist', text: 'milk', selection: at(2) },
  { name: 'adds list, checklist and quote prefixes to the current line (quote)', action: 'quote', text: 'said', selection: at(0) },
  { name: 'turns a selection into a link with the cursor on the address', action: 'link', text: 'see site', selection: at(4, 8) },
  { name: 'inserts a given time at the cursor', action: 'time', text: 'at ', selection: at(3), time: '14:05' },
  { name: 'edge: bold with the cursor at the start', action: 'bold', text: 'day', selection: at(0) },
  { name: 'edge: italic with the cursor at the end', action: 'italic', text: 'day', selection: at(3) },
  { name: 'edge: strike over the whole text', action: 'strike', text: 'gone', selection: at(0, 4) },
  { name: 'edge: bold on empty text', action: 'bold', text: '', selection: at(0) },
  { name: 'edge: italic over a multi-line selection', action: 'italic', text: 'one\ntwo\nthree', selection: at(2, 9) },
  { name: 'edge: bold counts UTF-16 units (emoji is 2)', action: 'bold', text: '🌸 day é', selection: at(0, 2) },
  { name: 'edge: bold after an emoji and a combining accent', action: 'bold', text: '🌸e\u0301x', selection: at(4, 5) },
  { name: 'edge: bold splitting a surrogate pair', action: 'bold', text: '🌸', selection: at(1, 2) },
  { name: 'edge: heading on an empty text', action: 'heading', text: '', selection: at(0) },
  { name: 'edge: heading with the cursor inside the prefix being removed', action: 'heading', text: '## two', selection: at(1, 4) },
  { name: 'edge: heading on a level-3 line adds a level-1 prefix', action: 'heading', text: '### deep', selection: at(5) },
  { name: 'edge: heading on a line starting with # without a space', action: 'heading', text: '#tag', selection: at(2) },
  { name: 'edge: heading uses the line of the selection start only', action: 'heading', text: 'one\ntwo\nthree', selection: at(1, 9) },
  { name: 'edge: heading with the cursor right after a line break', action: 'heading', text: 'one\ntwo', selection: at(4) },
  { name: 'edge: heading with the cursor at the end of a line', action: 'heading', text: 'one\ntwo', selection: at(3) },
  { name: 'edge: heading at position 0 of a text starting with a line break', action: 'heading', text: '\nabc', selection: at(0) },
  { name: 'edge: bullet on an empty line at the end', action: 'bullet', text: 'one\n', selection: at(4) },
  { name: 'edge: bullet does not toggle an existing bullet', action: 'bullet', text: '- milk', selection: at(6) },
  { name: 'edge: checklist uses the line of the selection start (second line)', action: 'checklist', text: 'a\nb\nc', selection: at(2, 5) },
  { name: 'edge: quote with the cursor at the end of the text', action: 'quote', text: 'one\ntwo', selection: at(7) },
  { name: 'edge: link with nothing selected', action: 'link', text: 'ab', selection: at(1) },
  { name: 'edge: link over the whole text', action: 'link', text: 'site', selection: at(0, 4) },
  { name: 'edge: link at the end after an emoji', action: 'link', text: '🌸 ', selection: at(3) },
  { name: 'edge: time replaces the selection', action: 'time', text: 'at noon!', selection: at(3, 7), time: '12:00' },
  { name: 'edge: time at the start', action: 'time', text: 'woke up', selection: at(0), time: '07:30 ' },
  { name: 'edge: time without a time argument deletes the selection', action: 'time', text: 'abc', selection: at(1, 2) },
  { name: 'edge: time with a non-ASCII time string', action: 'time', text: 'x', selection: at(1), time: '午後 2:05' },
];

function toolbarArgs(spec: ToolbarSpec): Json {
  const base = { action: spec.action, text: spec.text, selection: { start: spec.selection.start, end: spec.selection.end } };
  return spec.time === undefined ? base : { ...base, time: spec.time };
}

function toolbarCases(): GoldenCase[] {
  return TOOLBAR_SPECS.map((spec) =>
    functionCase(`${TOOLBAR}: ${spec.name}`, TOOLBAR, toolbarArgs(spec), () =>
      spec.time === undefined
        ? applyMarkdownAction(spec.action, spec.text, spec.selection)
        : applyMarkdownAction(spec.action, spec.text, spec.selection, spec.time),
    ),
  );
}

export async function writeMarkdownGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATHS.parse, parseCases());
  await writer.functionCases(PATHS.toolbar, toolbarCases());
}
