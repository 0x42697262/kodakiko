import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..', '..');
const SOURCE = join(HERE, 'strings.json');
const TARGET = join(REPO, 'apps', 'android', 'ui', 'core', 'src', 'main', 'res', 'values', 'strings.xml');
const ONE = 1;
const INDENT = '    ';

type Argument = string | number;
type Case = { readonly quantity?: number; readonly args: readonly Argument[]; readonly text: string };
type Resource = { readonly key: string; readonly name: string; readonly type: 'string' | 'plurals'; readonly cases: readonly Case[] };
type Part = { readonly literal: string } | { readonly argument: number };
type Template = readonly Part[];

function occurrences(value: Argument): RegExp {
  const escaped = String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return typeof value === 'number' ? new RegExp(`(?<![0-9])${escaped}(?![0-9])`, 'g') : new RegExp(escaped, 'g');
}

function derive(name: string, item: Case): Template {
  const marks: { readonly start: number; readonly end: number; readonly argument: number }[] = [];
  item.args.forEach((value, argument) => {
    for (const match of item.text.matchAll(occurrences(value))) {
      marks.push({ start: match.index, end: match.index + match[0].length, argument });
    }
  });
  marks.sort((left, right) => left.start - right.start);
  const parts: Part[] = [];
  let cursor = 0;
  for (const mark of marks) {
    if (mark.start < cursor) throw new Error(`${name}: overlapping arguments in ${JSON.stringify(item.text)}`);
    if (mark.start > cursor) parts.push({ literal: item.text.slice(cursor, mark.start) });
    parts.push({ argument: mark.argument });
    cursor = mark.end;
  }
  if (cursor < item.text.length) parts.push({ literal: item.text.slice(cursor) });
  return parts;
}

function render(template: Template, args: readonly Argument[]): string {
  return template.map((part) => ('literal' in part ? part.literal : String(args[part.argument]))).join('');
}

function verify(name: string, template: Template, item: Case): void {
  const actual = render(template, item.args);
  if (actual !== item.text) throw new Error(`${name}: template renders ${JSON.stringify(actual)}, expected ${JSON.stringify(item.text)}`);
}

function escapeLiteral(text: string, formatted: boolean): string {
  const escaped = text
    .replace(/\\/g, '\\\\')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\t/g, '\\t');
  return formatted ? escaped.replace(/%/g, '%%') : escaped;
}

function escapeTemplate(template: Template, formatted: boolean): string {
  const body = template
    .map((part) => ('literal' in part ? escapeLiteral(part.literal, formatted) : `%${part.argument + 1}$s`))
    .join('')
    .replace(/ {2,}/g, (run) => '\\u0020'.repeat(run.length))
    .replace(/^ /, '\\u0020')
    .replace(/ $/, '\\u0020');
  return /^[@?]/.test(body) ? `\\${body}` : body;
}

function stringElement(resource: Resource): string {
  const item = resource.cases[0];
  if (item === undefined || resource.cases.length !== 1) throw new Error(`${resource.name}: a string needs exactly one case`);
  const template = derive(resource.name, item);
  verify(resource.name, template, item);
  return `${INDENT}<string name="${resource.name}">${escapeTemplate(template, item.args.length > 0)}</string>`;
}

function pluralsElement(resource: Resource): string {
  const one = resource.cases.find((item) => item.quantity === ONE);
  const others = resource.cases.filter((item) => item.quantity !== ONE);
  const other = others.at(-1);
  if (one === undefined || other === undefined) throw new Error(`${resource.name}: plurals need a quantity 1 case and another quantity`);
  const oneTemplate = derive(resource.name, one);
  const otherTemplate = derive(resource.name, other);
  verify(resource.name, oneTemplate, one);
  for (const item of others) verify(resource.name, otherTemplate, item);
  return [
    `${INDENT}<plurals name="${resource.name}">`,
    `${INDENT}${INDENT}<item quantity="one">${escapeTemplate(oneTemplate, true)}</item>`,
    `${INDENT}${INDENT}<item quantity="other">${escapeTemplate(otherTemplate, true)}</item>`,
    `${INDENT}</plurals>`,
  ].join('\n');
}

const { resources } = JSON.parse(readFileSync(SOURCE, 'utf8')) as { resources: readonly Resource[] };
const elements = resources.map((resource) => (resource.type === 'plurals' ? pluralsElement(resource) : stringElement(resource)));
const xml = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>', ...elements, '</resources>', ''].join('\n');

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(TARGET, 'utf8');
  } catch {
    current = '';
  }
  if (current !== xml) {
    process.stderr.write('strings.xml is out of date; run node tests/fixtures/ui-strings/emit-android.ts\n');
    process.exit(1);
  }
} else {
  mkdirSync(dirname(TARGET), { recursive: true });
  writeFileSync(TARGET, xml);
}
const plurals = resources.filter((resource) => resource.type === 'plurals').length;
process.stdout.write(`${resources.length - plurals} strings, ${plurals} plurals\n`);
