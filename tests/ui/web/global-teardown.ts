import { stopStack } from '../support/stack.ts';

export default async function globalTeardown(): Promise<void> {
  await stopStack();
}
