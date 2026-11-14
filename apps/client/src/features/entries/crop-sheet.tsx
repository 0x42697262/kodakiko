import { Image } from 'expo-image';
import { useMemo, useRef, useState } from 'react';
import { PanResponder, StyleSheet, View, type LayoutChangeEvent } from 'react-native';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { OVERLAY_ELEVATION, OVERLAY_Z_INDEX } from '../../constants/gallery-constants';
import { SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { dragCorner, FULL_CROP, moveCrop, type Corner, type CropRect } from './crop-math';

const HANDLE = 28;
const HALF = 2;
const CORNERS: readonly Corner[] = ['topLeft', 'topRight', 'bottomLeft', 'bottomRight'];
const SHADE = 'rgba(0,0,0,0.55)';
const LINE = 2;

type Frame = { readonly left: number; readonly top: number; readonly width: number; readonly height: number };

function fitFrame(boxWidth: number, boxHeight: number, imageWidth: number, imageHeight: number): Frame {
  const scale = Math.min(boxWidth / imageWidth, boxHeight / imageHeight);
  const width = imageWidth * scale;
  const height = imageHeight * scale;
  return { left: (boxWidth - width) / HALF, top: (boxHeight - height) / HALF, width, height };
}

export function CropSheet({
  uri,
  imageWidth,
  imageHeight,
  busy,
  failed,
  onApply,
  onCancel,
}: {
  readonly uri: string;
  readonly imageWidth: number;
  readonly imageHeight: number;
  readonly busy: boolean;
  readonly failed: boolean;
  readonly onApply: (rect: CropRect) => void;
  readonly onCancel: () => void;
}) {
  const { colors } = useTheme();
  const [box, setBox] = useState({ width: 0, height: 0 });
  const [rect, setRect] = useState<CropRect>(FULL_CROP);
  const rectRef = useRef(rect);
  rectRef.current = rect;
  const start = useRef(rect);
  const frame = box.width === 0 ? null : fitFrame(box.width, box.height, imageWidth, imageHeight);
  const frameRef = useRef(frame);
  frameRef.current = frame;

  const body = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onPanResponderGrant: () => {
          start.current = rectRef.current;
        },
        onPanResponderMove: (_event, gesture) => {
          const current = frameRef.current;
          if (current === null) return;
          setRect(moveCrop(start.current, gesture.dx / current.width, gesture.dy / current.height));
        },
      }),
    [],
  );

  const handles = useMemo(
    () =>
      Object.fromEntries(
        CORNERS.map((corner) => [
          corner,
          PanResponder.create({
            onStartShouldSetPanResponder: () => true,
            onPanResponderGrant: () => {
              start.current = rectRef.current;
            },
            onPanResponderMove: (_event, gesture) => {
              const current = frameRef.current;
              if (current === null) return;
              const base = start.current;
              const left = corner === 'topLeft' || corner === 'bottomLeft';
              const top = corner === 'topLeft' || corner === 'topRight';
              const pointerX = (left ? base.x : base.x + base.width) + gesture.dx / current.width;
              const pointerY = (top ? base.y : base.y + base.height) + gesture.dy / current.height;
              setRect(dragCorner(base, corner, pointerX, pointerY));
            },
          }),
        ]),
      ) as Record<Corner, ReturnType<typeof PanResponder.create>>,
    [],
  );

  return (
    <View testID="crop.sheet" style={[styles.overlay, { backgroundColor: colors.background }]}>
      <View style={styles.box} onLayout={(event: LayoutChangeEvent) => setBox({ width: event.nativeEvent.layout.width, height: event.nativeEvent.layout.height })}>
        {frame === null ? null : (
          <View style={[styles.frame, { left: frame.left, top: frame.top, width: frame.width, height: frame.height }]}>
            <Image source={{ uri }} style={StyleSheet.absoluteFill} contentFit="fill" />
            <View pointerEvents="none" style={[styles.shade, { left: 0, top: 0, right: 0, height: rect.y * frame.height }]} />
            <View pointerEvents="none" style={[styles.shade, { left: 0, bottom: 0, right: 0, height: (1 - rect.y - rect.height) * frame.height }]} />
            <View pointerEvents="none" style={[styles.shade, { left: 0, top: rect.y * frame.height, width: rect.x * frame.width, height: rect.height * frame.height }]} />
            <View pointerEvents="none" style={[styles.shade, { right: 0, top: rect.y * frame.height, width: (1 - rect.x - rect.width) * frame.width, height: rect.height * frame.height }]} />
            <View
              testID="crop.area"
              {...body.panHandlers}
              style={[styles.area, { borderColor: colors.onPrimary, left: rect.x * frame.width, top: rect.y * frame.height, width: rect.width * frame.width, height: rect.height * frame.height }]}
            />
            {CORNERS.map((corner) => {
              const left = corner === 'topLeft' || corner === 'bottomLeft' ? rect.x : rect.x + rect.width;
              const top = corner === 'topLeft' || corner === 'topRight' ? rect.y : rect.y + rect.height;
              return (
                <View
                  key={corner}
                  testID={`crop.${corner}`}
                  accessibilityLabel={EN['crop.handle'](corner)}
                  {...handles[corner].panHandlers}
                  style={[styles.handle, { backgroundColor: colors.primary, left: left * frame.width - HANDLE / HALF, top: top * frame.height - HANDLE / HALF }]}
                />
              );
            })}
          </View>
        )}
      </View>
      {failed ? <Banner testID="crop.failed" kind="error" message={EN['crop.failed']} /> : null}
      <View style={styles.actions}>
        <Button testID="crop.cancel" label={EN['crop.cancel']} variant="secondary" disabled={busy} onPress={onCancel} />
        <Button testID="crop.apply" label={EN['crop.apply']} busy={busy} onPress={() => onApply(rect)} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: { ...StyleSheet.absoluteFill, zIndex: OVERLAY_Z_INDEX, elevation: OVERLAY_ELEVATION, padding: SPACING.lg, gap: SPACING.md },
  box: { flex: 1 },
  frame: { position: 'absolute', userSelect: 'none' },
  shade: { position: 'absolute', backgroundColor: SHADE },
  area: { position: 'absolute', borderWidth: LINE },
  handle: { position: 'absolute', width: HANDLE, height: HANDLE, borderRadius: HANDLE },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: SPACING.md },
});
