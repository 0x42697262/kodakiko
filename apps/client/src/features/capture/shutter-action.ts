export type CaptureMode = 'photo' | 'video';
export type ShutterEvent = 'tap' | 'holdStart' | 'holdEnd';
export type ShutterAction = 'photo' | 'start' | 'startCapped' | 'stop' | 'none';

export function shutterAction(mode: CaptureMode, event: ShutterEvent, holdToRecord: boolean, recording: boolean): ShutterAction {
  if (mode === 'video') {
    if (event === 'holdEnd') return 'none';
    return recording ? 'stop' : 'start';
  }
  if (event === 'tap') return 'photo';
  if (!holdToRecord) return event === 'holdStart' ? 'photo' : 'none';
  if (event === 'holdStart') return recording ? 'none' : 'startCapped';
  return recording ? 'stop' : 'none';
}
