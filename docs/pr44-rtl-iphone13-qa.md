# PR44 — scoped physical RTL warning observations

Candidatefb6ec835, unchanged signed isolated QA artifact498ee309. No source/signing/permission changes. Additional evidence is local and not in the tested commit or CI coverage.

## Arabic / landscape

Launched only QA with AppleLanguages(ar), AppleLocale ar_SA and the owned synthetic pull-cancel fixture. The phone was physically in landscape; hardware orientation was not changed by an unsupported tool. Native split navigation already showed the Vault, so the initial Home-card wait was an invalid entry-state assumption and failed. That raw failure is retained; actual Vault identity was then positively checked.

Held the real coordinator lease, invoked Arabic Pull, positively observed Arabic Cancel, and canceled. In the short landscape pane, scrolled only the owned Vault content to the warning. Native checks exposed the exact catalog title **توقّف السحب** and complete guidance **توقّف السحب. حاول السحب مجددًا عندما تكون مستعدًا.** Device screenshot inspected: RTL warning placement and wrapped body are visible in the left Vault pane, alongside the right sidebar and mixed Latin fixture identifiers. This is not a human linguistic or exhaustive legibility approval.

Released the holder, returned to its status label and verified released. Independent before/after native copies: all28 working/Git files byte-identical.

## Hebrew / portrait

The phone was now physically in portrait. The initial assumed Vault-entry wait failed because the app correctly showed Home; retained as another entry-state diagnostic, not a product failure. Positively identified the owned fixture card and entered its Vault using fresh observed frames.

Held the real coordinator lease, invoked Hebrew Pull, observed Hebrew Cancel and canceled. Native check confirmed **המשיכה הופסקה. אפשר לנסות למשוך שוב כשנוח לך.** The screenshot was inspected with the Hebrew warning title/body, RTL alignment, wrapped guidance, Latin fixture name/branch and native controls. No claim of count-grammar approval, all text sizes/themes, both orientations in both languages, or a26-language matrix.

Released holder and positively verified released. Independent before/after native copies: all28 files byte-identical.

## Limits / cleanup

Both are recorded live visual observations, not two automated regression passes. Raw initial failed navigation assumptions and Arabic element-seeking swipes are preserved. No source, assertion or translation was changed to obtain these views. Screenshots alone do not establish correct translation or count agreement. The language-review packet `docs/pr44-localization-human-review.md` accurately lists26locales×3source entries; **all remain pending qualified human review**.

Evidence: `docs/evidence/pr44-rtl-iphone13/` screenshots, raw recorder receipts and native byte manifests. Both recorders finished and both holders released. Normal QA restart with no fixture arguments restored empty-default onboarding; per-process locale overrides did not change the phone's system language. The latest private synthetic fixture is retained, and the original failed c101945 archive is outside the fixture reset path. Shipping app, real vaults, iPhone17 failure and unrelated Android changes remain untouched. No new server, shortcut or download.
