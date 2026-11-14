import { Image } from 'expo-image';
import { useContext } from 'react';
import { StyleSheet, View } from 'react-native';
import { listTestId, TEST_IDS } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { ExternalZoomContext } from '../viewer/media-pager-shared';
import type { ZoomablePhotoProps } from './zoomable-photo';

export function PhotoSurface({ uri, position, viewportWidth, viewportHeight, imageWidth, imageHeight }: ZoomablePhotoProps) {
  const { zoomed } = useContext(ExternalZoomContext);
  const ratio = Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight);
  const label = zoomed ? EN['viewer.photoZoomedLabel'](position + 1) : EN['viewer.photoLabel'](position + 1);
  return (
    <View
      testID={listTestId(TEST_IDS.viewerZoomable, position)}
      accessibilityLabel={label}
      accessibilityHint={EN['viewer.zoomHint']}
      style={[styles.viewport, { width: viewportWidth, height: viewportHeight }]}
    >
      <Image source={{ uri }} style={{ width: imageWidth * ratio, height: imageHeight * ratio }} contentFit="contain" transition={0} />
    </View>
  );
}

const styles = StyleSheet.create({ viewport: { alignItems: 'center', justifyContent: 'center' } });
