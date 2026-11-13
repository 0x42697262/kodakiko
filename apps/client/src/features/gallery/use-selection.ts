import { useCallback, useState } from 'react';
import { selectRange, toggleSelected } from './selection';
import type { TileModifiers } from './tile';

export type Selection = {
  readonly selecting: boolean;
  readonly selected: ReadonlySet<string>;
  readonly start: (entryId: string) => void;
  readonly tap: (entryId: string, modifiers: TileModifiers, order: readonly string[]) => boolean;
  readonly clear: () => void;
  readonly selectAll: (order: readonly string[]) => void;
};

const NOTHING: ReadonlySet<string> = new Set();

export function useSelection(): Selection {
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<ReadonlySet<string>>(NOTHING);
  const [anchor, setAnchor] = useState<string | null>(null);

  const start = useCallback((entryId: string) => {
    setSelecting(true);
    setSelected(new Set([entryId]));
    setAnchor(entryId);
  }, []);

  const tap = useCallback(
    (entryId: string, modifiers: TileModifiers, order: readonly string[]): boolean => {
      if (!selecting) {
        if (!modifiers.toggle && !modifiers.range) return false;
        start(entryId);
        return true;
      }
      setSelected((current) =>
        modifiers.range ? selectRange(current, order, anchor, entryId) : toggleSelected(current, entryId),
      );
      setAnchor(entryId);
      return true;
    },
    [selecting, anchor, start],
  );

  const clear = useCallback(() => {
    setSelecting(false);
    setSelected(NOTHING);
    setAnchor(null);
  }, []);

  const selectAll = useCallback((order: readonly string[]) => {
    setSelecting(true);
    setSelected(new Set(order));
  }, []);

  return { selecting, selected, start, tap, clear, selectAll };
}
