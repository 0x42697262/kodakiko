import {
  ANDROID_RELEASES_URL,
  APP_ROOT_ID,
  ASSET_PATHS,
  CSS_CLASSES,
  LINK_VIEWER_IDS,
} from './keys.ts';
import { TEXT } from './text.ts';

const HTML_ESCAPES: Readonly<Record<string, string>> = Object.freeze({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
});
const HTML_SPECIAL = /[&<>"']/g;

export function escapeHtml(value: string): string {
  return value.replace(HTML_SPECIAL, (character) => HTML_ESCAPES[character] ?? character);
}

function head(title: string, extra: string): string {
  return [
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">',
    extra,
    '<meta name="referrer" content="no-referrer">',
    '<meta name="color-scheme" content="light dark">',
    `<title>${escapeHtml(title)}</title>`,
    `<link rel="stylesheet" href="${ASSET_PATHS.styles}">`,
  ]
    .filter((line) => line !== '')
    .join('\n');
}

function document(headHtml: string, bodyHtml: string): string {
  return `<!doctype html>\n<html lang="en">\n<head>\n${headHtml}\n</head>\n<body>\n${bodyHtml}\n</body>\n</html>\n`;
}

export function viewerPage(): string {
  const extra = [
    '<meta name="robots" content="noindex, nofollow">',
    `<script src="${ASSET_PATHS.script}" defer></script>`,
  ].join('\n');
  const body = [
    `<main id="${APP_ROOT_ID}" data-testid="${LINK_VIEWER_IDS.screen}">`,
    `<div class="${CSS_CLASSES.status}" role="status"><div class="${CSS_CLASSES.spinner}" data-testid="${LINK_VIEWER_IDS.spinner}" aria-hidden="true"></div><p>${escapeHtml(TEXT.loading)}</p></div>`,
    '</main>',
  ].join('\n');
  return document(head(TEXT.getAppTitle, extra), body);
}

export function getAppPage(): string {
  const body = [
    `<main class="${CSS_CLASSES.page} ${CSS_CLASSES.getApp}" data-testid="${LINK_VIEWER_IDS.getApp}">`,
    `<h1 class="${CSS_CLASSES.title}">${escapeHtml(TEXT.getAppHeading)}</h1>`,
    `<p>${escapeHtml(TEXT.getAppBody)}</p>`,
    `<p><a class="${CSS_CLASSES.button} ${CSS_CLASSES.primary}" data-testid="${LINK_VIEWER_IDS.getAppDownload}" href="${ANDROID_RELEASES_URL}" rel="noopener">${escapeHtml(TEXT.getAppDownload)}</a></p>`,
    '</main>',
  ].join('\n');
  return document(head(TEXT.getAppTitle, ''), body);
}
