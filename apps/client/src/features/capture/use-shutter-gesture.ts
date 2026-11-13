import { useCallback, useRef } from 'react';
import { HOLD_TO_RECORD_MS } from '../../constants/capture-constants';

export type ShutterHandlers = {
  readonly onTap: () => void;
  readonly onHoldStart: () => void;
  readonly onHoldEnd: () => void;
};

export type ShutterGesture = {
  readonly onPressIn: () => void;
  readonly onPressOut: () => void;
};

export function useShutterGesture(handlers: ShutterHandlers): ShutterGesture {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const holding = useRef(false);

  const onPressIn = useCallback(() => {
    holding.current = false;
    timer.current = setTimeout(() => {
      timer.current = null;
      holding.current = true;
      handlers.onHoldStart();
    }, HOLD_TO_RECORD_MS);
  }, [handlers]);

  const onPressOut = useCallback(() => {
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    if (holding.current) {
      holding.current = false;
      handlers.onHoldEnd();
    } else {
      handlers.onTap();
    }
  }, [handlers]);

  return { onPressIn, onPressOut };
}
