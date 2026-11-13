import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAccountState } from '../../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../../src/composition/route-by-state';
import { ShareScreen } from '../../../src/features/sharing/ShareScreen';

export default function ShareRoute() {
  const state = useAccountState();
  const { id } = useLocalSearchParams<{ id?: string }>();
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  if (id === undefined) return <Redirect href="/" />;
  return <ShareScreen collectionId={id} />;
}
