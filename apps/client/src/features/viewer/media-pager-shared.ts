import { createContext, type ReactElement, type RefObject } from 'react';

export type PagerHandle = { readonly goTo: (index: number) => void };

export type MediaPagerProps<Item> = {
  readonly items: readonly Item[];
  readonly keyOf: (item: Item) => string;
  readonly width: number;
  readonly height: number;
  readonly zoomed: boolean;
  readonly onPageChange: (index: number) => void;
  readonly onZoomChange: (zoomed: boolean) => void;
  readonly renderPage: (item: Item, index: number) => ReactElement;
  readonly handle: RefObject<PagerHandle | null>;
};

export const ExternalZoomContext = createContext<{ readonly external: boolean; readonly zoomed: boolean }>({ external: false, zoomed: false });
