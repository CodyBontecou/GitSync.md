const APP_STORE_ID = "6758960270";
export const DEFAULT_APP_STORE_URL = `https://apps.apple.com/us/app/gitsync-md/id${APP_STORE_ID}`;

export const platformLabels = {
  tt: "TikTok",
  ig: "Instagram Reels",
  yt: "YouTube Shorts",
  x: "X/Twitter",
  th: "Threads",
  web: "Website",
};

export const angleConfig = {
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

export const starterSlugs = [
  ...numberedSlugs("yt", "obsidian", 7),
  ...numberedSlugs("yt", "commits", 7),
  ...numberedSlugs("yt", "privacy", 7),
  "yt-gitclient-001",
  "yt-gitclient-002",
  "yt-selfhosted-001",
  "yt-selfhosted-002",
  "tt-obsidian-001",
  "tt-gitclient-001",
  "tt-selfhosted-001",
  "ig-obsidian-001",
  "ig-gitclient-001",
  "ig-selfhosted-001",
];

function numberedSlugs(platform, angle, count) {
  return Array.from({ length: count }, (_, index) => {
    const sequence = String(index + 1).padStart(3, "0");
    return `${platform}-${angle}-${sequence}`;
  });
}

export const defaultEnv = {
  APP_STORE_BASE_URL: DEFAULT_APP_STORE_URL,
  CPP_OBSIDIAN_PPID: "6d42eefc-9fd5-4f7a-8a0d-b7332e0eea7f",
  CPP_GITCLIENT_PPID: "76f9ef64-2400-44f5-8e81-74b1728fc606",
  CPP_SELFHOSTED_PPID: "874af699-91c3-4c2f-a76c-776229fe77e8",
};

export const explicitLinks = {
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

function cleanSlug(slug) {
  return String(slug || "")
    .trim()
    .toLowerCase()
    .replace(/^\/+|\/+$/g, "");
}

export function parseCampaignSlug(rawSlug) {
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

export function buildAppStoreUrl(link, env = {}) {
  const target = new URL(env.APP_STORE_BASE_URL || DEFAULT_APP_STORE_URL);
  const providerToken = env.APPLE_PROVIDER_TOKEN;
  const productPageId = link.ppidEnv ? env[link.ppidEnv] : undefined;

  if (productPageId) {
    target.searchParams.set("ppid", productPageId);
  }
  if (providerToken) {
    target.searchParams.set("pt", providerToken);
  }

  target.searchParams.set("ct", link.campaignToken);
  target.searchParams.set("mt", "8");
  return target.toString();
}

export function starterCampaignLinks(baseUrl = "https://gitsyncmd.app") {
  return starterSlugs
    .map((slug) => parseCampaignSlug(slug))
    .filter(Boolean)
    .map((link) => ({
      ...link,
      shortlink: `${baseUrl.replace(/\/+$/g, "")}/v/${link.slug}`,
    }));
}
