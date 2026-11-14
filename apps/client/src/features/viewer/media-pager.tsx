import { useImperativeHandle, useRef } from 'react';
import { FlatList, type NativeScrollEvent, type NativeSyntheticEvent } from 'react-native';
import type { MediaPagerProps } from './media-pager-shared';

export function MediaPager<Item>({ items, keyOf, width, zoomed, onPageChange, renderPage, handle }: MediaPagerProps<Item>) {
  const list = useRef<FlatList<Item>>(null);
  useImperativeHandle(handle, () => ({
    goTo: (index: number) => list.current?.scrollToIndex({ index, animated: true }),
  }));
  function onScrollEnd(event: NativeSyntheticEvent<NativeScrollEvent>): void {
    if (width === 0) return;
    onPageChange(Math.round(event.nativeEvent.contentOffset.x / width));
  }
  return (
    <FlatList
      ref={list}
      data={items}
      horizontal
      pagingEnabled
      scrollEnabled={!zoomed}
      showsHorizontalScrollIndicator={false}
      keyExtractor={keyOf}
      onMomentumScrollEnd={onScrollEnd}
      onScrollEndDrag={onScrollEnd}
      getItemLayout={(_data, index) => ({ length: width, offset: width * index, index })}
      renderItem={({ item, index }) => renderPage(item, index)}
    />
  );
}
