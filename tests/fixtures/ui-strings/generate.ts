import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..', '..');
const CLIENT_SRC = join(REPO, 'apps', 'client', 'src');
const EN_FILE = join(CLIENT_SRC, 'text', 'en.ts');
const FLOWS = join(REPO, 'tests', 'ui', 'android', 'flows');
const GENERATOR = 'tests/fixtures/ui-strings/generate.ts';
const FORMAT = 1;

const ts = createRequire(join(REPO, 'package.json'))('typescript') as typeof import('typescript');

type Value = string | number | boolean | null;
type Case = { readonly quantity?: number; readonly args: readonly (string | number)[]; readonly text: string };
type Resource = { readonly key: string; readonly name: string; readonly type: 'string' | 'plurals'; readonly variant?: string; readonly cases: readonly Case[] };
type Variant = { readonly suffix: string; readonly label: string; readonly fixed: Readonly<Record<number, Value>> };

const DROPPED_FILES: readonly { readonly pattern: RegExp; readonly reason: string }[] = [
  { pattern: /\.web\.tsx?$/, reason: 'web platform file (parity.md section 2)' },
  { pattern: /^text\/en\.ts$/, reason: 'the string table itself' },
  { pattern: /^features\/link-viewer\//, reason: 'public link viewer moves to apps/link-viewer (D-108)' },
  { pattern: /^adapters\/memory\//, reason: 'in-memory test doubles' },
  { pattern: /^features\/diagnostics\/(filestore-self-test|rowstore-self-test|upload-self-test|index-benchmark|self-test-)/, reason: 'diagnostics keeps only the vector self-test and debug info (D-135)' },
];

const DROPPED_KEYS: Readonly<Record<string, string>> = {
  'media.webHandleRequired': 'web file handles (parity.md section 2)',
  'capture.refusal.undecodableWeb': 'B-029, HEIC refused in a browser (dropped with web)',
  'signIn.keepSignedIn': 'B-105, keep me signed in on this browser (dropped with web)',
  'settings.keepUnlocked': 'B-105, keep me unlocked (dropped with web)',
  'settings.keepUnlockedHint': 'B-105, keep me unlocked (dropped with web)',
  'settings.autoLockIdle': 'idle auto-lock is a web mechanism (parity.md section 2)',
  'settings.exportDownloads': 'U-20 browser download fallback (replaced by SAF)',
  'storage.locationWeb': 'browser site storage (web only)',
  'settings.notPersisted': 'browser storage persistence (StorageSettingsScreen non-Android branch)',
  'settings.persisted': 'browser storage persistence (StorageSettingsScreen non-Android branch)',
  'settings.askAgain': 'browser storage persistence (StorageSettingsScreen non-Android branch)',
  'share.unsupported': 'navigator.share (web only)',
  'video.streamingUnavailable': 'B-046, browser video streaming (web only)',
  'welcome.signIn': 'WelcomeScreen WebActions (platformTraits.chooseServerAtWelcome is true on Android)',
  'welcome.signUp': 'WelcomeScreen WebActions (platformTraits.chooseServerAtWelcome is true on Android)',
  'links.copyLink': "LinksListScreen Platform.OS === 'web' branch",
  'links.copy': "LinkCreateScreen and AdminScreen Platform.OS === 'web' branches",
  'links.copied': "LinkCreateScreen Platform.OS === 'web' branch",
  'diagnostics.measureKdf': 'KDF timing dropped (D-135)',
  'diagnostics.kdfSummary': 'KDF timing dropped (D-135)',
  'diagnostics.kdfParams': 'KDF timing dropped (D-135)',
  'diagnostics.rowStoreSelfTest': 'storage self-tests dropped (D-135)',
  'diagnostics.fileStoreSelfTest': 'storage self-tests dropped (D-135)',
  'diagnostics.selfTestPassed': 'storage self-tests dropped (D-135)',
  'tempFiles.diagnosticsList': 'storage self-tests dropped (D-135)',
  'tempFiles.diagnosticsListResult': 'storage self-tests dropped (D-135)',
  'sync.diagnosticsUploadTest': 'upload self-test dropped (D-135)',
  'sync.diagnosticsUploadTestResult': 'upload self-test dropped (D-135)',
  'diagnostics.indexBenchmark': 'index benchmark dropped (D-135)',
  'diagnostics.indexBenchmarkBuild': 'index benchmark dropped (D-135)',
  'diagnostics.indexBenchmarkQuery': 'index benchmark dropped (D-135)',
  'diagnostics.indexBenchmarkVerdict': 'index benchmark dropped (D-135)',
  'diagnostics.indexLastBuild': 'index benchmark dropped (D-135)',
};

const TEMPLATE_KEYS: Readonly<Record<string, string>> = {
  'admin.people': "AdminScreen EN[`admin.${value}`] over ['people', 'invites', 'server']",
  'admin.invites': "AdminScreen EN[`admin.${value}`] over ['people', 'invites', 'server']",
  'admin.server': "AdminScreen EN[`admin.${value}`] over ['people', 'invites', 'server']",
};

const VARIANTS: Readonly<Record<string, readonly Variant[]>> = {
  'errors.recoveryWordsInvalid': [
    { suffix: '', label: 'position === null', fixed: { 0: null } },
    { suffix: '_position', label: 'position !== null', fixed: {} },
  ],
  'diagnostics.vectorCase': [
    { suffix: '_pass', label: 'passed', fixed: { 1: true } },
    { suffix: '_fail', label: 'failed', fixed: { 1: false } },
  ],
  'stats.hours': [
    { suffix: '', label: 'hours > 0', fixed: {} },
    { suffix: '_minutes_only', label: 'hours === 0', fixed: { 0: 0 } },
  ],
  'share.itemLabel': [
    { suffix: '', label: 'not checked', fixed: { 1: false } },
    { suffix: '_selected', label: 'checked', fixed: { 1: true } },
  ],
};

const AMERICAN_SPELLING: readonly (readonly [RegExp, string])[] = [[/\bcancelled\b/g, 'canceled'], [/\bCancelled\b/g, 'Canceled']];

const DROPPED_TAGS: Readonly<Record<string, string>> = {
  'welcome.signIn': 'WelcomeScreen WebActions (web only)',
  'welcome.signUp': 'WelcomeScreen WebActions (web only)',
  'links.copy': "Platform.OS === 'web' branches",
  'links.copied': "Platform.OS === 'web' branch",
  'signIn.keepSignedIn': 'B-105 (web only)',
  'settings.keepUnlocked': 'B-105 (web only)',
  'share.unsupported': 'navigator.share (web only)',
  'export.downloads': 'U-20 browser download fallback (replaced by SAF)',
  'settings.persistence': 'browser storage persistence (StorageSettingsScreen non-Android branch)',
  'settings.askPersist': 'browser storage persistence (StorageSettingsScreen non-Android branch)',
  'diagnostics.measureKdf': 'D-135',
  'diagnostics.rowStoreSelfTest': 'D-135',
  'diagnostics.fileStoreSelfTest': 'D-135',
  'tempFiles.diagnosticsList': 'D-135',
  'sync.diagnosticsUploadTest': 'D-135',
  'diagnostics.indexBenchmark': 'D-135',
};

const EXTRA_TAGS: Readonly<Record<string, string>> = {
  'sync.offline': 'sync-status-banner testID `sync.${model.id}` (SyncBannerId)',
  'sync.dead': 'sync-status-banner testID `sync.${model.id}` (SyncBannerId)',
  'sync.waitingForWifi': 'sync-status-banner testID `sync.${model.id}` (SyncBannerId)',
  'diagnostics.runVectors': 'DiagnosticsScreen testID={action.key}',
};

const ERROR_TAG_FILE = 'features/auth/error-text.ts';
const NOT_TEST_ID_OBJECTS = new Set(['VARIANT_SUBKEY_IDS']);
const STRING_SAMPLES = ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo', 'Foxtrot'];
const NUMBER_SAMPLES = [7, 13, 42, 5, 9, 11];
const PLURAL_QUANTITIES = [1, 2, 0, 5];

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

function droppedReason(rel: string): string | undefined {
  return DROPPED_FILES.find((rule) => rule.pattern.test(rel))?.reason;
}

function parse(path: string) {
  return ts.createSourceFile(path, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true, path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
}

function each(node: import('typescript').Node, visit: (node: import('typescript').Node) => void): void {
  visit(node);
  ts.forEachChild(node, (child) => each(child, visit));
}

function snake(key: string): string {
  return key.split('.').map((part) => part.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()).join('_');
}

function american(text: string): string {
  return AMERICAN_SPELLING.reduce((current, [pattern, replacement]) => current.replace(pattern, replacement), text);
}

function literalText(node: import('typescript').Node | undefined): string | undefined {
  if (node === undefined) return undefined;
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  if (ts.isJsxExpression(node)) return literalText(node.expression);
  if (ts.isParenthesizedExpression(node)) return literalText(node.expression);
  return undefined;
}

const { EN } = (await import(pathToFileURL(EN_FILE).href)) as { EN: Record<string, string | ((...args: never[]) => string)> };
const allKeys = new Set(Object.keys(EN));
const enSource = parse(EN_FILE);
type Param = { readonly name: string; readonly type: string };
const params = new Map<string, Param[]>();
each(enSource, (node) => {
  if (!ts.isPropertyAssignment(node) || !ts.isStringLiteral(node.name)) return;
  const init = node.initializer;
  if (ts.isArrowFunction(init)) {
    params.set(node.name.text, init.parameters.map((p) => ({ name: p.name.getText(enSource), type: p.type === undefined ? 'unknown' : p.type.getText(enSource) })));
  }
});

const files = walk(CLIENT_SRC).filter((path) => /\.tsx?$/.test(path)).sort();
const keptFiles = files.filter((path) => droppedReason(relative(CLIENT_SRC, path).split('\\').join('/')) === undefined);
const usedIn = new Map<string, Set<string>>();
const idObjects = new Map<string, Map<string, string>>();
const idObjectRefs = new Set<string>();
const tagSources = new Map<string, Set<string>>();

function addTag(tag: string, source: string): void {
  if (!tagSources.has(tag)) tagSources.set(tag, new Set());
  tagSources.get(tag)?.add(source);
}

type TemplateUse = { readonly rel: string; readonly expression: import('typescript').TemplateExpression; readonly source: import('typescript').SourceFile };
const templateTags: TemplateUse[] = [];
const parsed = keptFiles.map((path) => ({ rel: relative(CLIENT_SRC, path).split('\\').join('/'), source: parse(path) }));
for (const { rel, source } of parsed) {
  each(source, (node) => {
    if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) && allKeys.has(node.text)) {
      if (!usedIn.has(node.text)) usedIn.set(node.text, new Set());
      usedIn.get(node.text)?.add(rel);
      if (rel === ERROR_TAG_FILE) addTag(node.text, `${rel} (error banner testID = text key)`);
    }
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && /_IDS$/.test(node.name.text) && !NOT_TEST_ID_OBJECTS.has(node.name.text) && node.initializer !== undefined) {
      const values = new Map<string, string>();
      each(node.initializer, (inner) => {
        if (ts.isPropertyAssignment(inner)) {
          const text = literalText(inner.initializer);
          if (text !== undefined) values.set(inner.name.getText(source), text);
        }
      });
      idObjects.set(`${node.name.text}@${rel}`, values);
    }
    if (ts.isPropertyAccessExpression(node) && ts.isIdentifier(node.expression) && /_IDS$/.test(node.expression.text)) {
      idObjectRefs.add(`${node.expression.text}.${node.name.text}`);
    }
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'testId') {
      const text = literalText(node.arguments[0]);
      if (text !== undefined) addTag(text, `${rel} testId()`);
    }
    const isTestIdAttribute = ts.isJsxAttribute(node) && node.name.getText(source) === 'testID';
    const isTestIdProperty = ts.isPropertyAssignment(node) && node.name.getText(source) === 'testID';
    if (isTestIdAttribute || isTestIdProperty) {
      const value = isTestIdAttribute ? node.initializer : (node as import('typescript').PropertyAssignment).initializer;
      const text = literalText(value);
      if (text !== undefined) addTag(text, `${rel} testID`);
      const expression = value !== undefined && ts.isJsxExpression(value) ? value.expression : value;
      if (expression !== undefined && ts.isTemplateExpression(expression)) templateTags.push({ rel, expression, source });
    }
  });
}

const idValueByRef = new Map<string, string>();
for (const [qualified, values] of idObjects) {
  const name = qualified.split('@')[0] ?? '';
  for (const [prop, value] of values) idValueByRef.set(`${name}.${prop}`, value);
}
for (const [qualified, values] of idObjects) {
  const [name, rel] = qualified.split('@');
  for (const [prop, value] of values) {
    if (idObjectRefs.has(`${name}.${prop}`)) addTag(value, `${rel} ${name}.${prop}`);
  }
}

const dynamicFamilies: string[] = [];
for (const { rel, expression, source } of templateTags) {
  const head = expression.head.text;
  const first = expression.templateSpans[0];
  if (head === '' && first !== undefined && ts.isPropertyAccessExpression(first.expression)) {
    const base = idValueByRef.get(first.expression.getText(source));
    const tail = first.literal.text;
    if (base !== undefined && expression.templateSpans.length === 1 && tail.startsWith('.') && tail.length > 1) {
      addTag(`${base}${tail}`, `${rel} template`);
      continue;
    }
    if (base !== undefined) continue;
  }
  const base = head.endsWith('.') ? head.slice(0, -1) : '';
  if (base.includes('.')) addTag(base, `${rel} list base \`${expression.getText(source)}\``);
  else dynamicFamilies.push(`${rel}: ${expression.getText(source)}`);
}
for (const [tag, why] of Object.entries(EXTRA_TAGS)) addTag(tag, why);
for (const tag of Object.keys(DROPPED_TAGS)) tagSources.delete(tag);

const keptKeys = [...new Set([...usedIn.keys(), ...Object.keys(TEMPLATE_KEYS)])].filter((key) => !(key in DROPPED_KEYS)).sort();
for (const key of [...Object.keys(DROPPED_KEYS), ...Object.keys(TEMPLATE_KEYS)]) {
  if (!allKeys.has(key)) throw new Error(`unknown key in the generator tables: ${key}`);
}

function sampleArgs(types: readonly string[]): Value[] {
  let strings = 0;
  let numbers = 0;
  return types.map((type) => {
    if (type === 'boolean') return false;
    if (type === 'string') return STRING_SAMPLES[strings++] ?? 'Zulu';
    return NUMBER_SAMPLES[numbers++] ?? 99;
  });
}

function pluralParam(key: string, fn: (...args: never[]) => string): number | undefined {
  const match = /(\w+) === 1 \?/.exec(fn.toString());
  if (match === null) return undefined;
  const index = (params.get(key) ?? []).findIndex((param) => param.name === match[1]);
  return index >= 0 ? index : undefined;
}

function androidArgs(args: readonly Value[], fixed: Readonly<Record<number, Value>>): (string | number)[] {
  return args.filter((_, index) => !(index in fixed)).map((value) => (typeof value === 'number' || typeof value === 'string' ? value : String(value)));
}

function resourcesFor(key: string): Resource[] {
  const entry = EN[key];
  const name = snake(key);
  if (typeof entry === 'string') return [{ key, name, type: 'string', cases: [{ args: [], text: american(entry) }] }];
  const fn = entry as (...args: Value[]) => string;
  const types = (params.get(key) ?? []).map((param) => param.type);
  const variants = VARIANTS[key] ?? [{ suffix: '', label: '', fixed: {} }];
  const plural = pluralParam(key, entry);
  return variants.map((variant) => {
    const base = sampleArgs(types).map((value, index) => (index in variant.fixed ? (variant.fixed[index] as Value) : value));
    const quantities = plural === undefined ? [undefined] : PLURAL_QUANTITIES;
    const cases = quantities.map((quantity) => {
      const args = base.map((value, index) => (index === plural && quantity !== undefined ? quantity : value));
      const text = american(fn(...args));
      const android = androidArgs(args, variant.fixed);
      return quantity === undefined ? { args: android, text } : { quantity, args: android, text };
    });
    const resource: Resource = { key, name: `${name}${variant.suffix}`, type: plural === undefined ? 'string' : 'plurals', cases };
    return variant.label === '' ? resource : { ...resource, variant: variant.label };
  });
}

const resources = keptKeys.flatMap(resourcesFor);
const names = new Set<string>();
for (const resource of resources) {
  if (names.has(resource.name)) throw new Error(`resource name collision: ${resource.name}`);
  names.add(resource.name);
}

const maestroIds = new Set<string>();
for (const file of readdirSync(FLOWS).filter((name) => name.endsWith('.yaml'))) {
  for (const match of readFileSync(join(FLOWS, file), 'utf8').matchAll(/\bid:\s*"?([^"\s]+)"?/g)) {
    const id = (match[1] ?? '').replace(/\.\d+$/, '');
    if (/^[A-Za-z][\w.]*$/.test(id)) maestroIds.add(id);
  }
}
const tags = [...tagSources.keys()].sort();
const tagSet = new Set(tags);
const maestroUncovered = [...maestroIds].filter((id) => !tagSet.has(id)).sort();

const strings = {
  format: FORMAT,
  generator: GENERATOR,
  source: 'apps/client/src/text/en.ts',
  keptKeyCount: keptKeys.length,
  resourceCount: resources.length,
  americanSpelling: AMERICAN_SPELLING.map(([pattern, replacement]) => `${pattern.source} -> ${replacement}`),
  droppedKeys: DROPPED_KEYS,
  templateKeys: TEMPLATE_KEYS,
  unusedKeys: [...allKeys].filter((key) => !usedIn.has(key) && !(key in TEMPLATE_KEYS) && !(key in DROPPED_KEYS)).sort(),
  resources,
};
const testTags = {
  format: FORMAT,
  generator: GENERATOR,
  tagCount: tags.length,
  droppedTags: DROPPED_TAGS,
  dynamicFamilies: dynamicFamilies.sort(),
  maestroUncovered,
  tags: tags.map((tag) => ({ tag, sources: [...(tagSources.get(tag) ?? [])].sort() })),
};

const outputs: Record<string, string> = {
  'strings.json': `${JSON.stringify(strings, null, 2)}\n`,
  'test-tags.json': `${JSON.stringify(testTags, null, 2)}\n`,
};
const check = process.argv.includes('--check');
let stale = false;
for (const [file, content] of Object.entries(outputs)) {
  const path = join(HERE, file);
  if (check) {
    let current = '';
    try {
      current = readFileSync(path, 'utf8');
    } catch {
      current = '';
    }
    if (current !== content) {
      stale = true;
      process.stderr.write(`${file} is out of date\n`);
    }
  } else {
    writeFileSync(path, content);
  }
}
process.stdout.write(`${keptKeys.length} kept keys, ${resources.length} resources, ${tags.length} test tags, ${maestroUncovered.length} Maestro ids not in source\n`);
if (stale) process.exit(1);
