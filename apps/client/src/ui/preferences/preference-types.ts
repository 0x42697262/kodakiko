import type { IconKey } from '../icons';

export type ChoiceOption<Value extends string | number> = { readonly value: Value; readonly label: string };

export type PreferenceItem =
  | { readonly kind: 'header'; readonly key: string; readonly title: string }
  | {
      readonly kind: 'text';
      readonly key: string;
      readonly title: string;
      readonly summary?: string;
      readonly icon?: IconKey;
      readonly testID: string;
      readonly onPress?: () => void;
      readonly danger?: boolean;
    }
  | {
      readonly kind: 'switch';
      readonly key: string;
      readonly title: string;
      readonly summary?: string;
      readonly icon?: IconKey;
      readonly testID: string;
      readonly value: boolean;
      readonly disabled?: boolean;
      readonly onChange: (next: boolean) => void;
    }
  | {
      readonly kind: 'choice';
      readonly key: string;
      readonly title: string;
      readonly icon?: IconKey;
      readonly testID: string;
      readonly summary?: string;
      readonly value: string | number;
      readonly options: readonly ChoiceOption<string | number>[];
      readonly onChange: (value: string | number) => void;
    }
  | { readonly kind: 'info'; readonly key: string; readonly text: string; readonly testID?: string };

export function choiceSummary(item: { readonly value: string | number; readonly options: readonly ChoiceOption<string | number>[] }): string {
  return item.options.find((option) => option.value === item.value)?.label ?? String(item.value);
}
