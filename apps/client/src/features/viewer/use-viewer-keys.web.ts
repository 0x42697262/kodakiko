import { useEffect } from 'react';
import type { ViewerKeyHandlers } from './use-viewer-keys';

const PREVIOUS = -1;
const NEXT = 1;
const TYPING_TAGS: ReadonlySet<string> = new Set(['INPUT', 'TEXTAREA']);

export function useViewerKeys({ onMove, onBack }: ViewerKeyHandlers): void {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent): void {
      const target = event.target;
      if (target instanceof HTMLElement && TYPING_TAGS.has(target.tagName)) return;
      if (event.key === 'ArrowLeft') onMove(PREVIOUS);
      else if (event.key === 'ArrowRight') onMove(NEXT);
      else if (event.key === 'Escape') onBack();
      else return;
      event.preventDefault();
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onMove, onBack]);
}
