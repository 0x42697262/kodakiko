export type CaptureMode = 'photo' | 'video';
export type ShutterEvent = 'tap' | 'holdStart' | 'holdEnd';
export type ShutterAction = 'photo' | 'start' | 'startCapped' | 'stop' | 'none';

export function shutterAction(
  mode: CaptureMode,
  event: ShutterEvent,
  holdToRecord: boolean,
  recording: boolean,
  heldRecording: boolean,
): ShutterAction {
  if (event === 'tap') {
    if (mode === 'photo') return 'photo';
    return recording ? 'stop' : 'start';
  }
  if (!holdToRecord) {
    if (event === 'holdEnd') return 'none';
    if (mode === 'photo') return 'photo';
    return recording ? 'stop' : 'start';
  }
  if (event === 'holdStart') return recording ? 'none' : 'startCapped';
  return heldRecording ? 'stop' : 'none';
}
