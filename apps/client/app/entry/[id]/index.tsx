import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAccountState } from '../../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../../src/composition/route-by-state';
import { EntryViewerScreen } from '../../../src/features/viewer/EntryViewerScreen';

export default function EntryRoute() {
  const state = useAccountState();
  const { id } = useLocalSearchParams<{ id?: string }>();
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  if (id === undefined) return <Redirect href="/" />;
  return <EntryViewerScreen entryId={id} />;
}
