import './zod-config.ts';
import { APP_ROOT_ID } from './keys.ts';
import { startViewer } from './viewer-app.ts';

void startViewer({
  root: document.getElementById(APP_ROOT_ID) ?? document.body,
  location: window.location,
  history: window.history,
  now: () => new Date(),
});
