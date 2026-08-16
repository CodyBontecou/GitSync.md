const encoder = new TextEncoder();

export class HttpError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}
export function randomToken(bytes = 32): string {
  const value = new Uint8Array(bytes); crypto.getRandomValues(value); return b64url(value);
}
export async function sha256(value: string): Promise<string> {
  return b64url(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}
export function uuid(v: unknown, name = "installation ID"): string {
  const value = text(v, name, 36).toLowerCase();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) {
    throw new HttpError(400, `invalid ${name}`);
  }
  return value;
}
export function positiveSetting(value: string, name: string, maximum: number): number {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0 || parsed > maximum) throw new Error(`invalid ${name}`);
  return parsed;
}
export function constantTimeText(a: string, b: string): boolean {
  const aa = encoder.encode(a), bb = encoder.encode(b);
  if (aa.length !== bb.length) return false;
  let n = 0; for (let i = 0; i < aa.length; i++) n |= aa[i]! ^ bb[i]!; return n === 0;
}
export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}
export function exactKeys(v: Record<string, unknown>, allowed: string[], required = allowed): void {
  for (const key of Object.keys(v)) if (!allowed.includes(key)) throw new HttpError(400, "unexpected field");
  for (const key of required) if (!(key in v)) throw new HttpError(400, "missing field");
}
export function text(v: unknown, name: string, max = 256): string {
  if (typeof v !== "string" || v.length < 1 || v.length > max) throw new HttpError(400, `invalid ${name}`); return v;
}
export function integer(v: unknown, name: string): number {
  if (typeof v !== "number" || !Number.isSafeInteger(v) || v <= 0) throw new HttpError(400, `invalid ${name}`); return v;
}
export const opaqueId = (v: unknown, name: string) => {
  const s = text(v, name, 128); if (!/^[A-Za-z0-9_-]{16,128}$/.test(s)) throw new HttpError(400, `invalid ${name}`); return s;
};
export async function strictJson(request: Request, max = 16_384): Promise<Record<string, unknown>> {
  if (request.headers.get("content-type")?.split(";", 1)[0]?.trim() !== "application/json") throw new HttpError(415, "application/json required");
  const length = Number(request.headers.get("content-length")); if (Number.isFinite(length) && length > max) throw new HttpError(413, "body too large");
  const bytes = new Uint8Array(await request.arrayBuffer()); if (bytes.length > max) throw new HttpError(413, "body too large");
  let value: unknown; try { value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); } catch { throw new HttpError(400, "invalid JSON"); }
  if (!isRecord(value)) throw new HttpError(400, "JSON object required"); return value;
}
export function json(value: unknown, status = 200, extra: HeadersInit = {}): Response {
  return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "content-security-policy": "default-src 'none'", "x-content-type-options": "nosniff", "referrer-policy": "no-referrer", ...extra } });
}
export function pemBytes(pem: string): ArrayBuffer {
  const body = pem.replace(/\\n/g, "\n").replace(/-----BEGIN [^-]+-----|-----END [^-]+-----|\s/g, "");
  const raw = atob(body);
  const bytes = new Uint8Array(raw.length);
  for (let index = 0; index < raw.length; index++) bytes[index] = raw.charCodeAt(index);
  return bytes.buffer;
}
export function log(event: string, fields: Record<string, string | number | boolean> = {}): void {
  console.log(JSON.stringify({ event, ...fields }));
}
export function noContent(status = 204): Response {
  return new Response(null, { status, headers: { "cache-control": "no-store" } });
}
