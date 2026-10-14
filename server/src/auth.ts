import type { Config } from "./config";

/**
 * Returns the authenticated user, or null.
 * Trusts `Tailscale-User-Login` because the gateway binds to loopback behind `tailscale serve`.
 */
export function authenticate(req: Request, auth: Config["auth"]): string | null {
  const login = req.headers.get("tailscale-user-login");
  if (login && auth.allowedUsers.includes(login)) return login;

  const token =
    req.headers.get("authorization")?.match(/^Bearer (.+)$/)?.[1] ?? new URL(req.url).searchParams.get("token");
  if (token && auth.tokens.includes(token)) return `token:${token.slice(0, 4)}`;

  return null;
}
