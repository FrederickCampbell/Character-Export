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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical metadata for internal Character Export datasets.
 *
 * Collector fragments remain under {@code .cache/}. Datasets that correspond
 * directly to a public domain file declare that file here. Derived public files
 * such as progress.json are built from more than one collector fragment.
 */
enum ExportDataset
{
    STATE(
        "state",
        "state.json",
        "Activities",
        "Live",
        null,
        false,
        "activities.json",
        true,
        true
    ),
    LIVE(
        "live",
        "live.json",
        "Live",
        "Live",
        null,
        false,
        "live.json",
        true,
        true
    ),
    CHARACTER(
        "character",
        "character.json",
        "Character",
        "Refresh",
        null,
        false,
        "character.json",
        true,
        true
    ),
    QUESTS(
        "quests",
        "quests.json",
        "Quests",
        "Refresh",
        null,
        false,
        "quests.json",
        true,
        true
    ),
    DIARIES(
        "diaries",
        "diaries.json",
        "Diaries",
        "Refresh",
        null,
        false,
        "diaries.json",
        true,
        true
    ),
    COMBAT_ACHIEVEMENTS(
        "combat_achievements",
        "combat_achievements.json",
        "Combat Ach.",
        "Refresh",
        null,
        false,
        "combat_achievements.json",
        true,
        true
    ),
    INVENTORY(
        "inventory",
        "inventory.json",
        "Inventory",
        "Refresh",
        null,
        false,
        "inventory.json",
        true,
        true
    ),
    EQUIPMENT(
        "equipment",
        "equipment.json",
        "Equipment",
        "Refresh",
        null,
        false,
        "equipment.json",
        true,
        true
    ),
    BANK(
        "bank",
        "bank.json",
        "Bank",
        "Open to refresh",
        "Open the Bank",
        true,
        "bank.json",
        true,
        true
    ),
    SEED_VAULT(
        "seed_vault",
        "seed_vault.json",
        "Seed Vault",
        "Open to refresh",
        "Open the Seed Vault",
        true,
        "seed_vault.json",
        true,
        true
    ),
    COLLECTION_LOG(
        "collection_log",
        "collection_log.json",
        "Collection Log",
        "Open log once",
        "Open your Collection Log",
        true,
        "collection_log.json",
        true,
        true
    ),
    STORAGE(
        "storage",
        "storage_source.json",
        "Storage",
        "DWMS snapshot",
        null,
        false,
        "storage.json",
        false,
        true
    ),
    TRAVEL_GATES(
        "travel_gates",
        "travel_gates.json",
        "Travel State",
        "Live",
        null,
        false,
        null,
        false,
        false
    ),
    PROGRESS_FLAGS(
        "progress_flags",
        "progress_flags.json",
        "Progress",
        "Live",
        null,
        false,
        "progress.json",
        false,
        true
    );

    static final Duration INTERACTIVE_STALE_AFTER = Duration.ofHours(12);

    private static final Map<String, ExportDataset> BY_KEY;
    private static final List<ExportDataset> PANEL_DATASETS;

    static
    {
        Map<String, ExportDataset> byKey = new LinkedHashMap<>();
        List<ExportDataset> panelDatasets = new ArrayList<>();

        for (ExportDataset dataset : values())
        {
            byKey.put(dataset.key, dataset);
            if (dataset.panelVisible)
            {
                panelDatasets.add(dataset);
            }
        }

        BY_KEY = Collections.unmodifiableMap(byKey);
        PANEL_DATASETS = Collections.unmodifiableList(panelDatasets);
    }

    private final String key;
    private final String fragmentFileName;
    private final String label;
    private final String readyHint;
    private final String refreshAction;
    private final boolean interactionBacked;
    private final String publicFileName;
    private final boolean mirrorFragmentPublicly;
    private final boolean panelVisible;

    ExportDataset(
        String key,
        String fragmentFileName,
        String label,
        String readyHint,
        String refreshAction,
        boolean interactionBacked,
        String publicFileName,
        boolean mirrorFragmentPublicly,
        boolean panelVisible)
    {
        this.key = key;
        this.fragmentFileName = fragmentFileName;
        this.label = label;
        this.readyHint = readyHint;
        this.refreshAction = refreshAction;
        this.interactionBacked = interactionBacked;
        this.publicFileName = publicFileName;
        this.mirrorFragmentPublicly = mirrorFragmentPublicly;
        this.panelVisible = panelVisible;
    }

    String key()
    {
        return key;
    }

    String fragmentFileName()
    {
        return fragmentFileName;
    }

    String label()
    {
        return label;
    }

    String readyHint()
    {
        return readyHint;
    }

    String refreshAction()
    {
        return refreshAction;
    }

    boolean interactionBacked()
    {
        return interactionBacked;
    }

    String publicFileName()
    {
        return publicFileName;
    }

    boolean mirrorFragmentPublicly()
    {
        return mirrorFragmentPublicly;
    }

    boolean panelVisible()
    {
        return panelVisible;
    }

    static List<ExportDataset> panelDatasets()
    {
        return PANEL_DATASETS;
    }

    static ExportDataset fromKey(String key)
    {
        return key == null ? null : BY_KEY.get(key);
    }
}
