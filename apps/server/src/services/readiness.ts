export type ReadinessCheck = { name: string; check(): Promise<void> };

export async function failedChecks(checks: readonly ReadinessCheck[]): Promise<string[]> {
  const outcomes = await Promise.allSettled(checks.map((entry) => entry.check()));
  return checks.flatMap((entry, position) =>
    outcomes[position]?.status === 'rejected' ? [entry.name] : [],
  );
}
