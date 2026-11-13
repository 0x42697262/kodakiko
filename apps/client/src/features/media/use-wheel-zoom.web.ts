import { useEffect, type ComponentRef, type RefObject } from 'react';
import type { View } from 'react-native';
import type { WheelZoomHandler } from './use-wheel-zoom';

export function useWheelZoom(ref: RefObject<ComponentRef<typeof View> | null>, onZoom: WheelZoomHandler): void {
  useEffect(() => {
    const element = ref.current as unknown as HTMLElement | null;
    if (element === null) return undefined;
    function onWheel(event: WheelEvent): void {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      const bounds = element?.getBoundingClientRect();
      onZoom(event.deltaY, event.clientX - (bounds?.left ?? 0), event.clientY - (bounds?.top ?? 0));
    }
    element.addEventListener('wheel', onWheel, { passive: false });
    return () => element.removeEventListener('wheel', onWheel);
  }, [ref, onZoom]);
}
