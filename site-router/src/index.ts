const CANONICAL_HOST = "gitsyncmd.app";
const UPSTREAM_HOST = "syncmd.pages.dev";
const APP_STORE_ID = "6758960270";
const DEFAULT_APP_STORE_URL = `https://apps.apple.com/us/app/gitsync-md/id${APP_STORE_ID}`;

type CampaignAngle = {
  label: string;
  ppidEnv?: keyof Env;
};

type CampaignLink = {
  slug: string;
  platform: string;
  platformLabel: string;
  angle: string;
  angleLabel: string;
  sequence: string;
  campaignToken: string;
  ppidEnv?: keyof Env;
};

type Env = {
  APP_STORE_BASE_URL?: string;
  APPLE_PROVIDER_TOKEN?: string;
  CPP_OBSIDIAN_PPID?: string;
  CPP_GITCLIENT_PPID?: string;
  CPP_SELFHOSTED_PPID?: string;
  CPP_HOMEPAGE_PPID?: string;
  DB?: {
    prepare: (query: string) => {
      bind: (...values: unknown[]) => { run: () => Promise<unknown> };
    };
  };
};

type ClickEvent = {
  slug: string;
  campaignToken: string;
  platform: string;
  angle: string;
  referrerHost: string | null;
  clientSignal: string;
  country: string | null;
  hasProviderToken: boolean;
  hasCustomProductPage: boolean;
  ts: string;
};

const platformLabels: Record<string, string> = {
  tt: "TikTok",
  ig: "Instagram Reels",
  yt: "YouTube Shorts",
  x: "X/Twitter",
  th: "Threads",
  web: "Website",
};

const angleConfig: Record<string, CampaignAngle> = {
  obsidian: {
    label: "Obsidian Git Sync",
    ppidEnv: "CPP_OBSIDIAN_PPID",
  },
  gitclient: {
    label: "Git Client on iOS",
    ppidEnv: "CPP_GITCLIENT_PPID",
  },
  selfhosted: {
    label: "Self-hosted / Technical Git",
    ppidEnv: "CPP_SELFHOSTED_PPID",
  },
  commits: {
    label: "Commits Without Laptop",
    ppidEnv: "CPP_GITCLIENT_PPID",
  },
  privacy: {
    label: "Private / Serverless Git",
    ppidEnv: "CPP_SELFHOSTED_PPID",
  },
  homepage: {
    label: "Homepage CTA",
    ppidEnv: "CPP_HOMEPAGE_PPID",
  },
};

const explicitLinks: Record<string, CampaignLink> = {
  "web-home": {
    slug: "web-home",
    platform: "web",
    platformLabel: platformLabels.web,
    angle: "homepage",
    angleLabel: angleConfig.homepage.label,
    sequence: "001",
    campaignToken: "web_home_001",
    ppidEnv: angleConfig.homepage.ppidEnv,
  },
};

function cleanSlug(slug: string | null): string {
  return String(slug || "")
    .trim()
    .toLowerCase()
    .replace(/^\/+|\/+$/g, "");
}

function parseCampaignSlug(rawSlug: string | null): CampaignLink | null {
  const slug = cleanSlug(rawSlug);
  if (!slug) return null;
  if (explicitLinks[slug]) return explicitLinks[slug];

  const match = slug.match(/^([a-z]{1,4})-([a-z0-9]+)-([0-9]{3})$/);
  if (!match) return null;

  const [, platform, angle, sequence] = match;
  const platformLabel = platformLabels[platform];
  const angleDetails = angleConfig[angle];
  if (!platformLabel || !angleDetails) return null;

  return {
    slug,
    platform,
    platformLabel,
    angle,
    angleLabel: angleDetails.label,
    sequence,
    campaignToken: `${platform}_${angle}_${sequence}`,
    ppidEnv: angleDetails.ppidEnv,
  };
}

function buildAppStoreUrl(link: CampaignLink, env: Env): string {
  const target = new URL(env.APP_STORE_BASE_URL || DEFAULT_APP_STORE_URL);
  const providerToken = env.APPLE_PROVIDER_TOKEN;
  const productPageId = link.ppidEnv ? env[link.ppidEnv] : undefined;

  if (typeof productPageId === "string" && productPageId.length > 0) {
    target.searchParams.set("ppid", productPageId);
  }
  if (providerToken) {
    target.searchParams.set("pt", providerToken);
  }

  target.searchParams.set("ct", link.campaignToken);
  target.searchParams.set("mt", "8");
  return target.toString();
}

function referrerHost(request: Request): string | null {
  const raw = request.headers.get("referer") || request.headers.get("referrer");
  if (!raw) return null;
  try {
    return new URL(raw).hostname;
  } catch {
    return "invalid-referrer";
  }
}

function clientSignal(request: Request): string {
  const ua = request.headers.get("user-agent") || "";
  if (/tiktok/i.test(ua)) return "tiktok";
  if (/instagram/i.test(ua)) return "instagram";
  if (/youtube|googlebot/i.test(ua)) return "youtube_or_google";
  if (/twitter|x-client/i.test(ua)) return "x";
  if (/bot|crawler|spider/i.test(ua)) return "bot";
  if (/iphone|ipad|ios/i.test(ua)) return "ios";
  if (/android/i.test(ua)) return "android";
  if (/macintosh|mac os/i.test(ua)) return "mac";
  return "other";
}

function slugFromRequest(request: Request): string | null {
  const { pathname } = new URL(request.url);
  const match = pathname.match(/^\/v\/([^/]+)\/?$/);
  return match ? decodeURIComponent(match[1]) : null;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

async function recordClick(env: Env, event: ClickEvent): Promise<void> {
  if (!env.DB) return;
  await env.DB.prepare(`
    INSERT INTO campaign_clicks (
      slug,
      campaign_token,
      platform,
      angle,
      referrer_host,
      client_signal,
      country,
      has_provider_token,
      has_custom_product_page,
      created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).bind(
    event.slug,
    event.campaignToken,
    event.platform,
    event.angle,
    event.referrerHost,
    event.clientSignal,
    event.country,
    event.hasProviderToken ? 1 : 0,
    event.hasCustomProductPage ? 1 : 0,
    event.ts,
  ).run();
}

async function handleCampaignRedirect(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  const slug = slugFromRequest(request);
  const link = parseCampaignSlug(slug);
  if (!link) {
    return new Response("Unknown GitSync.md campaign link. Use /v/<platform-angle-###>.", {
      status: 404,
      headers: {
        "content-type": "text/plain; charset=utf-8",
        "cache-control": "no-store",
      },
    });
  }

  const destination = buildAppStoreUrl(link, env);
  const hasProviderToken = Boolean(env.APPLE_PROVIDER_TOKEN);
  const hasCustomProductPage = Boolean(link.ppidEnv && env[link.ppidEnv]);
  const event: ClickEvent & { event: string } = {
    event: "gitsyncmd_campaign_click",
    slug: link.slug,
    campaignToken: link.campaignToken,
    platform: link.platform,
    angle: link.angle,
    referrerHost: referrerHost(request),
    clientSignal: clientSignal(request),
    country: (request as Request & { cf?: { country?: string } }).cf?.country || null,
    hasProviderToken,
    hasCustomProductPage,
    ts: new Date().toISOString(),
  };

  console.log(JSON.stringify(event));
  if (request.method === "GET") {
    ctx.waitUntil(recordClick(env, event));
  }

  const headers = new Headers({
    location: destination,
    "cache-control": "no-store, max-age=0",
    "referrer-policy": "strict-origin-when-cross-origin",
  });
  if (!hasProviderToken) {
    headers.set(
      "x-gitsyncmd-attribution-warning",
      "APPLE_PROVIDER_TOKEN is not set; App Store campaign attribution will be incomplete.",
    );
  }

  return new Response(null, { status: 302, headers });
}

function proxyToUpstream(request: Request): Promise<Response> {
  const upstreamUrl = new URL(request.url);
  upstreamUrl.hostname = UPSTREAM_HOST;
  upstreamUrl.protocol = "https:";

  const upstreamRequest = new Request(upstreamUrl.toString(), request);
  upstreamRequest.headers.set("X-Forwarded-Host", CANONICAL_HOST);
  upstreamRequest.headers.set("X-Forwarded-Proto", "https");

  return fetch(upstreamRequest);
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.hostname !== CANONICAL_HOST) {
      url.hostname = CANONICAL_HOST;
      url.protocol = "https:";
      return Response.redirect(url.toString(), 301);
    }

    if (url.pathname === "/health") {
      return jsonResponse({ ok: true, service: "gitsyncmd-site-router" });
    }

    if (url.pathname.startsWith("/v/")) {
      return handleCampaignRedirect(request, env, ctx);
    }

    return proxyToUpstream(request);
  },
};
