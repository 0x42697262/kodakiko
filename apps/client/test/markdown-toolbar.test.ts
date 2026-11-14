import { describe, expect, it } from 'vitest';
import { applyMarkdownAction } from '../src/features/markdown/toolbar';

const at = (start: number, end = start) => ({ start, end });

describe('markdown toolbar', () => {
  it('wraps a selection in bold, italic and strikethrough markers', () => {
    expect(applyMarkdownAction('bold', 'a big day', at(2, 5))).toEqual({ text: 'a **big** day', selection: at(4, 7) });
    expect(applyMarkdownAction('italic', 'a big day', at(2, 5)).text).toBe('a *big* day');
    expect(applyMarkdownAction('strike', 'a big day', at(2, 5)).text).toBe('a ~~big~~ day');
  });

  it('inserts an empty pair and puts the cursor between it when nothing is selected', () => {
    expect(applyMarkdownAction('bold', 'ab', at(1))).toEqual({ text: 'a****b', selection: at(3) });
  });

  it('cycles a heading at the start of the current line', () => {
    const once = applyMarkdownAction('heading', 'one\ntwo', at(5));
    expect(once.text).toBe('one\n# two');
    const twice = applyMarkdownAction('heading', once.text, once.selection);
    expect(twice.text).toBe('one\n## two');
    expect(applyMarkdownAction('heading', twice.text, twice.selection).text).toBe('one\ntwo');
  });

  it('adds list, checklist and quote prefixes to the current line', () => {
    expect(applyMarkdownAction('bullet', 'milk', at(2)).text).toBe('- milk');
    expect(applyMarkdownAction('checklist', 'milk', at(2)).text).toBe('- [ ] milk');
    expect(applyMarkdownAction('quote', 'said', at(0)).text).toBe('> said');
  });

  it('turns a selection into a link with the cursor on the address', () => {
    expect(applyMarkdownAction('link', 'see site', at(4, 8))).toEqual({ text: 'see [site](url)', selection: at(11, 14) });
  });

  it('inserts a given time at the cursor', () => {
    expect(applyMarkdownAction('time', 'at ', at(3), '14:05')).toEqual({ text: 'at 14:05', selection: at(8) });
  });
});
