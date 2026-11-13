import type { ComponentRef, RefObject } from 'react';
import type { View } from 'react-native';

export type WheelZoomHandler = (deltaY: number, offsetX: number, offsetY: number) => void;

export function useWheelZoom(_ref: RefObject<ComponentRef<typeof View> | null>, _onZoom: WheelZoomHandler): void {}
