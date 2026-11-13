import * as ImagePicker from 'expo-image-picker';
import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
  useMicrophonePermission,
  usePhotoOutput,
  useVideoOutput,
} from 'react-native-vision-camera';
import { useAppContext } from '../../composition/app-context';
import {
  CAMERA_PHOTO_QUALITY,
  CAMERA_POSITIONS,
  FILE_URI_PREFIX,
  PICKED_MEDIA_TYPES,
  PICKER_QUALITY,
  RECORDING_TIMER_INTERVAL_MS,
} from '../../constants/capture-constants';
import { formatDuration } from '../../features/capture/format-duration';
import { ShutterButton } from '../../features/capture/ShutterButton';
import { CAPTURE_IDS } from '../../keys/capture-keys';
import { testId } from '../../keys/test-ids';
import { maxPlaintextBytes } from '../../services/media/max-recording-bytes';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import type { MediaInput } from '../media/media-toolkit';
import type { CaptureSurfaceProps } from './capture-surface';

type Position = (typeof CAMERA_POSITIONS)[keyof typeof CAMERA_POSITIONS];
type StoppableRecorder = { readonly stopRecording: () => Promise<void> | void };

function cameraInput(filePath: string): MediaInput {
  return {
    origin: 'camera',
    handle: { uri: `${FILE_URI_PREFIX}${filePath}` },
    fileName: null,
    pickerExif: null,
  };
}

function useCameraActive(): boolean {
  const { lifecycle } = useAppContext();
  const [focused, setFocused] = useState(false);
  const [foreground, setForeground] = useState(true);
  useFocusEffect(
    useCallback(() => {
      setFocused(true);
      return () => setFocused(false);
    }, []),
  );
  useEffect(() => lifecycle.subscribeActive(setForeground), [lifecycle]);
  return focused && foreground;
}

function PermissionGate({
  canRequest,
  onRequest,
}: {
  readonly canRequest: boolean;
  readonly onRequest: () => void;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.gate}>
      <Text testID={testId('capture.cameraPermissionBody')} style={{ color: colors.text }}>
        {EN['capture.cameraPermissionBody']}
      </Text>
      {canRequest ? (
        <Button testID={testId('capture.allowCamera')} label={EN['capture.allowCamera']} onPress={onRequest} />
      ) : (
        <Button
          testID={testId('capture.openSettings')}
          label={EN['capture.openSettings']}
          onPress={() => void Linking.openSettings()}
        />
      )}
    </View>
  );
}

function CameraSurface({ onInputs, remainingSlots, maxUploadBytes }: CaptureSurfaceProps) {
  const { colors } = useTheme();
  const active = useCameraActive();
  const microphone = useMicrophonePermission();
  const [position, setPosition] = useState<Position>(CAMERA_POSITIONS.back);
  const device = useCameraDevice(position);
  const photoOutput = usePhotoOutput({ containerFormat: 'jpeg', quality: CAMERA_PHOTO_QUALITY });
  const videoOutput = useVideoOutput({ enableAudio: true, fileType: 'mp4' });
  const recorderRef = useRef<StoppableRecorder | null>(null);
  const [recordingMs, setRecordingMs] = useState<number | null>(null);

  const takePhoto = useCallback(() => {
    photoOutput
      .capturePhotoToFile({ flashMode: 'off', enableShutterSound: false }, {})
      .then((file) => onInputs([cameraInput(file.filePath)]))
      .catch(() => undefined);
  }, [photoOutput, onInputs]);

  const startRecording = useCallback(() => {
    const begin = async (): Promise<void> => {
      if (!microphone.hasPermission && !(await microphone.requestPermission())) return;
      const recorder = await videoOutput.createRecorder({
        maxFileSize: maxPlaintextBytes(maxUploadBytes),
      });
      const startedAt = Date.now();
      const timer = setInterval(
        () => setRecordingMs(Date.now() - startedAt),
        RECORDING_TIMER_INTERVAL_MS,
      );
      const finish = (): void => {
        clearInterval(timer);
        recorderRef.current = null;
        setRecordingMs(null);
      };
      recorderRef.current = recorder;
      setRecordingMs(0);
      await recorder.startRecording(
        (filePath) => {
          finish();
          onInputs([cameraInput(filePath)]);
        },
        finish,
      );
    };
    begin().catch(() => setRecordingMs(null));
  }, [microphone, videoOutput, maxUploadBytes, onInputs]);

  const stopRecording = useCallback(() => {
    void recorderRef.current?.stopRecording();
  }, []);

  const pickFromGallery = useCallback(() => {
    const pick = async (): Promise<void> => {
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: [...PICKED_MEDIA_TYPES],
        allowsMultipleSelection: true,
        selectionLimit: remainingSlots,
        exif: true,
        quality: PICKER_QUALITY,
      });
      if (result.canceled) return;
      onInputs(
        result.assets.map((asset): MediaInput => ({
          origin: 'picker',
          handle: { uri: asset.uri },
          fileName: asset.fileName ?? null,
          pickerExif: asset.exif ?? null,
        })),
      );
    };
    pick().catch(() => undefined);
  }, [remainingSlots, onInputs]);

  return (
    <View style={styles.camera}>
      {device === undefined ? null : (
        <View testID={CAPTURE_IDS.camera} style={StyleSheet.absoluteFill}>
          <Camera
            style={StyleSheet.absoluteFill}
            isActive={active}
            device={device}
            outputs={[photoOutput, videoOutput]}
          />
        </View>
      )}
      {recordingMs === null ? null : (
        <Text testID={CAPTURE_IDS.timer} style={[styles.timer, { color: colors.danger }]}>
          {formatDuration(recordingMs)}
        </Text>
      )}
      <View style={styles.controls}>
        <Button
          testID={testId('capture.pickFromGallery')}
          label={EN['capture.pickFromGallery']}
          variant="secondary"
          disabled={recordingMs !== null}
          onPress={pickFromGallery}
        />
        <ShutterButton
          recording={recordingMs !== null}
          onTap={takePhoto}
          onHoldStart={startRecording}
          onHoldEnd={stopRecording}
        />
        <Button
          testID={testId('capture.flip')}
          label={EN['capture.flip']}
          variant="secondary"
          disabled={recordingMs !== null}
          onPress={() =>
            setPosition((current) =>
              current === CAMERA_POSITIONS.back ? CAMERA_POSITIONS.front : CAMERA_POSITIONS.back,
            )
          }
        />
      </View>
    </View>
  );
}

export function CaptureSurface(props: CaptureSurfaceProps) {
  const permission = useCameraPermission();
  if (!permission.hasPermission) {
    return (
      <PermissionGate
        canRequest={permission.canRequestPermission}
        onRequest={() => void permission.requestPermission()}
      />
    );
  }
  return <CameraSurface {...props} />;
}

const styles = StyleSheet.create({
  gate: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: SPACING.md },
  camera: { flex: 1, justifyContent: 'flex-end' },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: SPACING.lg,
  },
  timer: {
    position: 'absolute',
    top: SPACING.lg,
    alignSelf: 'center',
    fontSize: FONT_SIZE.headline,
    fontWeight: '600',
  },
});
