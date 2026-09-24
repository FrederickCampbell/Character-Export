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

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an internal all-in-one account snapshot from modular fragments.
 *
 * The public contract is domain-oriented; this aggregate is retained under
 * .cache for diagnostics and compatibility only.
 */
final class UnifiedCharacterSnapshot
{
    static final int SCHEMA_VERSION = 2;

    private UnifiedCharacterSnapshot()
    {
    }

    static Map<String, Object> build(
        Path accountDir,
        Gson gson,
        String pluginVersion,
        String sessionId)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("layout", "unified_character_snapshot");
        root.put("plugin_version", pluginVersion);
        root.put("generated_at", OffsetDateTime.now().toString());
        root.put("session_id", sessionId);

        Map<ExportDataset, Map<String, Object>> fragments =
            new LinkedHashMap<>();

        for (ExportDataset dataset : ExportDataset.values())
        {
            Map<String, Object> value =
                readFragment(accountDir, gson, dataset);
            if (value != null)
            {
                fragments.put(dataset, value);
            }
        }

        Map<String, Object> character =
            fragments.get(ExportDataset.CHARACTER);
        if (character != null)
        {
            copyIfPresent(character, root, "account_name");
            copyIfPresent(character, root, "account_hash");
            copyIfPresent(character, root, "world");
            copyIfPresent(character, root, "game_state");
            copyIfPresent(character, root, "world_types");
            copyIfPresent(character, root, "stats");
        }

        Map<String, Object> quests =
            fragments.get(ExportDataset.QUESTS);
        if (quests != null)
        {
            Map<String, Object> section = new LinkedHashMap<>();
            copyIfPresent(quests, section, "summary");
            Object values = quests.get("quests");
            if (values != null)
            {
                section.put("entries", values);
            }
            root.put("quests", section);
        }

        Map<String, Object> diaries =
            fragments.get(ExportDataset.DIARIES);
        if (diaries != null)
        {
            Map<String, Object> section = new LinkedHashMap<>();
            copyIfPresent(diaries, section, "summary");
            Object values = diaries.get("diaries");
            if (values != null)
            {
                section.put("areas", values);
            }
            root.put("diaries", section);
        }

        Map<String, Object> combat =
            fragments.get(ExportDataset.COMBAT_ACHIEVEMENTS);
        if (combat != null)
        {
            root.put(
                "combat_achievements",
                withoutEnvelope(combat)
            );
        }

        Map<String, Object> items = new LinkedHashMap<>();
        addItemSection(
            items,
            "inventory",
            fragments.get(ExportDataset.INVENTORY)
        );
        addItemSection(
            items,
            "equipment",
            fragments.get(ExportDataset.EQUIPMENT)
        );
        addItemSection(
            items,
            "bank",
            fragments.get(ExportDataset.BANK)
        );
        addItemSection(
            items,
            "seed_vault",
            fragments.get(ExportDataset.SEED_VAULT)
        );
        if (!items.isEmpty())
        {
            root.put("items", items);
        }

        Map<String, Object> collection =
            fragments.get(ExportDataset.COLLECTION_LOG);
        if (collection != null)
        {
            root.put(
                "collection_log",
                withoutEnvelope(collection)
            );
        }

        Map<String, Object> storage =
            fragments.get(ExportDataset.STORAGE);
        if (storage != null)
        {
            root.put(
                "storage_source",
                withoutEnvelope(storage)
            );
        }

        Map<String, Object> state =
            fragments.get(ExportDataset.STATE);
        if (state != null)
        {
            copyIfPresent(state, root, "slayer");
            copyIfPresent(state, root, "grand_exchange");
            copyIfPresent(state, root, "recurring");
        }

        Map<String, Object> travel =
            fragments.get(ExportDataset.TRAVEL_GATES);
        if (travel != null)
        {
            root.put("travel", compactTravel(travel));
        }

        Map<String, Object> freshness = new LinkedHashMap<>();
        for (ExportDataset dataset : ExportDataset.values())
        {
            DatasetFreshness.Observation observation =
                DatasetFreshness.evaluate(
                    dataset,
                    fragments.get(dataset),
                    ExportLayout.datasetPath(accountDir, dataset),
                    sessionId
                );

            freshness.put(
                dataset.key(),
                observation.toMap()
            );
        }
        root.put("freshness", freshness);

        return root;
    }

    static Map<String, Object> readFragment(
        Path accountDir,
        Gson gson,
        ExportDataset dataset)
    {
        if (accountDir == null || dataset == null)
        {
            return null;
        }

        return readObject(
            ExportLayout.datasetPath(accountDir, dataset),
            gson
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(
        Path file,
        Gson gson)
    {
        if (!Files.isRegularFile(file))
        {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(
            file,
            StandardCharsets.UTF_8))
        {
            Object value = gson.fromJson(reader, Object.class);
            if (value instanceof Map)
            {
                return (Map<String, Object>) value;
            }
        }
        catch (Exception ignored)
        {
            // One unreadable fragment should not suppress the entire public
            // character snapshot.
        }

        return null;
    }

    private static void addItemSection(
        Map<String, Object> items,
        String key,
        Map<String, Object> fragment)
    {
        if (fragment == null)
        {
            return;
        }

        Map<String, Object> section = new LinkedHashMap<>();
        copyIfPresent(fragment, section, "item_count");
        copyIfPresent(fragment, section, "items");
        items.put(key, section);
    }

    private static Map<String, Object> compactTravel(
        Map<String, Object> fragment)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fragment, out, "semantics");
        copyIfPresent(fragment, out, "gate_count");
        copyIfPresent(fragment, out, "satisfied_count");
        copyIfPresent(fragment, out, "blocked_count");
        copyIfPresent(fragment, out, "unknown_count");
        copyIfPresent(fragment, out, "cooldown_gate_count");

        Object gatesObject = fragment.get("gates");
        if (gatesObject instanceof List)
        {
            Map<String, Object> states = new LinkedHashMap<>();
            for (Object rowObject : (List<?>) gatesObject)
            {
                if (!(rowObject instanceof Map))
                {
                    continue;
                }

                Map<?, ?> row = (Map<?, ?>) rowObject;
                Object source = row.get("source");
                Object line = row.get("line");
                Object state = row.get("state");
                if (source == null ||
                    line == null ||
                    state == null)
                {
                    continue;
                }

                states.put(
                    String.valueOf(source) +
                        "#" +
                        String.valueOf(line),
                    String.valueOf(state)
                );
            }
            out.put("states", states);
        }

        return out;
    }

    private static Map<String, Object> withoutEnvelope(
        Map<String, Object> source)
    {
        Map<String, Object> out =
            new LinkedHashMap<>(source);

        for (String key : Arrays.asList(
            "exported_at",
            "plugin_version",
            "session_id",
            "reason",
            "account_name",
            "account_hash",
            "schema_version"))
        {
            out.remove(key);
        }

        return out;
    }

    private static void copyIfPresent(
        Map<String, Object> source,
        Map<String, Object> destination,
        String key)
    {
        Object value = source.get(key);
        if (value != null)
        {
            destination.put(key, value);
        }
    }
}
