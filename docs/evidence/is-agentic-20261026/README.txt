GitSync.md agent readiness iteration — October 26, 2026

Baseline Is Agentic score: 75/100 (report.json).
Fresh Is Agentic browser scan: 100/100 (score-100.png).
Essential checks: 6/6, 80/80. Recommended: 17.8/20. Bonus: +3.
The displayed score is capped at 100.

Published the Markdown negotiation router, generated Markdown documents,
llms.txt, agent instructions, pricing, About and Contact pages.
All 22 HTML pages match the validated generated build. The 21 indexable
URLs are in the sitemap. Nine live representation/error checks passed.
12 Python tests, 7 router tests, 2 testimonial tests, typecheck and dry-run passed.
Homepage, About and Contact score four Lighthouse 100s in production.

External report persistence issue:
The browser displayed the new 100-point scan with 'Saving completed scan…'.
Retrying the official scan stream emitted scan_complete and scan_archived
for the new 2026-10-26T13:33:23.746+00:00 result, with all targeted fixes passing.
However, the public report page in a new tab and the read-only public API
still serve the 75-point snapshot from 13:15:57.691Z. This is recorded rather
than described as a successful public snapshot refresh.

The stream's provider raw score uses a different model from Is Agentic's
weighted displayed score. The provider JSON is saved unchanged in
after-provider-result.json; the actual displayed 100 is captured in the PNG.
No search-ranking improvement is claimed. Brand discovery remains a finding.
See release.json for deployments, rollback references, and validation details.
