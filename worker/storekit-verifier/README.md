# GitSync StoreKit verifier

Private Cloudflare Worker used through the Premium relay's `STOREKIT_VERIFIER`
service binding. It validates StoreKit 2 transaction JWS and App Store Server
Notification v2 JWS with Apple's official `SignedDataVerifier` before returning
the relay's narrow, fail-closed DTO.

## Security boundary

- `@apple/app-store-server-library` verifies ES256 signatures, the `x5c`
  certificate chain, Apple certificate-purpose OIDs, bundle ID, environment,
  signed `appAccountToken`, and (for Production) App Apple ID.
- Online revocation checks via OCSP are **disabled** (`ENABLE_ONLINE_CHECKS=false`):
  Apple's CDN (`ocsp.apple.com`) rejects the Workers runtime's TLS fingerprint
  with `403 Forbidden` on every request (verified 2026-08-27: identical OCSP
  bytes succeed from Node's TLS stack and fail from workerd's, regardless of
  headers; Apple serves no HTTPS OCSP endpoint). Online checks are therefore
  impossible on this platform, and the flag is a deliberate decision, not a
  weakening shortcut. Compensating controls: Apple Root CA SHA-256 pinning in
  `src/roots.ts` (rotate when Apple rotates roots), certificate-purpose OID
  checks, full `x5c` chain validation, bundle/environment/App Apple ID checks,
  and relay-side product, appAccountToken, revocation, and expiry enforcement
  with a 30-day session cap. A verification or availability error grants no
  access.
- The Worker has no route in configuration. Keep it private and invoke it only
  through the relay service binding.
- Requests are JSON-only, exact-schema, and bounded by `MAX_JWS_BYTES`.
- No JWS or transaction identifier is logged.
- No Apple or app secret is required. The committed certificates are public
  trust anchors.

Pinned Apple root SHA-256 fingerprints (downloaded from
<https://www.apple.com/certificateauthority/>):

- Apple Root CA: `B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24`
- Apple Root CA - G2: `C2:B9:B0:42:DD:57:83:0E:7D:11:7D:AC:55:AC:8A:E1:94:07:D3:8E:41:D8:8F:32:15:BC:3A:89:04:44:A0:50`
- Apple Root CA - G3: `63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79`

## Configuration

Review these non-secret vars in `wrangler.jsonc` before deployment:

- `BUNDLE_ID`
- `APP_APPLE_ID` (required for Production verification)
- `ALLOWED_ENVIRONMENTS` (`Sandbox,Production` for release)
- `ENABLE_ONLINE_CHECKS` (keep `false`; see the security-boundary note above —
  online OCSP is blocked by Apple's CDN for the Workers runtime)
- `MAX_JWS_BYTES`

The relay binding service name must match this Worker's deployed name,
`storekit-verifier`.

## Local gates

```bash
npm ci
npm test
npm run typecheck
npm run types:check
npm run dry-run
npm run startup-check
```

Tests execute in the Workers runtime. They pin trust-anchor bytes and prove
malformed/unsigned JWS, invalid input, oversized bodies, and invalid
configuration fail closed. Before release, additionally send Apple's signed
Sandbox transaction and App Store Server Notification v2 test payload through
the deployed relay/verifier binding and retain Cloudflare/Apple evidence.

Do not deploy without explicit authorization. Deploy this Worker before the
relay so its service binding resolves; roll back the relay or set its kill
switch if verifier availability or Apple validation fails.
