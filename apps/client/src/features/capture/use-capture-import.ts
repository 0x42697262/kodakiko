import { useCallback, useRef, useState, useSyncExternalStore } from 'react';
import type { MediaInput } from '../../adapters/media/media-toolkit';
import { useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { LOCATION_TIMEOUT_MS } from '../../constants/capture-constants';
import type { ImportedMedia } from '../../services/media/import-service';
import { inputLabel, refusalMessage } from './refusals';

export type CaptureImport = {
  readonly items: readonly ImportedMedia[];
  readonly pending: number;
  readonly messages: readonly string[];
  readonly maxMediaPerEntry: number;
  readonly addInputs: (inputs: readonly MediaInput[]) => void;
  readonly remove: (mediaId: string) => void;
};

export function useCaptureImport(): CaptureImport {
  const { services } = useAppContext();
  const tray = services.captureTray;
  const items = useSyncExternalStore(tray.subscribe, () => tray.items);
  const [pending, setPending] = useState(0);
  const [messages, setMessages] = useState<readonly string[]>([]);
  const queue = useRef<Promise<void>>(Promise.resolve());
  const locationRequested = useRef(false);
  const maxMediaPerEntry = (services.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;

  const requestLocationOnce = useCallback(() => {
    if (locationRequested.current || !services.deviceSettings.get().locationForCaptures) return;
    locationRequested.current = true;
    services.location
      .currentPosition(LOCATION_TIMEOUT_MS)
      .then((location) => tray.setDeviceLocation(location))
      .catch(() => undefined);
  }, [services, tray]);

  const importOne = useCallback(
    async (input: MediaInput, generation: number): Promise<void> => {
      const limit = (services.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;
      const outcome = await services.mediaImport.importOne(input, {
        used: tray.items.length,
        limit,
      });
      if (outcome.ok) {
        await tray.addImported(outcome.media, generation);
      } else {
        const message = refusalMessage(outcome.reason, inputLabel(input), limit);
        setMessages((current) => (current.includes(message) ? current : [...current, message]));
      }
      setPending((current) => current - 1);
    },
    [services, tray],
  );

  const addInputs = useCallback(
    (inputs: readonly MediaInput[]) => {
      if (inputs.length === 0) return;
      setMessages([]);
      setPending((current) => current + inputs.length);
      if (inputs.some((input) => input.origin === 'camera')) requestLocationOnce();
      const generation = tray.generation;
      for (const input of inputs) {
        queue.current = queue.current.then(() => importOne(input, generation));
      }
    },
    [importOne, requestLocationOnce, tray],
  );

  const remove = useCallback(
    (mediaId: string) => {
      tray.remove(mediaId).catch(() => undefined);
    },
    [tray],
  );

  return { items, pending, messages, maxMediaPerEntry, addInputs, remove };
}
