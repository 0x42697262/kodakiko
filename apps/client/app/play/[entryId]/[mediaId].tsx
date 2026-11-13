import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAccountState } from '../../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../../src/composition/route-by-state';
import { VideoPlayerScreen } from '../../../src/features/video/VideoPlayerScreen';

export default function PlayRoute() {
  const state = useAccountState();
  const { entryId, mediaId } = useLocalSearchParams<{ entryId?: string; mediaId?: string }>();
  if (state.kind !== 'unlocked') return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
  if (entryId === undefined || mediaId === undefined) return <Redirect href="/" />;
  return <VideoPlayerScreen entryId={entryId} mediaId={mediaId} />;
}
