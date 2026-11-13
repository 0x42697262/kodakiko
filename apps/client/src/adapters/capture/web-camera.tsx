import { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  RECORDING_TIMER_INTERVAL_MS,
  WEB_CAMERA_FACING,
  WEB_PHOTO_MIME_TYPE,
  WEB_PHOTO_QUALITY,
  WEB_RECORDER_MIME_TYPES,
} from '../../constants/capture-constants';
import { formatDuration } from '../../features/capture/format-duration';
import { ShutterButton } from '../../features/capture/ShutterButton';
import { CAPTURE_IDS } from '../../keys/capture-keys';
import { CANVAS_CONTEXT_2D, CANVAS_ELEMENT } from '../../keys/media-keys';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import type { MediaInput } from '../media/media-toolkit';

type WebCameraProps = { readonly onInputs: (inputs: readonly MediaInput[]) => void };
type Facing = keyof typeof WEB_CAMERA_FACING;

function cameraInput(blob: Blob): MediaInput {
  return { origin: 'camera', handle: { blob }, fileName: null, pickerExif: null };
}

async function openStream(facing: Facing): Promise<MediaStream> {
  const video = { facingMode: WEB_CAMERA_FACING[facing] };
  try {
    return await navigator.mediaDevices.getUserMedia({ video, audio: true });
  } catch {
    return navigator.mediaDevices.getUserMedia({ video });
  }
}

export function WebCamera({ onInputs }: WebCameraProps) {
  const { colors } = useTheme();
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const [facing, setFacing] = useState<Facing>('back');
  const [available, setAvailable] = useState(true);
  const [recordingMs, setRecordingMs] = useState<number | null>(null);

  useEffect(() => {
    if (navigator.mediaDevices?.getUserMedia === undefined) {
      setAvailable(false);
      return undefined;
    }
    let cancelled = false;
    openStream(facing).then(
      (stream) => {
        if (cancelled) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        streamRef.current = stream;
        setAvailable(true);
        if (videoRef.current !== null) videoRef.current.srcObject = stream;
      },
      () => {
        if (!cancelled) setAvailable(false);
      },
    );
    return () => {
      cancelled = true;
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
  }, [facing]);

  const takePhoto = useCallback(() => {
    const video = videoRef.current;
    if (video === null || video.videoWidth === 0) return;
    const canvas = document.createElement(CANVAS_ELEMENT);
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext(CANVAS_CONTEXT_2D)?.drawImage(video, 0, 0);
    canvas.toBlob(
      (blob) => {
        if (blob !== null) onInputs([cameraInput(blob)]);
      },
      WEB_PHOTO_MIME_TYPE,
      WEB_PHOTO_QUALITY,
    );
  }, [onInputs]);

  const startRecording = useCallback(() => {
    const stream = streamRef.current;
    if (stream === null) return;
    const mimeType = WEB_RECORDER_MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type));
    const recorder = new MediaRecorder(stream, mimeType === undefined ? undefined : { mimeType });
    const chunks: Blob[] = [];
    const startedAt = Date.now();
    const timer = setInterval(() => setRecordingMs(Date.now() - startedAt), RECORDING_TIMER_INTERVAL_MS);
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunks.push(event.data);
    };
    recorder.onstop = () => {
      clearInterval(timer);
      setRecordingMs(null);
      recorderRef.current = null;
      if (chunks.length > 0) onInputs([cameraInput(new Blob(chunks, { type: recorder.mimeType }))]);
    };
    recorderRef.current = recorder;
    setRecordingMs(0);
    recorder.start();
  }, [onInputs]);

  const stopRecording = useCallback(() => {
    recorderRef.current?.stop();
  }, []);

  if (!available) {
    return (
      <View style={styles.unavailable}>
        <Text testID={testId('capture.cameraUnavailable')} style={{ color: colors.text }}>
          {EN['capture.cameraUnavailable']}
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.camera}>
      <video ref={videoRef} autoPlay muted playsInline style={VIDEO_STYLE} />
      {recordingMs === null ? null : (
        <Text testID={CAPTURE_IDS.timer} style={[styles.timer, { color: colors.danger }]}>
          {formatDuration(recordingMs)}
        </Text>
      )}
      <View style={styles.controls}>
        <ShutterButton
          recording={recordingMs !== null}
          onTap={takePhoto}
          onHoldStart={startRecording}
          onHoldEnd={stopRecording}
        />
        <IconAction
          testID={testId('capture.flip')}
          icon="flip"
          label={EN['capture.flip']}
          disabled={recordingMs !== null}
          onPress={() => setFacing((current) => (current === 'back' ? 'front' : 'back'))}
        />
      </View>
    </View>
  );
}

const VIDEO_STYLE = { width: '100%', flex: 1, minHeight: 0, objectFit: 'cover' } as const;

const styles = StyleSheet.create({
  camera: { flex: 1, gap: SPACING.md },
  unavailable: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  controls: { alignItems: 'center', gap: SPACING.md },
  timer: { fontSize: FONT_SIZE.headline, fontWeight: '600', textAlign: 'center' },
});
