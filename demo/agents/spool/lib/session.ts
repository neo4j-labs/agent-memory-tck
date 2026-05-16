/**
 * Anonymous cookie-based session helper.
 *
 * Each browser is assigned a UUID stored in an HTTP-only cookie on first
 * visit. That UUID becomes the NAMS `userId` for every operation
 * originating from that browser. Not a security boundary — the README
 * disclaimer is explicit about this.
 */

import { cookies } from "next/headers";

const COOKIE_NAME = "spool-uid";
const THIRTY_DAYS = 30 * 24 * 60 * 60;

export function getOrCreateUserId(): string {
  const jar = cookies();
  const existing = jar.get(COOKIE_NAME)?.value;
  if (existing) return existing;

  const fresh = crypto.randomUUID();
  jar.set({
    name: COOKIE_NAME,
    value: fresh,
    httpOnly: true,
    sameSite: "lax",
    maxAge: THIRTY_DAYS,
    path: "/",
  });
  return fresh;
}

export function readUserId(): string | null {
  return cookies().get(COOKIE_NAME)?.value ?? null;
}
