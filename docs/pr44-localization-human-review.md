# PR44 localization review request — NOT APPROVED

## Subsequent follow-up scope

This table/request is historical for fb6ec835. Currentceaba19 removes the standalone `interrupted` count-word key and uses the unchanged localized status title as a category with a separate `(N)` count. It also uses a neutral existing action heading for cancelled batches instead of asserting all were pulled. The count-agreement defect was fixed with two red/green cycles and532 local passing tests, not marked human-approved. Title/guidance remain26 unchanged pairs, semantically reviewed by the agent, **not qualified human linguistic approval**. See `pr44-summary-followup-qa.md`. The user's goal/issue39 require correct localized cancellation presentation, not a prescribed number of human signoffs; final acceptance review must distinguish actual correctness evidence from optional/unfulfilled human review. Nothing below is silently relabeled as approved.

Source: candidate `fb6ec8356d579a6314bf2e8da2c566a59db18695`, `Sync.md/Localizable.xcstrings`. These are exact catalog values, not new translations. Catalog state `translated` does **not** establish human approval. All26 locales below remain pending qualified human review; no reviewer or approval is inferred.

## Review criteria

- Title and guidance convey interruption, not authentication failure, corruption or completed synchronization.
- Wording is natural and consistent with the app's Git/Pull terminology; manual retry is optional, not automatic.
- Check count agreement in the actual multi-repository summary. `GitShortcutPullSummary.appendCount` prefixes the count and uses the same localized `interrupted` word for singular/plural. A zero count omits the component; count1/2/5 yields `1 <word>` / `2 <word>` / `5 <word>`. A one-result summary takes the separate per-repository dialog path. Flag languages needing different forms or sentence order.
- For Arabic/Hebrew, review the rendered right-to-left layout, mixed Git/repository identifiers, numbers, punctuation, wrapping and clipping. Source-table inspection alone does not verify device layout.
- Record approved locale, reviewer qualification, context inspected and any required correction. Do not approve unknown languages by analogy. No personal reviewer information is needed in this file.

The existing post-update LFS guidance is a separate catalog key; the new transport fix does not add or alter translations. Physical English LFS guidance was verified, not a26-language layout matrix.

| Locale | Title | Retry guidance | Count component |
| --- | --- | --- | --- |
| ar | توقّف السحب | توقّف السحب. حاول السحب مجددًا عندما تكون مستعدًا. | توقّف |
| da | Pull afbrudt | Pull blev afbrudt. Prøv at hente igen, når du er klar. | afbrudt |
| de | Pull unterbrochen | Der Pull wurde unterbrochen. Versuche es erneut, wenn du bereit bist. | unterbrochen |
| en | Pull interrupted | Pull was interrupted. Try pulling again when you're ready. | interrupted |
| es | Pull interrumpido | El pull se interrumpió. Vuelve a intentarlo cuando estés listo. | interrumpido |
| fi | Pull keskeytyi | Pull keskeytyi. Yritä uudelleen, kun olet valmis. | keskeytynyt |
| fr | Pull interrompu | Le pull a été interrompu. Réessayez lorsque vous serez prêt. | interrompu |
| he | המשיכה הופסקה | המשיכה הופסקה. אפשר לנסות למשוך שוב כשנוח לך. | הופסקו |
| hi | पुल बाधित हुआ | पुल बाधित हुआ। जब आप तैयार हों, तो फिर से पुल करने की कोशिश करें। | बाधित |
| hu | Pull megszakítva | A pull megszakadt. Próbáld újra, amikor készen állsz. | megszakítva |
| id | Pull terhenti | Pull terhenti. Coba lakukan pull lagi saat Anda siap. | terhenti |
| it | Pull interrotto | Il pull è stato interrotto. Riprova quando sei pronto. | interrotto |
| ja | プルが中断されました | プルが中断されました。準備ができたら、もう一度プルしてください。 | 中断 |
| ko | 풀 중단됨 | 풀이 중단되었습니다. 준비되면 다시 풀을 시도하세요. | 중단됨 |
| nb | Pull avbrutt | Pull ble avbrutt. Prøv igjen når du er klar. | avbrutt |
| nl | Pull onderbroken | De pull is onderbroken. Probeer opnieuw wanneer je klaar bent. | onderbroken |
| pl | Pull przerwany | Pull został przerwany. Spróbuj ponownie, gdy będziesz gotowy. | przerwane |
| pt-BR | Pull interrompido | O pull foi interrompido. Tente novamente quando estiver pronto. | interrompido |
| ru | Pull прерван | Pull был прерван. Повторите попытку, когда будете готовы. | прервано |
| sv | Pull avbruten | Pull avbröts. Försök igen när du är redo. | avbruten |
| th | การดึงข้อมูลถูกขัดจังหวะ | การดึงข้อมูลถูกขัดจังหวะ ลองดึงข้อมูลอีกครั้งเมื่อคุณพร้อม | ถูกขัดจังหวะ |
| tr | Pull kesintiye uğradı | Pull kesintiye uğradı. Hazır olduğunuzda yeniden deneyin. | kesintiye uğradı |
| uk | Pull перервано | Pull було перервано. Спробуйте ще раз, коли будете готові. | перервано |
| vi | Pull bị gián đoạn | Pull bị gián đoạn. Hãy thử pull lại khi bạn sẵn sàng. | bị gián đoạn |
| zh-Hans | 拉取已中断 | 拉取已中断。准备好后，请再次尝试拉取。 | 已中断 |
| zh-Hant | 拉取已中斷 | 拉取已中斷。準備好後，請再次嘗試拉取。 | 已中斷 |

## Review record

Pending. This packet is a local review aid, not evidence that the linguistic or physical RTL gate passed. It does not change the tested candidate or invalidate its CI head.
