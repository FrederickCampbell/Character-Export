# Third-party notices

Character Export uses RuneLite APIs and was developed with reference to open-source RuneLite plugins and state-export implementations.

## RuneLite

RuneLite is distributed under the BSD 2-Clause License. Character Export uses RuneLite API/client interfaces supplied as build dependencies.

Source: <https://github.com/runelite/runelite>

## Combat Achievement Exporter

`CombatAchievementExporter.java` adapts the runtime catalogue-enumeration and completion-bit approach used by `cdfisher/ca-export`.

Copyright (c) 2025, cdfisher <https://github.com/cdfisher>

Source: <https://github.com/cdfisher/ca-export>

The adapted source retains the applicable BSD 2-Clause notice in the source file.

## Collection Log full-read technique

The automatic Collection Log reconciliation follows the open-source WikiSync/RuneProfile technique: after the player's own Collection Log opens, invoke its native Search operation and collection-log init script, then collect script-4100 item/quantity transmissions. RuneProfile explicitly attributes this technique to WikiSync under the BSD 2-Clause License.

References:

- <https://github.com/ReinhardtR/runeprofile-plugin>
- <https://github.com/weirdgloop/WikiSync>

## Additional reference implementations

The following projects were researched while designing Character Export's broader state model. Unless source code is directly copied or adapted in a file carrying the relevant license notice, inclusion here records design provenance rather than a bundled runtime dependency.

- A-Kimpton/account-data-exporter — BSD 2-Clause
- Dude, Where's My Stuff? — BSD 2-Clause
- RuneLite Time Tracking
- RuneLite Daily Task Indicator
- Shortest Path transport requirement corpus
- Collection Log exporter implementations

Future direct code transplants must preserve the donor project's required copyright and license notices.

### DWMS interoperability note (Character Export 0.9.1-refactor-rc3)

Character Export interoperates with the public `storages-request` / `storages-response`
PluginMessage protocol exposed by Dude, Where's My Stuff? and reads that plugin's
persisted RuneLite RS-profile configuration namespace through RuneLite's public
ConfigManager API. Character Export does not bundle DWMS classes or source code.
The protocol behavior was verified against upstream commit
`b8490cf3fe552615e8f6093c3b28f1fb395b34ea`.
