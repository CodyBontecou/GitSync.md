const CANONICAL_HOST = "gitsyncmd.isolated.tech";
const LEGACY_HOST = "syncmd.isolated.tech";
const UPSTREAM_HOST = "syncmd.pages.dev";

export default {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.hostname === LEGACY_HOST) {
      url.hostname = CANONICAL_HOST;
      url.protocol = "https:";
      return Response.redirect(url.toString(), 301);
    }

    url.hostname = UPSTREAM_HOST;
    url.protocol = "https:";

    const upstreamRequest = new Request(url.toString(), request);
    upstreamRequest.headers.set("X-Forwarded-Host", CANONICAL_HOST);
    upstreamRequest.headers.set("X-Forwarded-Proto", "https");

    return fetch(upstreamRequest);
  },
};
