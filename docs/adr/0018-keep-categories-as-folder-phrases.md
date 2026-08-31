# Keep Categories as folder-Phrases

Status: accepted, supersedes ADR-0012

ADR-0012's migration to flat Categories was never implemented, and the code moved the opposite way: every client now persists Categories as folder-Phrase records (a Phrase whose `linkedBoardId` self-references, with membership via `Phrase.parentId`), partitioned by `GetPhrasesAndCategoriesUseCase` and owned by the shared phrase state module. Wingmate keeps the folder-Phrase model as the intended one and deletes the flat per-platform Category stores. Legacy flat Category data — on device and inside old backups — is converted to folder-Phrases with the same IDs so membership survives: conversions are idempotent, suffix colliding names, and never merge duplicates.

Considered alternatives: implementing ADR-0012's flat model (rejected — no client's UI path uses it anymore, and it would keep two models alive during migration); dropping legacy flat data on restore (rejected — it would silently delete real vocabulary).
