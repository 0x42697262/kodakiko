import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { SegmentedButtons } from 'react-native-paper';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { shutterAction, type CaptureMode, type ShutterEvent } from './shutter-action';
import { haptics } from '../../ui/haptics';

const MS_PER_SECOND = 1_000;

export function useCaptureMode(actions: {
  readonly takePhoto: () => void;
  readonly startRecording: () => void;
  readonly stopRecording: () => void;
  readonly recording: boolean;
}, initialMode: CaptureMode = 'photo') {
  const { services } = useAppContext();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const [mode, setMode] = useState<CaptureMode>(initialMode);
  const capTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const { takePhoto, startRecording, stopRecording, recording } = actions;

  useEffect(() => {
    if (!recording && capTimer.current !== null) {
      clearTimeout(capTimer.current);
      capTimer.current = null;
    }
  }, [recording]);

  const handle = useCallback(
    (event: ShutterEvent) => {
      switch (shutterAction(mode, event, settings.holdToRecordOn, recording, capTimer.current !== null)) {
        case 'photo':
          haptics.tick('shutter');
          takePhoto();
          return;
        case 'start':
          haptics.tick('recordStart');
          startRecording();
          return;
        case 'startCapped':
          haptics.tick('recordStart');
          startRecording();
          capTimer.current = setTimeout(stopRecording, settings.holdToRecordMaxSeconds * MS_PER_SECOND);
          return;
        case 'stop':
          if (capTimer.current !== null) {
            clearTimeout(capTimer.current);
            capTimer.current = null;
          }
          haptics.tick('recordStop');
          stopRecording();
          return;
        default:
          return;
      }
    },
    [mode, settings.holdToRecordOn, settings.holdToRecordMaxSeconds, recording, takePhoto, startRecording, stopRecording],
  );

  return {
    mode,
    setMode,
    onTap: () => handle('tap'),
    onHoldStart: () => handle('holdStart'),
    onHoldEnd: () => handle('holdEnd'),
  };
}

export function ModeSwitch({ mode, onChange, disabled }: { readonly mode: CaptureMode; readonly onChange: (mode: CaptureMode) => void; readonly disabled: boolean }) {
  return (
    <SegmentedButtons
      value={mode}
      onValueChange={(value) => onChange(value as CaptureMode)}
      buttons={[
        { value: 'photo', label: EN['capture.modePhoto'], icon: 'photo-camera', testID: 'capture.mode.photo', disabled },
        { value: 'video', label: EN['capture.modeVideo'], icon: 'videocam', testID: 'capture.mode.video', disabled },
      ]}
    />
  );
}
