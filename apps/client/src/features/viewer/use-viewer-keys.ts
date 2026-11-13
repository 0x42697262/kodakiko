export type ViewerKeyHandlers = { readonly onMove: (delta: number) => void; readonly onBack: () => void };

export function useViewerKeys(_handlers: ViewerKeyHandlers): void {}
