import { Image } from 'expo-image';
import { useCallback, useEffect, useRef, useState, type ComponentRef } from 'react';
import { StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';
import { TEST_IDS, listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { DOUBLE_TAP_COUNT, HALF, WHEEL_ZOOM_SENSITIVITY, ZOOM_ANIMATION_MS, ZOOM_DOUBLE_TAP, ZOOM_UNZOOMED } from './constants';
import { useWheelZoom } from './use-wheel-zoom';
import { clampScale, clampTranslate, maxTranslate, settleScale, zoomAround } from './zoom-math';

export type ZoomablePhotoProps = {
  readonly uri: string;
  readonly position: number;
  readonly viewportWidth: number;
  readonly viewportHeight: number;
  readonly imageWidth: number;
  readonly imageHeight: number;
  readonly active: boolean;
  readonly onZoomChange: (zoomed: boolean) => void;
};

function fitted(imageWidth: number, imageHeight: number, width: number, height: number) {
  'worklet';
  const ratio = Math.min(width / imageWidth, height / imageHeight);
  return { width: imageWidth * ratio, height: imageHeight * ratio };
}

export function ZoomablePhoto({
  uri,
  position,
  viewportWidth,
  viewportHeight,
  imageWidth,
  imageHeight,
  active,
  onZoomChange,
}: ZoomablePhotoProps) {
  const scale = useSharedValue(ZOOM_UNZOOMED);
  const savedScale = useSharedValue(ZOOM_UNZOOMED);
  const translateX = useSharedValue(0);
  const translateY = useSharedValue(0);
  const savedTranslateX = useSharedValue(0);
  const savedTranslateY = useSharedValue(0);
  const [zoomed, setZoomed] = useState(false);
  const container = useRef<ComponentRef<typeof View> | null>(null);
  const size = fitted(imageWidth, imageHeight, viewportWidth, viewportHeight);
  const centerX = viewportWidth / HALF;
  const centerY = viewportHeight / HALF;

  const report = useCallback(
    (next: boolean) => {
      setZoomed(next);
      onZoomChange(next);
    },
    [onZoomChange],
  );

  const reset = useCallback(() => {
    scale.value = withTiming(ZOOM_UNZOOMED, { duration: ZOOM_ANIMATION_MS });
    translateX.value = withTiming(0, { duration: ZOOM_ANIMATION_MS });
    translateY.value = withTiming(0, { duration: ZOOM_ANIMATION_MS });
    savedScale.value = ZOOM_UNZOOMED;
    report(false);
  }, [report, scale, savedScale, translateX, translateY]);

  useEffect(() => {
    if (!active && zoomed) reset();
  }, [active, zoomed, reset]);

  function applyZoom(next: number, focalX: number, focalY: number): void {
    'worklet';
    const ratio = next / scale.value;
    translateX.value = clampTranslate(
      zoomAround(translateX.value, focalX, centerX, ratio),
      maxTranslate(size.width, viewportWidth, next),
    );
    translateY.value = clampTranslate(
      zoomAround(translateY.value, focalY, centerY, ratio),
      maxTranslate(size.height, viewportHeight, next),
    );
    scale.value = next;
  }

  const pinch = Gesture.Pinch()
    .onStart(() => {
      savedScale.value = scale.value;
    })
    .onUpdate((event) => {
      applyZoom(clampScale(savedScale.value * event.scale), event.focalX, event.focalY);
    })
    .onEnd(() => {
      const settled = settleScale(scale.value);
      if (settled === ZOOM_UNZOOMED) {
        scale.value = withTiming(ZOOM_UNZOOMED, { duration: ZOOM_ANIMATION_MS });
        translateX.value = withTiming(0, { duration: ZOOM_ANIMATION_MS });
        translateY.value = withTiming(0, { duration: ZOOM_ANIMATION_MS });
      }
      scheduleOnRN(report, settled > ZOOM_UNZOOMED);
    });

  const pan = Gesture.Pan()
    .minPointers(1)
    .enabled(zoomed)
    .onStart(() => {
      savedTranslateX.value = translateX.value;
      savedTranslateY.value = translateY.value;
    })
    .onUpdate((event) => {
      translateX.value = clampTranslate(
        savedTranslateX.value + event.translationX,
        maxTranslate(size.width, viewportWidth, scale.value),
      );
      translateY.value = clampTranslate(
        savedTranslateY.value + event.translationY,
        maxTranslate(size.height, viewportHeight, scale.value),
      );
    });

  const doubleTap = Gesture.Tap()
    .numberOfTaps(DOUBLE_TAP_COUNT)
    .onEnd((event) => {
      if (scale.value > ZOOM_UNZOOMED) {
        scale.value = withTiming(ZOOM_UNZOOMED, { duration: ZOOM_ANIMATION_MS });
        translateX.value = withTiming(0, { duration: ZOOM_ANIMATION_MS });
        translateY.value = withTiming(0, { duration: ZOOM_ANIMATION_MS });
        scheduleOnRN(report, false);
        return;
      }
      const ratio = ZOOM_DOUBLE_TAP / scale.value;
      const nextX = clampTranslate(
        zoomAround(0, event.x, centerX, ratio),
        maxTranslate(size.width, viewportWidth, ZOOM_DOUBLE_TAP),
      );
      const nextY = clampTranslate(
        zoomAround(0, event.y, centerY, ratio),
        maxTranslate(size.height, viewportHeight, ZOOM_DOUBLE_TAP),
      );
      scale.value = withTiming(ZOOM_DOUBLE_TAP, { duration: ZOOM_ANIMATION_MS });
      translateX.value = withTiming(nextX, { duration: ZOOM_ANIMATION_MS });
      translateY.value = withTiming(nextY, { duration: ZOOM_ANIMATION_MS });
      scheduleOnRN(report, true);
    });

  const onWheel = useCallback(
    (deltaY: number, offsetX: number, offsetY: number) => {
      const next = clampScale(scale.value * (1 - deltaY * WHEEL_ZOOM_SENSITIVITY));
      applyZoom(next, offsetX, offsetY);
      report(next > ZOOM_UNZOOMED);
    },
    [report, scale],
  );
  useWheelZoom(container, onWheel);

  const animated = useAnimatedStyle(() => ({
    transform: [{ translateX: translateX.value }, { translateY: translateY.value }, { scale: scale.value }],
  }));

  const label = zoomed ? EN['viewer.photoZoomedLabel'](position + 1) : EN['viewer.photoLabel'](position + 1);

  return (
    <GestureDetector gesture={Gesture.Race(doubleTap, Gesture.Simultaneous(pinch, pan))}>
      <View
        ref={container}
        testID={listTestId(TEST_IDS.viewerZoomable, position)}
        accessibilityLabel={label}
        accessibilityHint={EN['viewer.zoomHint']}
        style={[styles.viewport, { width: viewportWidth, height: viewportHeight }]}
      >
        <Animated.View style={[styles.center, StyleSheet.absoluteFill, animated]}>
          <Image source={{ uri }} style={{ width: size.width, height: size.height }} contentFit="contain" transition={0} />
        </Animated.View>
      </View>
    </GestureDetector>
  );
}

const styles = StyleSheet.create({
  viewport: { overflow: 'hidden' },
  center: { alignItems: 'center', justifyContent: 'center' },
});
