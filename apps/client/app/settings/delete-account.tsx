import { Redirect } from 'expo-router';
import { useAccountState } from '../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../src/composition/route-by-state';
import { DeleteAccountScreen } from '../../src/features/settings/DeleteAccountScreen';

export default function Route() {
  const state = useAccountState();
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  return <DeleteAccountScreen />;
}
