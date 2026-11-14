import { RecordingPresets, requestRecordingPermissionsAsync, setAudioModeAsync, useAudioRecorder } from 'expo-audio';
import { useCallback, useEffect, useRef, useState } from 'react';
import { VOICE_MIME_ANDROID, VOICE_TICK_MS } from '../../constants/voice-note-constants';
import type { RecordedVoice, VoiceRecorder } from './voice-recorder';

export function useVoiceRecorder(): VoiceRecorder {
  const recorder = useAudioRecorder(RecordingPresets.HIGH_QUALITY);
  const [recording, setRecording] = useState(false);
  const [elapsedMs, setElapsedMs] = useState(0);
  const startedAt = useRef(0);

  useEffect(() => {
    if (!recording) return undefined;
    const timer = setInterval(() => setElapsedMs(Date.now() - startedAt.current), VOICE_TICK_MS);
    return () => clearInterval(timer);
  }, [recording]);

  const start = useCallback(async () => {
    const permission = await requestRecordingPermissionsAsync();
    if (!permission.granted) throw new Error(permission.status);
    await setAudioModeAsync({ allowsRecording: true });
    await recorder.prepareToRecordAsync();
    recorder.record();
    startedAt.current = Date.now();
    setElapsedMs(0);
    setRecording(true);
  }, [recorder]);

  const stop = useCallback(async (): Promise<RecordedVoice | null> => {
    const durationMs = Date.now() - startedAt.current;
    await recorder.stop();
    setRecording(false);
    await setAudioModeAsync({ allowsRecording: false });
    const uri = recorder.uri;
    if (uri === null) return null;
    return { input: { origin: 'camera', handle: { uri }, fileName: null, pickerExif: null }, durationMs, mimeType: VOICE_MIME_ANDROID };
  }, [recorder]);

  return { supported: true, recording, elapsedMs, start, stop };
}
