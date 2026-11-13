export function deletionAllowed(typed: string, username: string, password: string): boolean {
  return typed.trim() === username && password.length > 0;
}
