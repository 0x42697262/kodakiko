import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAccountState } from '../../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../../src/composition/route-by-state';
import { EntryEditScreen } from '../../../src/features/entries/EntryEditScreen';
import { useIndexedEntry } from '../../../src/features/gallery/hooks';
import { entryRoute } from '../../../src/keys/routes';

export default function EntryEditRoute() {
  const state = useAccountState();
  const { id } = useLocalSearchParams<{ id?: string }>();
  const summary = useIndexedEntry(id ?? '');
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  if (id === undefined) return <Redirect href="/" />;
  if (summary !== null && (!summary.isOwn || summary.trashed)) return <Redirect href={entryRoute(id)} />;
  return <EntryEditScreen entryId={id} />;
}
