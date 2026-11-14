import { CAPTION_MAX_CHARS, type EntryMetadataV1 } from '@kodakiko/contract';
import { useState } from 'react';
import { Text } from 'react-native';
import { List } from 'react-native-paper';
import type { z } from 'zod';
import { cropImage } from '../../adapters/image-crop/crop-image';
import { canShareFiles, saveToDevice } from '../../adapters/share-files/share-files';
import { shareOriginals } from '../sharing/ShareSheet';
import { useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import type { CropRect } from './crop-math';
import { CropSheet } from './crop-sheet';
import { replaceMedia } from './replace-media';
import { fileNameOf } from './media-file-name';

type Descriptor = z.infer<typeof EntryMetadataV1>['media'][number];

export type MediaMenuState =
  | null
  | { readonly kind: 'menu' | 'caption' | 'remove'; readonly mediaId: string }
  | {
      readonly kind: 'crop';
      readonly mediaId: string;
      readonly uri: string;
      readonly release: () => Promise<void>;
      readonly busy: boolean;
      readonly failed: boolean;
    };

const SAVE_RELEASE_DELAY_MS = 30_000;
function kindText(kind: Descriptor['kind']): string {
  return kind === 'photo' ? EN['edit.kindPhoto'] : kind === 'video' ? EN['edit.kindVideo'] : EN['edit.kindAudio'];
}

export function MediaMenus({
  entryId,
  state,
  setState,
  media,
  captions,
  flush,
  onCaption,
  onCover,
  onRemove,
  onReplaced,
}: {
  readonly entryId: string;
  readonly state: MediaMenuState;
  readonly setState: (next: MediaMenuState) => void;
  readonly media: readonly Descriptor[];
  readonly captions: Readonly<Record<string, string>>;
  readonly flush: () => Promise<void>;
  readonly onCaption: (mediaId: string, caption: string) => void;
  readonly onCover: (mediaId: string) => void;
  readonly onRemove: (mediaId: string) => void;
  readonly onReplaced: () => void;
}) {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [captionText, setCaptionText] = useState('');
  if (state === null) return null;
  const position = media.findIndex((item) => item.mediaId === state.mediaId);
  const item = media[position];
  if (item === undefined) return null;
  const close = () => setState(null);

  async function save(): Promise<void> {
    if (item === undefined) return;
    close();
    const ref = await services.originals.open(entryId, item.mediaId);
    if (ref === 'missing') return;
    try {
      await saveToDevice({ uri: ref.uri, mimeType: ref.mimeType, fileName: fileNameOf(item, position) });
    } finally {
      setTimeout(() => {
        ref.release().catch(() => undefined);
      }, SAVE_RELEASE_DELAY_MS);
    }
  }

  async function startCrop(): Promise<void> {
    if (item === undefined) return;
    await flush();
    const ref = await services.originals.open(entryId, item.mediaId);
    if (ref === 'missing') {
      close();
      return;
    }
    setState({ kind: 'crop', mediaId: item.mediaId, uri: ref.uri, release: ref.release, busy: false, failed: false });
  }

  async function applyCrop(rect: CropRect): Promise<void> {
    if (state === null || state.kind !== 'crop' || item === undefined) return;
    setState({ ...state, busy: true, failed: false });
    try {
      const input = await cropImage(state.uri, rect, item.originalFileName);
      const limit = (services.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;
      const result = await replaceMedia({ entries: services.entries, importOne: services.mediaImport.importOne, limit }, entryId, item.mediaId, input);
      if (result === 'refused') throw new Error(EN['crop.failed']);
      await state.release();
      close();
      onReplaced();
    } catch {
      setState({ ...state, busy: false, failed: true });
    }
  }

  if (state.kind === 'crop') {
    return (
      <CropSheet
        uri={state.uri}
        imageWidth={item.width}
        imageHeight={item.height}
        busy={state.busy}
        failed={state.failed}
        onApply={(rect) => {
          applyCrop(rect).catch(() => undefined);
        }}
        onCancel={() => {
          state.release().catch(() => undefined);
          close();
        }}
      />
    );
  }

  if (state.kind === 'caption') {
    return (
      <Sheet testID="edit.captionSheet" onClose={close}>
        <TextField
          testID="edit.captionField"
          label={EN['edit.caption']}
          placeholder={EN['edit.captionPlaceholder']}
          value={captionText}
          onChangeText={setCaptionText}
          maxLength={CAPTION_MAX_CHARS}
          autoCapitalize="sentences"
          autoFocus
          onSubmit={() => {
            onCaption(item.mediaId, captionText.trim());
            close();
          }}
        />
        <Button
          testID="edit.captionSave"
          label={EN['edit.captionSave']}
          onPress={() => {
            onCaption(item.mediaId, captionText.trim());
            close();
          }}
        />
      </Sheet>
    );
  }

  if (state.kind === 'remove') {
    return (
      <Sheet testID="edit.removeSheet" onClose={close}>
        <Text style={{ color: colors.text }}>{media.length <= 1 ? EN['edit.lastItem'] : EN['edit.removeTitle'](kindText(item.kind))}</Text>
        {media.length <= 1 ? null : (
          <Button
            testID="edit.removeConfirm"
            label={EN['edit.removeConfirm']}
            variant="danger"
            onPress={() => {
              onRemove(item.mediaId);
              close();
            }}
          />
        )}
      </Sheet>
    );
  }

  return (
    <Sheet testID="edit.mediaMenu" onClose={close}>
      <List.Item
        testID="edit.caption"
        title={EN['edit.caption']}
        left={(props) => <List.Icon {...props} icon="closed-caption" />}
        onPress={() => {
          setCaptionText(captions[item.mediaId] ?? '');
          setState({ kind: 'caption', mediaId: item.mediaId });
        }}
      />
      {position === 0 ? null : (
        <List.Item
          testID="edit.setCover"
          title={EN['edit.setCover']}
          left={(props) => <List.Icon {...props} icon="star" />}
          onPress={() => {
            onCover(item.mediaId);
            close();
          }}
        />
      )}
      {canShareFiles() ? (
        <List.Item
          testID="edit.shareItem"
          title={EN['edit.share']}
          left={(props) => <List.Icon {...props} icon="share" />}
          onPress={() => {
            close();
            shareOriginals(services.originals.open, [{ entryId, media: item, position, note: '' }], null).catch(() => undefined);
          }}
        />
      ) : null}
      <List.Item
        testID="edit.saveToDevice"
        title={EN['edit.saveToDevice']}
        left={(props) => <List.Icon {...props} icon="download" />}
        onPress={() => {
          save().catch(() => undefined);
        }}
      />
      {item.kind === 'photo' ? (
        <List.Item
          testID="edit.crop"
          title={EN['edit.crop']}
          left={(props) => <List.Icon {...props} icon="crop" />}
          onPress={() => {
            startCrop().catch(() => close());
          }}
        />
      ) : null}
      <List.Item
        testID={testId('compose.removeItem')}
        title={EN['edit.remove']}
        left={(props) => <List.Icon {...props} icon="delete" />}
        onPress={() => setState({ kind: 'remove', mediaId: item.mediaId })}
      />
    </Sheet>
  );
}
