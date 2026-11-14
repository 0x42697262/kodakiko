import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAccountState } from '../src/composition/app-context';
import { ROUTE_BY_STATE } from '../src/composition/route-by-state';
import { CaptureScreen } from '../src/features/capture/CaptureScreen';
import { useIndexedEntry } from '../src/features/gallery/hooks';
import { entryRoute } from '../src/keys/routes';

export default function CaptureRoute() {
  const state = useAccountState();
  const { entryId, mode } = useLocalSearchParams<{ entryId?: string; mode?: string }>();
  const initialMode = mode === 'video' ? 'video' : 'photo';
  const target = useIndexedEntry(entryId ?? '');
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  if (entryId === undefined) return <CaptureScreen />;
  if (target === null) return null;
  if (!target.isOwn || target.trashed) return <Redirect href={entryRoute(entryId)} />;
  return <CaptureScreen addToEntry={{ entryId, currentCount: target.mediaCount }} initialMode={initialMode} />;
}
