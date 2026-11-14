export type AutoplaySettings = { readonly autoplayVideos: boolean; readonly autoplayOnMobileData: boolean };
export type AutoplayState = { readonly active: boolean; readonly cached: boolean; readonly wifi: boolean };

export function shouldAutoplay(settings: AutoplaySettings, state: AutoplayState): boolean {
  if (!settings.autoplayVideos || !state.active) return false;
  return state.cached || state.wifi || settings.autoplayOnMobileData;
}
