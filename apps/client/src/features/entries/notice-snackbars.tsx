import { useEffect } from 'react';
import { useAppContext } from '../../composition/app-context';
import { SAVED_BANNER_MS } from '../../constants/entry-constants';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { snackbar } from '../../ui/snackbar-store';

export function NoticeSnackbars() {
  const { services, clock } = useAppContext();

  useEffect(
    () =>
      services.savedNotice.subscribe(() => {
        const savedAt = services.savedNotice.get();
        if (savedAt !== null && clock.now() - savedAt < SAVED_BANNER_MS) {
          snackbar.show({ text: EN['entry.saved'], testID: ENTRY_IDS.savedBanner });
        }
      }),
    [services.savedNotice, clock],
  );

  useEffect(
    () =>
      services.galleryNotice.subscribe(() => {
        const notice = services.galleryNotice.get();
        if (notice === null) return;
        services.galleryNotice.set(null);
        if (notice.kind === 'gone') {
          snackbar.show({ text: EN['gallery.noticeGone'], testID: testId('gallery.noticeGone') });
          return;
        }
        snackbar.show({
          text: EN['gallery.noticeTrashed'],
          testID: testId('gallery.noticeTrashed'),
          action: {
            label: EN['gallery.noticeUndo'],
            testID: testId('gallery.noticeUndo'),
            onPress: () => {
              services.entries.restore([notice.entryId]).catch(() => undefined);
            },
          },
        });
      }),
    [services.galleryNotice, services.entries],
  );

  return null;
}
