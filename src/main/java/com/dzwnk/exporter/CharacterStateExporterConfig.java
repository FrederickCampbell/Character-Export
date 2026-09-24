/*
 * Copyright (c) 2026, DZWNK
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.dzwnk.exporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("characterStateExporter")
public interface CharacterStateExporterConfig extends Config
{
    @ConfigSection(
        name = "Core snapshots",
        description = "Primary account-state JSON snapshots.",
        position = 0
    )
    String coreSection = "coreSection";

    @ConfigSection(
        name = "Items & storage",
        description = "Item snapshots. storage.json is populated automatically from Dude, Where's My Stuff? when available.",
        position = 1
    )
    String itemsSection = "itemsSection";

    @ConfigSection(
        name = "Progress & live",
        description = "Progress telemetry, travel gates, activities, and live player state.",
        position = 2
    )
    String progressSection = "progressSection";

    @ConfigSection(
        name = "Diagnostics",
        description = "Optional developer diagnostics. Public exports do not depend on these files.",
        position = 3,
        closedByDefault = true
    )
    String diagnosticsSection = "diagnosticsSection";

    @ConfigItem(
        keyName = "exportCharacter",
        name = "Character",
        description = "Write character.json with account identity, world, and skills.",
        position = 0,
        section = coreSection
    )
    default boolean exportCharacter()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportQuests",
        name = "Quests",
        description = "Write quests.json with current quest states and counts.",
        position = 1,
        section = coreSection
    )
    default boolean exportQuests()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportDiaries",
        name = "Achievement diaries",
        description = "Write diaries.json with achievement diary completion state.",
        position = 2,
        section = coreSection
    )
    default boolean exportDiaries()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportCombatAchievements",
        name = "Combat achievements",
        description = "Write combat_achievements.json with catalogue, tier, and task completion state.",
        position = 3,
        section = coreSection
    )
    default boolean exportCombatAchievements()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportInventory",
        name = "Inventory",
        description = "Write inventory.json from the carried inventory.",
        position = 0,
        section = itemsSection
    )
    default boolean exportInventory()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportEquipment",
        name = "Equipment",
        description = "Write equipment.json from worn equipment.",
        position = 1,
        section = itemsSection
    )
    default boolean exportEquipment()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportBank",
        name = "Bank",
        description = "Write bank.json when the Bank container is observable. Open the Bank to refresh it.",
        position = 2,
        section = itemsSection
    )
    default boolean exportBank()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportSeedVault",
        name = "Seed Vault",
        description = "Write seed_vault.json when the Seed Vault is observable. Open the Seed Vault to refresh it.",
        position = 3,
        section = itemsSection
    )
    default boolean exportSeedVault()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportCollectionLog",
        name = "Collection Log",
        description = "Write collection_log.json. Open your own Collection Log once for a complete ownership snapshot.",
        position = 4,
        section = itemsSection
    )
    default boolean exportCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportRawVariables",
        name = "Progress variables",
        description = "Write curated VarBit/VarPlayer telemetry into progress.json.",
        position = 0,
        section = progressSection
    )
    default boolean exportRawVariables()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportTravelState",
        name = "Travel gates",
        description = "Write travel requirement state into progress.json without claiming full transport availability.",
        position = 1,
        section = progressSection
    )
    default boolean exportTravelState()
    {
        return true;
    }

    @ConfigItem(
        keyName = "exportUniversalState",
        name = "Live & activities",
        description = "Write activities.json and live.json with Slayer, Grand Exchange, recurring markers, and current player state.",
        position = 2,
        section = progressSection
    )
    default boolean exportUniversalState()
    {
        return true;
    }

    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging",
        description = "Write exporter.log, status.json, and recent_events.json under diagnostics/. Takes effect immediately.",
        position = 0,
        section = diagnosticsSection
    )
    default boolean debugLogging()
    {
        return false;
    }
}
