const config = {
  app: {
    name: "Sync.md",
    icon: "screenshots/app-icon.png",
  },

  brand: {
    primary: "#0A84FF",
    secondary: "#5AA8FF",
    background: "#F5F7FA",
    surface: "#FFFFFF",
    text: "#111111",
    textMuted: "#5C6570",
    accent: "#0A84FF",
  },

  theme: "clean-light",

  device: {
    color: "white",
  },

  slides: [
    {
      id: "clone-real-repos",
      layout: "text-top",
      screenshot: "screenshots/screen1-repos.png",
      label: "TRUE GIT CLONE",
      headline: "Your repos.\nOn your phone.",
      subtext: "Real codebases. Fully cloned.",
    },
    {
      id: "repo-health",
      layout: "text-top",
      screenshot: "screenshots/screen2-vault.png",
      label: "SEE WHAT CHANGED",
      headline: "Know repo state\nin seconds.",
      subtext: "Changed, staged, untracked — instantly.",
    },
    {
      id: "git-control",
      layout: "text-top",
      screenshot: "screenshots/screen3-git-control.png",
      label: "BRANCH WITH CONFIDENCE",
      headline: "Switch, stage,\nand commit fast.",
      subtext: "Branch, stage, commit in one flow.",
    },
    {
      id: "line-by-line-diff",
      layout: "text-top",
      screenshot: "screenshots/screen4-diff.png",
      label: "REVIEW EVERY LINE",
      headline: "Spot changes\nbefore you push.",
      subtext: "Review diffs before every push.",
    },
    {
      id: "ready-to-push",
      layout: "text-top",
      screenshot: "screenshots/screen5-staging.png",
      label: "SHIP FROM ANYWHERE",
      headline: "No laptop?\nStill in control.",
      subtext: "Clean commits, even on the go.",
    },
  ],

  featureGraphic: {
    headline: "Real Git workflows. Right from your Android phone.",
    subtext: "Available on Google Play",
    style: "gradient",
    screenshot: "screenshots/screen2-vault.png",
  },

  tablet: {
    enabled: false,
    sizes: ["10-inch"],
  },

  locales: ["en"],
};
