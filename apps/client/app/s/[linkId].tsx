import { useLocalSearchParams } from 'expo-router';
import { LinkViewerScreen } from '../../src/features/link-viewer/LinkViewerScreen';

export default function PublicLinkRoute() {
  const { linkId } = useLocalSearchParams<{ linkId?: string }>();
  if (linkId === undefined) return null;
  return <LinkViewerScreen linkId={linkId} />;
}
