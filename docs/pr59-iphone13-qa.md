# PR #59 — physical Safari guide QA

Current follow-up **1de5394a6b3f41cf027b03507b9bb15cf3a58b90** integrates merged main738043e after PR44. Site/build/publish inputs are byte-identical to prior phone-tested ab27; fresh built article hash6d3ce31f equals the actual twice-tested phone LAN response. See `evidence/pr59-after44/`. Actual current website/validation logs were inspected:16 Python site checks,9 Node checks,2 release-verifier tests and source/built SEO pass. Actual checkouts309ea03 have main738/head1de parents and candidate whole tree49661c55. Downloaded CI artifact14098683233 read-only; its article SHA6d3ce31f exactly matches the actual twice-tested iPhone LAN response. No deployment or new handset pass is inferred. Fresh guard43557322204, Website43557322194 and publishing validation43557322180 passed; actual publish job skipped. Native XCTest43557322221 now passed:532 total/two explicit hosted StoreKit skips/zero failures, actual checkout309ea03/parents/tree49661c55 verified, Release build/privacy/noStorekit audit inspected. Both strict required checks pass. See `evidence/pr59-after44/pr59-current-final-tree-audit.json` and actual compressed log. No new phone pass, server or deployment claimed. **Draft/unmerged pending explicit automatic preview/production deployment authorization and fresh exact-head final audit.**

Historical original head `ad5d1cdcf983ccfde08cbd450152e43b19533a60`. Integrated current verified main `27146b7026de2e4960d812d4a071a45a122f9646` as `8919d35bc1e572bd448393ea8441b956ec60ef6c`. Tested site tree `321efd81796bd0c67848c4c88e5abbb8b27eeb66`.

## Requirement-to-evidence audit

| Requirement | Actual evidence |
| --- | --- |
| Explain normal merge sides | Source/built article and actual physical Safari show current branch=Ours, incoming=Theirs. Disposable real Git oracle checks index stages2/3 against phone/remote bytes. |
| Explain rebase sides and phone example | Physical Safari shows replay target/history=Ours, replayed commit=Theirs, explicitly illustrative rather than an app test. Real Git rebase conflict oracle confirms remote bytes in stage2 and phone bytes in stage3. |
| Cite primary source | Actual iPhone tap follows Git documentation link to the `--ours`/`--theirs` rebase note; positive text gate confirms correct destination; native Back returns to the changed section. |
| Preserve publication date and pricing | Residual product diff against actual main is only article body/dateModified plus its sitemap lastmod; original datePublished2026-10-26 remains. No pricing/source app change. Source/built SEO checks include metadata and JSON-LD. |
| Modification date/sitemap consistency | Both2026-11-03; source and built checks pass26HTML/25sitemap URLs, links, anchors, assets and reachability. |
| Real iPhone readability/navigation | Physical iPhone13 Pro Max/iOS27.2, native Safari, built candidate from public-site-only private LAN preview. Actual screenshot reviewed: readable wrapped merge/rebase/example, no observed clipping/horizontal overflow; native tree reports horizontal scrollbar1page. This is not a desktop viewport or browser-engine matrix proof. |
| Repeatable Argent QA | Final unchanged `qa-pr59-conflict-guide-final.yaml` **14/14 twice consecutively**, fresh scoped device services first then immediate warm replay;23.016s/21.789s, zero failed/errored/skipped steps or readiness warnings. Recorded walkthrough retained. No real vault or shipping app operation. |
| Current automated website checks | Source/built SEO passed,16 website Python tests,2 release-verifier tests, Node suites all passed. Raw logs retained. |
| Required exact-head CI/merge safety | CI43516781945 passed:518 total/two explicit hosted StoreKit skips/zero failures; Release/privacy/no-StoreKit resource audit logs inspected. Both website checks and guard43516781953 passed; publish skipped. Synthetic mergef5a3b620 has expected main27146b7/headab27ed44 parents and whole treeb21e38889b430cc2df9ecb81af92525364d1faeb identical to candidate and CI checkout; shipping project IDs intact. Must refresh before any qualifying merge. No bypass. |
| Deployment boundary | **BLOCKED pending explicit user permission**: `.github/workflows/website-publish.yml` runs on matching main pushes and automatically deploys preview then production. A qualifying merge would itself deploy. Asked user for authorization; PR converted to draft, autoMergeRequest was null. No workflow disabled, no deploy performed. |

## Additional limits / preserved evidence

Pi's persistent desktop browser is currently in visible mode and refused to start a task: existing browser was not force-restarted; message says finish native sign-in. Offline doctor passed8/8; window status visible. Profile/auth configuration left untouched. Thus no fresh Chromium390/1440 claim; earlier PR-body screenshots are historical, not substituted for current handset proof. Physical Safari observations above are direct handset evidence, independent of desktop browser availability.

Local preview serves **only the built public site**, not the repository (which contains unrelated files). No public tunnel, production publishing, account sign-in, native conflict resolution or Obsidian runtime test. Git oracle uses only disposable fully synthetic notes and credentials-free local Git.

Evidence: `docs/evidence/pr59-iphone13/`; key files `git-sides-oracle.json`, executable `check_git_sides.py`, `phone-pass1.json`, `phone-pass2.json`, `physical-safari-guide.png`, native article/Git-source/back trees and raw validation logs. Phone flow's LAN URL is an explicit local prerequisite, not production or a claimed portable remote endpoint.

**Disposition:** scoped content correction and iPhone Safari behavior passed. Exact-head CI/tree audit passed, but keep draft/unmerged while deployment authorization remains outstanding; refresh all gates immediately before any qualifying merge. Overall PR-by-PR objective is not complete.
