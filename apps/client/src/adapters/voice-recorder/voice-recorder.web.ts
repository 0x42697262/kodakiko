import { useCallback, useEffect, useRef, useState } from 'react';
import { VOICE_MIME_WEB, VOICE_TICK_MS } from '../../constants/voice-note-constants';
import type { RecordedVoice, VoiceRecorder } from './voice-recorder';

const OPUS_MIME = 'audio/webm;codecs=opus';
const DATA_EVENT = 'dataavailable';
const STOP_EVENT = 'stop';

function isSupported(): boolean {
  return typeof MediaRecorder !== 'undefined' && typeof navigator.mediaDevices?.getUserMedia === 'function';
}

export function useVoiceRecorder(): VoiceRecorder {
  const [recording, setRecording] = useState(false);
  const [elapsedMs, setElapsedMs] = useState(0);
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const startedAt = useRef(0);

  useEffect(() => {
    if (!recording) return undefined;
    const timer = setInterval(() => setElapsedMs(Date.now() - startedAt.current), VOICE_TICK_MS);
    return () => clearInterval(timer);
  }, [recording]);

  useEffect(
    () => () => {
      recorder.current?.stream.getTracks().forEach((track) => track.stop());
    },
    [],
  );

  const start = useCallback(async () => {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const options = MediaRecorder.isTypeSupported(OPUS_MIME) ? { mimeType: OPUS_MIME } : undefined;
    const next = new MediaRecorder(stream, options);
    chunks.current = [];
    next.addEventListener(DATA_EVENT, (event) => chunks.current.push(event.data));
    next.start();
    recorder.current = next;
    startedAt.current = Date.now();
    setElapsedMs(0);
    setRecording(true);
  }, []);

  const stop = useCallback(async (): Promise<RecordedVoice | null> => {
    const current = recorder.current;
    if (current === null) return null;
    const durationMs = Date.now() - startedAt.current;
    await new Promise<void>((resolve) => {
      current.addEventListener(STOP_EVENT, () => resolve(), { once: true });
      current.stop();
    });
    current.stream.getTracks().forEach((track) => track.stop());
    recorder.current = null;
    setRecording(false);
    const blob = new Blob(chunks.current, { type: VOICE_MIME_WEB });
    chunks.current = [];
    return { input: { origin: 'camera', handle: { blob }, fileName: null, pickerExif: null }, durationMs, mimeType: VOICE_MIME_WEB };
  }, []);

  return { supported: isSupported(), recording, elapsedMs, start, stop };
}
