import { BROWSER_LABEL_PATTERNS, FALLBACK_BROWSER_LABEL } from '../../keys/device-label-keys';

export function deviceLabel(): string {
  const agent = navigator.userAgent;
  const match = BROWSER_LABEL_PATTERNS.find(({ pattern }) => agent.includes(pattern));
  return match?.label ?? FALLBACK_BROWSER_LABEL;
}
