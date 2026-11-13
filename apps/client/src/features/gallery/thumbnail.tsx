import { Image } from 'expo-image';
import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { EN } from '../../text/en';
import { FONT_SIZE } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';

export function useThumbnail(entryId: string, mediaId: string | null): string | null {
  const { services } = useAppContext();
  const [uri, setUri] = useState<string | null>(null);
  useEffect(() => {
    if (mediaId === null) return undefined;
    let active = true;
    let release: (() => void) | null = null;
    services.thumbnails.get(entryId, mediaId).then(
      (ref) => {
        if (ref === null) return;
        if (!active) {
          ref.release();
          return;
        }
        release = ref.release;
        setUri(ref.uri);
      },
      () => undefined,
    );
    return () => {
      active = false;
      release?.();
      setUri(null);
    };
  }, [services.thumbnails, entryId, mediaId]);
  return uri;
}

type ThumbnailProps = {
  readonly entryId: string;
  readonly mediaId: string | null;
  readonly size: number;
  readonly unreadable?: boolean;
};

export function Thumbnail({ entryId, mediaId, size, unreadable = false }: ThumbnailProps) {
  const { colors } = useTheme();
  const uri = useThumbnail(entryId, unreadable ? null : mediaId);
  const box = { width: size, height: size, backgroundColor: colors.surface };
  if (unreadable) {
    return (
      <View testID={GALLERY_IDS.tileUnreadable} style={[styles.center, box]}>
        <Text style={{ color: colors.textMuted }}>{EN['gallery.lockGlyph']}</Text>
        <Text style={[styles.caption, { color: colors.textMuted }]}>{EN['gallery.cantOpen']}</Text>
      </View>
    );
  }
  if (uri === null || mediaId === null) {
    return <View testID={GALLERY_IDS.thumbnailPlaceholder} style={box} />;
  }
  return (
    <Image
      source={{ uri }}
      style={box}
      contentFit="cover"
      cachePolicy="memory"
      transition={0}
      recyclingKey={mediaId}
    />
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
  caption: { fontSize: FONT_SIZE.caption },
});
