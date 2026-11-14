import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { localDateOf, localPartsToUtc, localTimeOf } from '@kodakiko/core';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Hint } from '../../ui/Typography';
import { SPACING } from '../../ui/theme-constants';

export type EntryTime = { readonly takenAtUtc: string; readonly utcOffsetMinutes: number };

export type TimeEditorProps = {
  readonly value: EntryTime;
  readonly original: EntryTime;
  readonly onDone: (value: EntryTime) => void;
};

export function TimeEditor({ value, original, onDone }: TimeEditorProps) {
  const offset = value.utcOffsetMinutes;
  const [date, setDate] = useState(localDateOf(value.takenAtUtc, offset));
  const [time, setTime] = useState(localTimeOf(value.takenAtUtc, offset));
  const [invalid, setInvalid] = useState(false);

  function reset(): void {
    setInvalid(false);
    onDone(original);
  }

  function finish(): void {
    const utc = localPartsToUtc(date, time, offset);
    if (utc === null) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    onDone({ takenAtUtc: utc, utcOffsetMinutes: offset });
  }

  return (
    <View style={styles.container}>
      <TextField
        testID={testId('timeEditor.date')}
        label={EN['timeEditor.date']}
        value={date}
        onChangeText={setDate}
        onSubmit={finish}
      />
      <TextField
        testID={testId('timeEditor.time')}
        label={EN['timeEditor.time']}
        value={time}
        onChangeText={setTime}
        onSubmit={finish}
      />
      {invalid ? (
        <Hint testID={testId('timeEditor.invalid')} text={EN['timeEditor.invalid']} />
      ) : null}
      <View style={styles.row}>
        <Button
          testID={testId('timeEditor.reset')}
          label={EN['timeEditor.reset']}
          variant="secondary"
          onPress={reset}
        />
        <Button testID={testId('timeEditor.done')} label={EN['timeEditor.done']} onPress={finish} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.sm },
  row: { flexDirection: 'row', gap: SPACING.md, justifyContent: 'space-between' },
});
