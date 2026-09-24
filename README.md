# Character Export

Character Export is a RuneLite plugin that writes structured local JSON describing the character and account state RuneLite can observe.

The plugin is intended for local tools that need reliable, machine-readable account state without packet interception, process-memory access, or screen scraping.

## Public output

Each RuneScape account gets its own directory:

```text
.runelite/character-exporter/<Character Name>/
├── character.json
├── quests.json
├── diaries.json
├── combat_achievements.json
├── collection_log.json
├── inventory.json
├── equipment.json
├── bank.json
├── seed_vault.json
├── storage.json
├── activities.json
├── progress.json
├── live.json
├── .cache/
└── diagnostics/
```

The account-root JSON files are the public domain-oriented surface. `.cache/` contains modular collector fragments and internal derived state; consumers should not depend on cache file names or shapes.

### Original domain files

Character Export keeps the original plugin's easy-to-consume domain files:

- `character.json` — account identity and skills
- `quests.json` — quest states and counts
- `diaries.json` — achievement diary state
- `combat_achievements.json` — Combat Achievement catalogue and completion
- `collection_log.json` — durable Collection Log ownership plus supplemental page metadata
- `inventory.json` — carried items
- `equipment.json` — worn items
- `bank.json` — last observed bank contents
- `seed_vault.json` — last observed Seed Vault contents

### New domain files

- `storage.json` — DWMS-backed non-bank storage domain. Character Export consumes DWMS’s `storages-response` protocol for normalized item-bearing stores and also snapshots the complete dotted DWMS RS-profile storage namespace, preserving minigame/death/POH/STASH/Sailing records that the protocol does not normalize. Bank, Inventory, Equipment, and Seed Vault remain in their dedicated Character Export files.
- `activities.json` — Slayer, Grand Exchange, recurring account markers, and future activity state
- `progress.json` — travel requirement state, semantic unlocks, and curated raw VarBits/VarPlayers
- `live.json` — high-churn current state such as world, location, HP, Prayer, run energy, weight, special attack, spellbook, and combat level

`variables.json` is no longer a public file; raw variables live under `progress.json`.

## Item quantities and placeholders

`bank.json` keeps owned items and placeholders separate:

- `items` contains only actually owned bank stacks. Every row carries its real owned `quantity` and `item_state: "owned"`.
- `placeholders` contains bank placeholders. Each row uses the canonical real item id for requirement matching, preserves the raw placeholder id as `raw_id`, carries `quantity: 0`, and uses `item_state: "placeholder"`.
- `item_count` remains the number of owned bank stacks. `placeholder_count` is separate, so placeholders can never satisfy an item requirement by accident. RuneLite's bank filler UI token is ignored because it is neither owned stock nor a placeholder.

`storage.json` uses the same owned-item semantics: normalized DWMS item rows carry `item_state: "owned"` and `quantity` is the owned quantity. DWMS intentionally filters placeholders before persisting/responding, so Character Export marks that source capability explicitly rather than pretending it observed zero placeholders.

## Freshness and interaction-backed data

Missing data is **unknown**, never silently treated as empty.

Every public domain JSON now carries explicit freshness metadata. Direct domains expose a top-level `freshness` object with `status`, `current_session`, `requires_human_interaction`, optional `refresh_action`, and the original `observed_at`/`age_hours` when known. Derived `progress.json` exposes an aggregate plus component freshness; `storage.json` exposes freshness for the DWMS source snapshot while each normalized store retains its own observation state. Internal `.cache/` fragments are not rewritten merely to add public freshness.

- **Bank** becomes current when the Bank is observed in-game and becomes stale after 12 hours without a new observation.
- **Seed Vault** behaves the same way.
- **Collection Log** is different: ownership is monotonic. Once a complete snapshot exists, it remains a valid saved baseline rather than expiring after 12 hours.
- **Current-state domains** report current when observed during the active RuneLite session; older-session snapshots report saved rather than being silently treated as current.

## Collection Log

Open your **own** Collection Log once. Character Export automatically invokes the native Collection Log Search request and captures the resulting whole-log item transmissions. You do not need to click Search manually and you do not need to visit every boss/raid/clue page.

The exporter also listens for later obtained-item transmissions, so new unlocks can be merged while RuneLite is running. Opening the Collection Log again performs a full reconciliation.

The POH Adventure Log / another player's Collection Log is explicitly rejected so another player's data cannot contaminate the active account snapshot.

## Combat Achievements

The Combat Achievement catalogue is enumerated from the live game-cache tier enums at runtime rather than from a hard-coded task list. Completion is decoded from RuneLite's Combat Achievement completion VarPlayers and reconciled against live tier counters.

## Sidebar

The RuneLite sidebar lists all **13 public JSON snapshots** by domain, using the same freshness model written into those files. It also provides **Refresh Now**, **Open Folder**, and a searchable **Browse JSON** viewer. Hover a domain label to see its public file name.

`Refresh Now` samples everything RuneLite can currently observe. It does not open Bank, Seed Vault, or Collection Log interfaces for you; the sidebar calls out those in-game refresh requirements explicitly.

## Settings

RuneLite settings are grouped into **Core snapshots**, **Items & storage**, **Progress & live**, and a collapsed **Diagnostics** section. Existing export keys are preserved. `storage.json` remains automatic and uses DWMS when available; it is part of the stable public contract rather than a separate optional UI mode.

## Diagnostics

Enable **Debug logging** to write optional diagnostics under:

```text
.runelite/character-exporter/<Character Name>/diagnostics/
```

Diagnostics are not part of the public schema.

## Safety model

Character Export uses standard RuneLite plugin APIs. It does not intercept packets, read process memory, or automate gameplay actions. When the player's own Collection Log is opened, it programmatically invokes the log's read-only native Search control to request a complete ownership transmission.

## Building

```text
./gradlew test classes
```

Windows:

```text
gradlew.bat test classes
```

## Architecture

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for invariants, threading, persistence, and extension rules. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for provenance.

## Support

Issues: <https://github.com/DZWNK/Character-Export/issues>
