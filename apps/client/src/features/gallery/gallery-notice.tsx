import { useSyncExternalStore } from 'react';
import { StyleSheet, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { SPACING } from '../../ui/theme-constants';

export function GalleryNoticeBanner() {
  const { services } = useAppContext();
  const notice = useSyncExternalStore(services.galleryNotice.subscribe, services.galleryNotice.get);
  if (notice === null) return null;
  if (notice.kind === 'gone') {
    return <Banner testID={testId('gallery.noticeGone')} kind="info" message={EN['gallery.noticeGone']} />;
  }
  return (
    <View style={styles.row}>
      <Banner testID={testId('gallery.noticeTrashed')} kind="info" message={EN['gallery.noticeTrashed']} />
      <Button
        testID={testId('gallery.noticeUndo')}
        label={EN['gallery.noticeUndo']}
        variant="secondary"
        onPress={() => {
          services.galleryNotice.set(null);
          services.entries.restore([notice.entryId]).catch(() => undefined);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({ row: { gap: SPACING.sm } });
