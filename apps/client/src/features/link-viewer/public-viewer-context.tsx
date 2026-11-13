import type { Sodium } from '@kodakiko/core';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { createHttpClient, type HttpClient } from '../../adapters/http/http';
import { loadSodium } from '../../adapters/sodium/sodium';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';

export type PublicViewerContext = { readonly sodium: Sodium; readonly http: HttpClient };

const Context = createContext<PublicViewerContext | null>(null);

export function PublicViewerProvider({ children }: { readonly children: ReactNode }) {
  const [value, setValue] = useState<PublicViewerContext | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadSodium()
      .then((sodium) => {
        if (cancelled) return;
        const http = createHttpClient({
          baseUrl: async () => window.location.origin,
          sessionToken: async () => null,
          onSessionInvalid: () => undefined,
        });
        setValue({ sodium, http });
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);
  if (value === null) {
    return (
      <Screen testID="linkViewer.loading">
        <Spinner testID="linkViewer.spinner" />
      </Screen>
    );
  }
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function usePublicViewer(): PublicViewerContext {
  const value = useContext(Context);
  if (value === null) throw new Error('PublicViewerProvider missing');
  return value;
}
