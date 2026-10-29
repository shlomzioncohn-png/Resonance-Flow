// Inbox's rules for a password: at least 8 characters, at most 72 bytes (bcrypt's limit).
export const PASSWORD_MIN_LENGTH = 8;
const PASSWORD_MAX_BYTES = 72;

/** What's wrong with a password, or null when Inbox will accept it. */
export function passwordProblem(password: string): string | null {
  if (password.length < PASSWORD_MIN_LENGTH) return `At least ${PASSWORD_MIN_LENGTH} characters`;
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) return 'Too long';
  return null;
}
