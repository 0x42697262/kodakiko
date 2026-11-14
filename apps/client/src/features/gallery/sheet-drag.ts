export const SHEET_CLOSE_FRACTION = 0.3;
export const SHEET_FLING_VELOCITY = 1_000;

export function shouldCloseSheet(dragY: number, velocityY: number, height: number): boolean {
  'worklet';
  if (dragY <= 0) return false;
  return dragY > height * SHEET_CLOSE_FRACTION || velocityY > SHEET_FLING_VELOCITY;
}
