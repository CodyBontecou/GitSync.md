// Crops are [left, top, width, height] in the original 1080 × 2400 capture.
// Only rectangular crops and uniform scaling are applied to app UI.
const config = {
  app: { name: "GitSync.md" },
  reference: "aso-tool/.asc/aso/screenshots-editorial-v1",
  slides: [
    {
      id: "clone-real-repos",
      headline: "Git on\nyour Android.",
      subtext: "Pull, commit and push.\nYour repositories, wherever you are.",
      source: "vault.png", crop: [35, 145, 1010, 1800],
      figure: { left: 216, top: 590, width: 648 },
      caption: "Repository overview", note: "Your repositories, within reach.",
    },
    {
      id: "repo-health",
      headline: "Your notes.\nYour history.",
      subtext: "Pull remote changes.\nCommit and push when you’re ready.",
      source: "vault.png", crop: [50, 1515, 980, 420],
      figure: { left: 100, top: 850, width: 880 },
      caption: "GitSync.md · Repository actions", sketch: "workflow",
      note: "A small change. A little progress.",
    },
    {
      id: "git-control",
      headline: "Make room for\nyour next branch.",
      subtext: "Create and switch branches.\nKeep your work moving.",
      source: "git.png", crop: [50, 950, 980, 590],
      figure: { left: 120, top: 780, width: 840 },
      caption: "GitSync.md · Branches", sketch: "branch",
      note: "A new direction, whenever you need it.",
    },
    {
      id: "line-by-line-diff",
      headline: "Review before\nyou commit.",
      subtext: "See what changed,\none line at a time.",
      source: "diff.png", crop: [0, 145, 1080, 990],
      figure: { left: 120, top: 720, width: 840 },
      caption: "GitSync.md · Line by line diff",
      note: "A clearer view of every change.",
    },
    {
      id: "ready-to-push",
      headline: "A little care.\nA clean commit.",
      subtext: "Stage the changes you want.\nWrite a message. Commit and push.",
      source: "git.png", crop: [50, 1565, 980, 750],
      figure: { left: 120, top: 760, width: 840 },
      caption: "GitSync.md · Staging and commits", sketch: "workflow",
      note: "Choose what becomes part of your history.",
    },
  ],
};
