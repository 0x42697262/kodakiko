import { useImperativeHandle, useRef } from 'react';
import { View } from 'react-native';
import { runOnJS, useSharedValue } from 'react-native-reanimated';
import { Gallery, type GalleryRefType } from 'react-native-zoom-toolkit';
import { ExternalZoomContext, type MediaPagerProps } from './media-pager-shared';

const MAX_ZOOM = 6;
const UNZOOMED = 1;
const ZOOM_EPSILON = 0.01;

export function MediaPager<Item>({ items, keyOf, width, height, zoomed, onPageChange, onZoomChange, renderPage, handle }: MediaPagerProps<Item>) {
  const gallery = useRef<GalleryRefType>(null);
  const lastZoomed = useSharedValue(false);
  useImperativeHandle(handle, () => ({ goTo: (index: number) => gallery.current?.setIndex(index) }));
  return (
    <ExternalZoomContext.Provider value={{ external: true, zoomed }}>
      <View style={{ width, height }}>
        <Gallery
          ref={gallery}
          data={[...items]}
          keyExtractor={(item) => keyOf(item)}
          maxScale={MAX_ZOOM}
          onIndexChange={(index) => {
            lastZoomed.value = false;
            onZoomChange(false);
            onPageChange(index);
          }}
          onUpdate={(state) => {
            'worklet';
            const next = state.scale > UNZOOMED + ZOOM_EPSILON;
            if (next === lastZoomed.value) return;
            lastZoomed.value = next;
            runOnJS(onZoomChange)(next);
          }}
          renderItem={(item, index) => renderPage(item, index)}
        />
      </View>
    </ExternalZoomContext.Provider>
  );
}
