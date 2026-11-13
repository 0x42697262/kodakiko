export const DOM_EVENTS = Object.freeze({
  paste: 'paste',
  dragOver: 'dragover',
  dragLeave: 'dragleave',
  drop: 'drop',
} as const);

export const CAPTURE_IDS = Object.freeze({
  screen: 'capture.screen',
  refusals: 'capture.refusals',
  removeItem: 'capture.removeItem',
  importing: 'capture.importing',
  timer: 'capture.timer',
  camera: 'capture.camera',
  discardPanel: 'capture.discardPanel',
} as const);
