import { describe, expect, it } from 'vitest';
import { parseMarkdown } from '../src/features/markdown/parse';

describe('parseMarkdown', () => {
  it('maps headings, capping depth at 3', () => {
    expect(parseMarkdown('# One\n\n##### Five')).toEqual([
      { type: 'heading', level: 1, children: [{ type: 'text', text: 'One' }] },
      { type: 'heading', level: 3, children: [{ type: 'text', text: 'Five' }] },
    ]);
  });

  it('maps strong, em, del and code spans inside a paragraph', () => {
    expect(parseMarkdown('**b** *i* ~~d~~ `c`')).toEqual([
      {
        type: 'paragraph',
        children: [
          { type: 'strong', children: [{ type: 'text', text: 'b' }] },
          { type: 'text', text: ' ' },
          { type: 'em', children: [{ type: 'text', text: 'i' }] },
          { type: 'text', text: ' ' },
          { type: 'del', children: [{ type: 'text', text: 'd' }] },
          { type: 'text', text: ' ' },
          { type: 'code', text: 'c' },
        ],
      },
    ]);
  });

  it('keeps text unescaped as typed', () => {
    expect(parseMarkdown('a < b & "c" &amp;')).toEqual([
      { type: 'paragraph', children: [{ type: 'text', text: 'a < b & "c" &amp;' }] },
    ]);
  });

  it('turns a single newline into a break', () => {
    expect(parseMarkdown('one\ntwo')).toEqual([
      {
        type: 'paragraph',
        children: [{ type: 'text', text: 'one' }, { type: 'break' }, { type: 'text', text: 'two' }],
      },
    ]);
  });

  it('keeps http, https and mailto links and drops other schemes', () => {
    const [block] = parseMarkdown(
      '[a](https://example.com) [b](mailto:x@example.com) [c](javascript:alert(1)) [d](HTTP://x.org)',
    );
    expect(block?.type).toBe('paragraph');
    const links = block?.type === 'paragraph' ? block.children.filter((inline) => inline.type === 'link') : [];
    expect(links.map((link) => (link.type === 'link' ? link.href : 'x'))).toEqual([
      'https://example.com',
      'mailto:x@example.com',
      null,
      'HTTP://x.org',
    ]);
  });

  it('renders images as their alt text and never keeps the URL', () => {
    expect(parseMarkdown('![a cat](https://example.com/a.png) ![](https://example.com/b.png)')).toEqual([
      {
        type: 'paragraph',
        children: [
          { type: 'text', text: 'a cat' },
          { type: 'text', text: ' ' },
          { type: 'text', text: '[image]' },
        ],
      },
    ]);
  });

  it('turns HTML into plain text with the raw source', () => {
    expect(parseMarkdown('<script>alert(1)</script>')).toEqual([
      { type: 'paragraph', children: [{ type: 'text', text: '<script>alert(1)</script>' }] },
    ]);
    expect(parseMarkdown('x <b>y</b>')).toEqual([
      {
        type: 'paragraph',
        children: [
          { type: 'text', text: 'x ' },
          { type: 'text', text: '<b>' },
          { type: 'text', text: 'y' },
          { type: 'text', text: '</b>' },
        ],
      },
    ]);
  });

  it('maps code blocks, quotes and rules', () => {
    expect(parseMarkdown('```\nlet a = 1;\n```\n\n> quoted\n\n---')).toEqual([
      { type: 'code', text: 'let a = 1;' },
      { type: 'quote', blocks: [{ type: 'paragraph', children: [{ type: 'text', text: 'quoted' }] }] },
      { type: 'rule' },
    ]);
  });

  it('maps ordered, unordered and nested task lists', () => {
    expect(parseMarkdown('3. three\n4. four')).toEqual([
      {
        type: 'list',
        ordered: true,
        start: 3,
        items: [
          { checked: null, blocks: [{ type: 'paragraph', children: [{ type: 'text', text: 'three' }] }] },
          { checked: null, blocks: [{ type: 'paragraph', children: [{ type: 'text', text: 'four' }] }] },
        ],
      },
    ]);
    expect(parseMarkdown('- [x] done\n- [ ] open\n  - inner')).toEqual([
      {
        type: 'list',
        ordered: false,
        start: 1,
        items: [
          { checked: true, blocks: [{ type: 'paragraph', children: [{ type: 'text', text: 'done' }] }] },
          {
            checked: false,
            blocks: [
              { type: 'paragraph', children: [{ type: 'text', text: 'open' }] },
              {
                type: 'list',
                ordered: false,
                start: 1,
                items: [
                  { checked: null, blocks: [{ type: 'paragraph', children: [{ type: 'text', text: 'inner' }] }] },
                ],
              },
            ],
          },
        ],
      },
    ]);
  });

  it('turns content nested deeper than 6 levels into plain text', () => {
    const blocks = parseMarkdown('> > > > > > > deep');
    let current = blocks[0];
    for (let level = 1; level < 6; level += 1) {
      expect(current?.type).toBe('quote');
      current = current?.type === 'quote' ? current.blocks[0] : undefined;
    }
    expect(current?.type).toBe('quote');
    const innermost = current?.type === 'quote' ? current.blocks[0] : undefined;
    expect(innermost).toEqual({ type: 'paragraph', children: [{ type: 'text', text: '> deep' }] });
  });

  it('returns nothing for an empty note', () => {
    expect(parseMarkdown('')).toEqual([]);
  });
});
