import { Redirect, useLocalSearchParams } from 'expo-router';
import { useMemo } from 'react';
import { useAccountState } from '../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../src/composition/route-by-state';
import { LinkCreateScreen } from '../../src/features/links/LinkCreateScreen';

const SEPARATOR = ',';

export default function NewLinkRoute() {
  const state = useAccountState();
  const { entries } = useLocalSearchParams<{ entries?: string }>();
  const entryIds = useMemo(() => (entries ?? '').split(SEPARATOR).filter((id) => id.length > 0), [entries]);
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  if (entryIds.length === 0) return <Redirect href="/" />;
  return <LinkCreateScreen entryIds={entryIds} />;
}
