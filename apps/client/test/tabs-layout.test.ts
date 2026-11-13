import { beforeEach, describe, expect, it, vi } from 'vitest';

const { replace, Tabs } = vi.hoisted(() => ({
  replace: vi.fn(),
  Tabs: Object.assign(() => null, { Screen: () => null }),
}));
const effects: Array<() => void> = [];
let accountKind = 'unlocked';

vi.mock('react', () => ({
  useEffect: (effect: () => void) => {
    effects.push(effect);
  },
}));
vi.mock('expo-router', () => ({
  Tabs,
  Redirect: 'Redirect',
  useRouter: () => ({ replace }),
}));
vi.mock('../src/composition/app-context', () => ({
  useAccountState: () => ({ kind: accountKind }),
  useAccountMode: () => 'server',
  useAppContext: () => ({ services: { config: { get: () => null } } }),
}));
vi.mock('../src/features/navigation/material-tab-bar', () => ({ MaterialTabBar: () => null }));

const { default: TabsLayout } = await import('../app/(tabs)/_layout');

describe('(tabs) layout', () => {
  beforeEach(() => {
    replace.mockClear();
    effects.length = 0;
  });

  it('renders the tabs while unlocked and does not navigate', () => {
    accountKind = 'unlocked';
    const element = TabsLayout() as { type: unknown } | null;
    for (const effect of effects) effect();
    expect(element?.type).toBe(Tabs);
    expect(replace).not.toHaveBeenCalled();
  });

  it.each([
    ['signedOut', '/welcome'],
    ['noServer', '/welcome'],
    ['locked', '/lock'],
    ['pendingDeletion', '/cancel-deletion'],
  ])(
    'renders nothing and replaces with the route of the %s state (never "/", which is this very layout\'s index)',
    (kind, route) => {
      accountKind = kind;
      const element = TabsLayout();
      for (const effect of effects) effect();
      expect(element).toBeNull();
      expect(replace).toHaveBeenCalledTimes(1);
      expect(replace).toHaveBeenCalledWith(route);
    },
  );
});
