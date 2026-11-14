import { useEffect, useRef, useState, type ChangeEvent, type CSSProperties } from 'react';
import { Text } from 'react-native';
import {
  DROP_HIGHLIGHT_OPACITY,
  DROP_OUTLINE_WIDTH,
  WEB_ACCEPT_TYPES,
} from '../../constants/capture-constants';
import { DOM_EVENTS } from '../../keys/capture-keys';
import { TEST_IDS, testId } from '../../keys/test-ids';
import { shortenFileName } from '../../services/media/file-name';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import type { MediaInput } from '../media/media-toolkit';
import type { CaptureSurfaceProps } from './capture-surface';
import { WebCamera } from './web-camera';

type WebOrigin = 'picker' | 'drop' | 'paste';

function toInputs(files: FileList | null | undefined, origin: WebOrigin): MediaInput[] {
  return Array.from(files ?? []).map((file) => ({
    origin,
    handle: { blob: file },
    fileName: shortenFileName(file.name),
    pickerExif: null,
  }));
}

const ZONE_STYLE: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  flex: 1,
  minHeight: 0,
  gap: SPACING.md,
};

export function CaptureSurface({ onInputs, onRecordingChange }: CaptureSurfaceProps) {
  const { colors } = useTheme();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    function onPaste(event: ClipboardEvent): void {
      const inputs = toInputs(event.clipboardData?.files, 'paste');
      if (inputs.length === 0) return;
      event.preventDefault();
      onInputs(inputs);
    }
    function onDragOver(event: DragEvent): void {
      event.preventDefault();
      setDragging(true);
    }
    function onDragLeave(event: DragEvent): void {
      if (event.relatedTarget === null) setDragging(false);
    }
    function onDrop(event: DragEvent): void {
      event.preventDefault();
      setDragging(false);
      onInputs(toInputs(event.dataTransfer?.files, 'drop'));
    }
    document.addEventListener(DOM_EVENTS.paste, onPaste);
    document.addEventListener(DOM_EVENTS.dragOver, onDragOver);
    document.addEventListener(DOM_EVENTS.dragLeave, onDragLeave);
    document.addEventListener(DOM_EVENTS.drop, onDrop);
    return () => {
      document.removeEventListener(DOM_EVENTS.paste, onPaste);
      document.removeEventListener(DOM_EVENTS.dragOver, onDragOver);
      document.removeEventListener(DOM_EVENTS.dragLeave, onDragLeave);
      document.removeEventListener(DOM_EVENTS.drop, onDrop);
    };
  }, [onInputs]);

  function onFilesChosen(event: ChangeEvent<HTMLInputElement>): void {
    onInputs(toInputs(event.target.files, 'picker'));
    event.target.value = '';
  }

  return (
    <div
      data-testid={TEST_IDS.captureDropZone}
      style={{
        ...ZONE_STYLE,
        outline: dragging ? `${DROP_OUTLINE_WIDTH}px solid ${colors.primary}` : 'none',
        opacity: dragging ? DROP_HIGHLIGHT_OPACITY : 1,
      }}
    >
      <WebCamera onInputs={onInputs} onRecordingChange={onRecordingChange} />
      <input
        ref={inputRef}
        type="file"
        multiple
        accept={WEB_ACCEPT_TYPES}
        data-testid={TEST_IDS.captureFileInput}
        style={{ display: 'none' }}
        onChange={onFilesChosen}
      />
      {dragging ? (
        <Text style={{ color: colors.text }}>{EN['capture.dropHint']}</Text>
      ) : null}
      <Button
        testID={testId('capture.chooseFiles')}
        label={EN['capture.chooseFiles']}
        variant="secondary"
        onPress={() => inputRef.current?.click()}
      />
    </div>
  );
}
