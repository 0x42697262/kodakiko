import { Redirect } from 'expo-router';
import { useAccountState } from '../src/composition/app-context';
import { ROUTE_BY_STATE } from '../src/composition/route-by-state';
import { TrashScreen } from '../src/features/gallery/TrashScreen';

export default function TrashRoute() {
  const state = useAccountState();
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  return <TrashScreen />;
}
