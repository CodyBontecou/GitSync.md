/**
 * Sync.md — Legacy Purchase Verifier
 *
 * POST /verify-legacy
 *   Body:    { "receipt": "<base64-encoded App Store receipt>" }
 *   Returns: { "isLegacy": true | false, "originalVersion": "..." }
 *   Verifies via Apple's verifyReceipt endpoint (requires on-disk receipt file).
 *
 * POST /verify-legacy-jws
 *   Body:    { "jws": "<AppTransaction JWS from StoreKit 2>" }
 *   Returns: { "isLegacy": true | false, "originalVersion": "..." }
 *   Decodes the Apple-signed AppTransaction JWS and checks originalAppVersion.
 *   Works on TestFlight + App Store (no receipt file needed).
 *
 * The iOS app caches a successful `isLegacy: true` response in the Keychain
 * so either endpoint is only ever called once per device.
 */

interface Env {
  /** Shared secret from App Store Connect → In-App Purchases → App-Specific Shared Secret.
   *  Required for subscription receipt verification; optional for paid-app receipts,
   *  but should always be set so the worker is ready if subscriptions are added later. */
  APPLE_SHARED_SECRET?: string;
  /** Set to "true" in wrangler.toml temporarily to force isLegacy: true for any valid
   *  receipt. Used to test the full iOS unlock flow without a real legacy receipt.
   *  NEVER ship with this enabled. */
  DEBUG_FORCE_LEGACY?: string;
}

interface AppleVerifyReceiptResponse {
  /** 0 = success. See Apple docs for other status codes. */
  status: number;
  receipt?: {
    bundle_id: string;
    /** CFBundleVersion (build number) on iOS, CFBundleShortVersionString on macOS. */
    original_application_version: string;
  };
  /** Returned when status === 21007 — receipt is from the sandbox environment. */
  environment?: string;
}

const BUNDLE_ID = "bontecou.Sync-md";
/** Marketing version threshold (CFBundleShortVersionString, used by macOS).
 *  v1.6 was accidentally shipped as a paid app; v1.7 is the first truly free release. */
const FREEMIUM_INTRO_VERSION = "1.7";
/** Build number threshold (CFBundleVersion, used by iOS).
 *  On iOS, originalApplicationVersion is CFBundleVersion, NOT CFBundleShortVersionString.
 *
 *  History:
 *    v1.5  build "1"            — paid app
 *    v1.6  build "202603260933" — accidentally still paid; all v1.6 users get legacy access
 *    v1.7  build ≥ "202603270000" — first free (freemium) release
 */
const FREEMIUM_INTRO_BUILD_NUMBER = "202603270000";

const APPLE_PRODUCTION_URL = "https://buy.itunes.apple.com/verifyReceipt";
const APPLE_SANDBOX_URL    = "https://sandbox.itunes.apple.com/verifyReceipt";

// ---------------------------------------------------------------------------
// JWS / JWT helpers
// ---------------------------------------------------------------------------

/** Decodes a base64url string (no padding) to a UTF-8 string. */
function base64urlDecode(input: string): string {
  let b64 = input.replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4 !== 0) b64 += "=";
  return atob(b64);
}

/**
 * Decodes an Apple AppTransaction JWS (compact serialization: header.payload.signature).
 * Returns the parsed payload or null if the structure is invalid.
 */
function decodeAppTransactionJWS(jws: string): AppTransactionPayload | null {
  const parts = jws.split(".");
  if (parts.length !== 3) return null;
  try {
    const payload = JSON.parse(base64urlDecode(parts[1])) as AppTransactionPayload;
    if (!payload.bundleId || !payload.originalApplicationVersion) return null;
    return payload;
  } catch {
    return null;
  }
}

interface AppTransactionPayload {
  bundleId: string;
  /** Apple uses "originalApplicationVersion" in the JWS payload, even though
   *  StoreKit 2's Swift API exposes it as `originalAppVersion`. */
  originalApplicationVersion: string;
  applicationVersion?: string;
  environment?: string;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function versionIsLessThan(v1: string, v2: string): boolean {
  const a = v1.split(".").map(Number);
  const b = v2.split(".").map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] ?? 0;
    const y = b[i] ?? 0;
    if (x < y) return true;
    if (x > y) return false;
  }
  return false;
}

/** Detects whether a version string is a CFBundleVersion build number
 *  versus a marketing version. */
function isBuildNumber(version: string): boolean {
  return !version.includes(".") && version.length > 0 && /^\d+$/.test(version);
}

/** Returns true when the given originalApplicationVersion indicates a legacy
 *  (pre-freemium) install, handling both build numbers (iOS) and marketing
 *  versions (macOS). */
function isLegacyVersion(version: string): boolean {
  if (isBuildNumber(version)) {
    return versionIsLessThan(version, FREEMIUM_INTRO_BUILD_NUMBER);
  }
  return versionIsLessThan(version, FREEMIUM_INTRO_VERSION);
}

async function verifyWithApple(
  receiptData: string,
  sharedSecret: string | undefined,
  url: string
): Promise<AppleVerifyReceiptResponse> {
  const body: Record<string, string | boolean> = {
    "receipt-data": receiptData,
    "exclude-old-transactions": false,
  };
  if (sharedSecret) {
    body.password = sharedSecret;
  }

  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return response.json() as Promise<AppleVerifyReceiptResponse>;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { method, url } = request;
    const { pathname } = new URL(url);

    if (pathname === "/health" && method === "GET") {
      return jsonResponse({ ok: true });
    }

    if (method !== "POST") {
      return jsonResponse({ error: "Method not allowed" }, 405);
    }

    // -----------------------------------------------------------------------
    // POST /verify-legacy-jws — StoreKit 2 AppTransaction JWS
    // -----------------------------------------------------------------------
    if (pathname === "/verify-legacy-jws") {
      let body: { jws?: string };
      try {
        body = await request.json() as { jws?: string };
      } catch {
        return jsonResponse({ error: "Invalid JSON body" }, 400);
      }

      if (!body.jws || typeof body.jws !== "string") {
        return jsonResponse({ error: "Missing or invalid 'jws' field" }, 400);
      }

      const payload = decodeAppTransactionJWS(body.jws);
      if (!payload) {
        return jsonResponse({ error: "Invalid JWS structure" }, 400);
      }

      if (payload.bundleId !== BUNDLE_ID) {
        return jsonResponse({ error: "Bundle ID mismatch" }, 400);
      }

      const originalVersion = payload.originalApplicationVersion;
      const isLegacy = env.DEBUG_FORCE_LEGACY === "true"
        ? true
        : isLegacyVersion(originalVersion);

      return jsonResponse({ isLegacy, originalVersion });
    }

    // -----------------------------------------------------------------------
    // POST /verify-legacy — Legacy receipt file → Apple verifyReceipt
    // -----------------------------------------------------------------------
    if (pathname !== "/verify-legacy") {
      return jsonResponse({ error: "Not found" }, 404);
    }

    let body: { receipt?: string };
    try {
      body = await request.json() as { receipt?: string };
    } catch {
      return jsonResponse({ error: "Invalid JSON body" }, 400);
    }

    if (!body.receipt || typeof body.receipt !== "string") {
      return jsonResponse({ error: "Missing or invalid 'receipt' field" }, 400);
    }

    let result = await verifyWithApple(body.receipt, env.APPLE_SHARED_SECRET, APPLE_PRODUCTION_URL);

    if (result.status === 21007) {
      result = await verifyWithApple(body.receipt, env.APPLE_SHARED_SECRET, APPLE_SANDBOX_URL);
    }

    if (result.status !== 0) {
      return jsonResponse(
        { error: "Apple receipt verification failed", appleStatus: result.status },
        400
      );
    }

    if (!result.receipt) {
      return jsonResponse({ error: "No receipt data in Apple response" }, 400);
    }

    if (result.receipt.bundle_id !== BUNDLE_ID) {
      return jsonResponse({ error: "Bundle ID mismatch" }, 400);
    }

    const originalVersion = result.receipt.original_application_version;
    const isLegacy = env.DEBUG_FORCE_LEGACY === "true"
      ? true
      : isLegacyVersion(originalVersion);

    return jsonResponse({ isLegacy, originalVersion });
  },
};
