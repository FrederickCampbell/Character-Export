# Character Export architecture

Character Export collects factual RuneLite-observable account state and exposes it as stable local JSON. Collection, freshness, persistence, aggregation, and presentation remain separate concerns.

## Public contract

The public account-root files are domain oriented:

```text
character.json
quests.json
diaries.json
combat_achievements.json
collection_log.json
inventory.json
equipment.json
bank.json
seed_vault.json
storage.json
activities.json
progress.json
live.json
```

Everything under `.cache/` is internal implementation state. `diagnostics/` is optional observability output.

The original Character Export domains remain first-class files. New coverage is grouped into only four additional public domains: storage, activities, progress, and live.

## Core invariants

1. **Missing is unknown, never false.**
2. **Current state is not sticky.** Inventory, equipment, location, boosts, cooldowns and similar values are replaced by current observations.
3. **Monotonic ownership may merge.** Collection Log ownership can be safely retained and reconciled upward.
4. **Interaction-backed state is explicit.** Bank and Seed Vault require actual observations. Collection Log requires one full native Search reconciliation, which the plugin triggers automatically after the player's own log opens.
5. **Raw and semantic state stay separate.** Raw VarBits/VarPlayers live under `progress.json`; consumers decide workflow-specific meaning.
6. **Writes are atomic.** Consumers see a complete old document or a complete new document.
7. **Public schema is simpler than implementation.** Internal collectors can remain modular without creating public-file sprawl.
8. **UI freshness and exported freshness share one model.** Public mirrors are decorated from `DatasetFreshness`; internal collector fragments keep their original observation metadata untouched. The sidebar exposes one row for each of the 13 public account-root JSON files.

## Components

- `CharacterStateExporterPlugin` — RuneLite lifecycle/event orchestration
- `ExportDataset` — collector metadata, public-domain mapping, panel visibility
- `ExportLayout` — account/cache/diagnostics paths and migration
- `ExportStore` — fragment persistence, public mirrors, derived public views, and canonical public freshness decoration without mutating `.cache/` observations
- `AtomicFileWriter` — atomic UTF-8 writes with bounded Windows retry handling
- `DatasetFreshness` — current/saved/stale/unknown semantics
- `PublicDomainSnapshots` — derived `progress.json` and `storage.json`
- `UnifiedCharacterSnapshot` — internal all-in-one snapshot retained under `.cache/` for diagnostics/compatibility, not the public contract
- `ProgressFlagExporter` — curated raw game variables
- `TravelGateExporter` — generic travel requirement evaluation
- `UniversalStateExporter` — live, Slayer, Grand Exchange, recurring state; `live.json` is also persisted as its own first-class fragment/public mirror
- `CombatAchievementExporter` — dynamic CA catalogue/completion
- `DiaryWidgetParser` — testable diary markup parsing
- `CharacterStateExporterPanel` / `CharacterStateViewerPanel` — presentation only

## Collection Log model

Script `4100` transmits obtained item id + quantity. When the player's own Collection Log setup completes, Character Export invokes the native Search control and collection-log init script to cause a whole-log transmission. The POH Adventure Log varbit is checked before capture.

A complete snapshot remains a saved baseline across sessions. Later item transmissions merge into it. Reopening the log triggers a new full reconciliation. A partial incremental transmission before the first full read never falsely marks the snapshot complete.

## Threading

RuneLite client state is read on the client thread. File writes run through the single exporter writer. Swing mutations run on the EDT. Derived public views are coalesced so bursts of collector writes do not produce unnecessary disk churn.

## DWMS storage bridge

`storage.json` is sourced from Dude, Where's My Stuff? without creating a compile-time dependency on the external plugin. Character Export sends DWMS's public `storages-request` PluginMessage and consumes `storages-response` version 1 for canonical `{id, quantity}` item rows. In parallel, it snapshots all dotted RS-profile keys in the `dudewheresmystuff` namespace (excluding known configuration-only namespaces) so saved storage records that the message protocol does not normalize remain available.

The public `stores` map excludes Bank, Inventory, Equipment, and Seed Vault because those already have dedicated Character Export domain files. Their DWMS source records can still exist in `source_records` for provenance/debugging. A DWMS response proves that the source answered during the current RuneLite session; it does **not** rewrite an individual storage's observation timestamp or claim that an older saved storage observation is current.

DWMS filters bank/storage placeholders before its normalized storage state is persisted or returned. Character Export therefore marks normalized DWMS rows as `item_state: "owned"` and publishes `placeholder_reporting: "excluded_by_dwms"` instead of interpreting source silence as a zero-placeholder observation.

## Bank ownership semantics

`bank.json` is the authoritative bank ownership surface. Owned stacks stay under `items`; bank placeholders are exported separately under `placeholders`. Placeholder rows use the canonical normal item id for downstream requirement matching, preserve the placeholder variant as `raw_id`, and force `quantity: 0`. `item_count` remains owned-stack count and `placeholder_count` is independent. This makes it impossible for a placeholder to satisfy an ownership requirement while preserving the user's bank layout state.

## Adding a new collector

1. Add collector metadata to `ExportDataset` when a dedicated fragment/status is required.
2. Collect factual RuneLite state without downstream route assumptions.
3. Persist through `ExportStore`.
4. Define freshness and monotonic/current semantics explicitly.
5. Place new public data in an existing public domain whenever possible; create a new public JSON only when the domain is genuinely distinct.
6. Add tests for decoding and missing/stale edge cases.
7. Preserve third-party provenance/licenses for adapted code.
