import { withAndroidStyles } from 'expo/config-plugins';

const APP_THEME = 'AppTheme';
const CONTRAST_ITEM = 'android:enforceNavigationBarContrast';

export function disableNavigationBarContrast(styles) {
  for (const style of styles.resources.style ?? []) {
    if (style.$.name !== APP_THEME) continue;
    const items = (style.item ?? []).filter((item) => item.$.name !== CONTRAST_ITEM);
    items.push({ $: { name: CONTRAST_ITEM }, _: 'false' });
    style.item = items;
  }
  return styles;
}

export default function withSystemBars(config) {
  return withAndroidStyles(config, (modified) => {
    modified.modResults = disableNavigationBarContrast(modified.modResults);
    return modified;
  });
}
