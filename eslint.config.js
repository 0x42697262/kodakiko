import tseslint from 'typescript-eslint';
import kodakiko from './tools/eslint-local/index.js';

const SOURCE_FILES = '**/*.{ts,tsx,js}';
const PACKAGE_SOURCE_FILES = '{apps,packages}/*/src/**/*.{ts,tsx}';

function forbid(...names) {
  return { 'no-restricted-imports': ['error', { patterns: names }] };
}

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/.expo/**',
      '**/coverage/**',
      'apps/client/android/**',
      'apps/client/ios/**',
      'apps/client/expo-env.d.ts',
      'apps/client/public/workers/**',
      'apps/client/public/media-sw.js',
      'docs/**',
      'tests/ui/reports/**',
    ],
  },
  ...tseslint.configs.recommended,
  {
    files: [SOURCE_FILES],
    plugins: { kodakiko },
    rules: {
      'kodakiko/no-comments': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      'no-debugger': 'error',
    },
  },
  {
    files: [PACKAGE_SOURCE_FILES],
    ignores: ['**/constants.ts', '**/*-constants.ts', '**/migrations/**', '**/*.test.ts'],
    rules: {
      '@typescript-eslint/no-magic-numbers': [
        'error',
        {
          ignore: [0, 1],
          ignoreArrayIndexes: true,
          ignoreEnums: true,
          ignoreNumericLiteralTypes: true,
          ignoreReadonlyClassProperties: true,
          ignoreTypeIndexes: true,
        },
      ],
    },
  },
  {
    files: ['packages/contract/**/*.{ts,tsx,js}'],
    rules: forbid('@kodakiko/*', 'react', 'react*', 'expo', 'expo*', 'node:*'),
  },
  {
    files: ['packages/core/src/**/*.{ts,tsx,js}'],
    rules: forbid(
      'react',
      'react-native',
      'react-native-*',
      'expo',
      'expo-*',
      'node:*',
      'libsodium*',
      '@kodakiko/server',
      '@kodakiko/client',
    ),
  },
  {
    files: ['apps/client/**/*.{ts,tsx,js}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: ['@kodakiko/server'],
          paths: [{ name: 'react-native', importNames: ['Pressable'], message: 'Use Touchable from src/ui (spec 24).' }],
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='allowFontScaling'][value.expression.value=false]",
          message: 'Respect the system font scale (spec 24).',
        },
      ],
    },
  },
  {
    files: ['apps/client/src/ui/**/*.{ts,tsx}'],
    rules: forbid('@kodakiko/server'),
  },
  {
    files: ['apps/server/**/*.{ts,tsx,js}'],
    rules: forbid('@kodakiko/client', 'react', 'react*', 'expo', 'expo*'),
  },
);
