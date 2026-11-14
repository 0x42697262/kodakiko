import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAccountState } from '../../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../../src/composition/route-by-state';
import { EntryDetailsScreen } from '../../../src/features/viewer/EntryDetailsScreen';

export default function Route() {
  const state = useAccountState();
  const { id } = useLocalSearchParams<{ id: string }>();
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  return <EntryDetailsScreen entryId={id ?? ''} />;
}
