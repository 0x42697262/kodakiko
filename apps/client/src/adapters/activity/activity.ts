import type { ActivitySource } from '../../services/lock/auto-lock';

export type PageVisibility = { readonly onVisible: (listener: () => void) => () => void };

export declare function createActivitySource(now: () => number): ActivitySource;
export declare const pageVisibility: PageVisibility;
