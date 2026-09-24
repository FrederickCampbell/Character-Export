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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the small set of public domain files that aggregate multiple internal
 * collector fragments.
 */
final class PublicDomainSnapshots
{
    static final int SCHEMA_VERSION = 1;

    private PublicDomainSnapshots()
    {
    }

    static Map<String, Object> buildProgress(
        java.nio.file.Path accountDir,
        Gson gson,
        String pluginVersion,
        String sessionId)
    {
        Map<String, Object> root = envelope(pluginVersion, sessionId);

        Map<String, Object> raw = UnifiedCharacterSnapshot.readFragment(
            accountDir,
            gson,
            ExportDataset.PROGRESS_FLAGS
        );
        if (raw != null)
        {
            root.put("raw_variables", withoutEnvelope(raw));
        }

        Map<String, Object> travel = UnifiedCharacterSnapshot.readFragment(
            accountDir,
            gson,
            ExportDataset.TRAVEL_GATES
        );
        if (travel != null)
        {
            root.put("travel", compactTravel(travel));
        }

        Map<String, DatasetFreshness.Observation> progressFreshness =
            new LinkedHashMap<>();
        progressFreshness.put(
            "raw_variables",
            freshness(
                accountDir,
                ExportDataset.PROGRESS_FLAGS,
                raw,
                sessionId
            )
        );
        progressFreshness.put(
            "travel",
            freshness(
                accountDir,
                ExportDataset.TRAVEL_GATES,
                travel,
                sessionId
            )
        );
        root.put(
            "freshness",
            aggregateFreshness(progressFreshness, "components")
        );

        // Semantic unlocks can be added here without exposing another public
        // file or changing the raw-variable collector contract.
        root.put("unlocks", Collections.emptyMap());
        return root;
    }

    static Map<String, Object> buildStorage(
        java.nio.file.Path accountDir,
        Gson gson,
        String pluginVersion,
        String sessionId)
    {
        Map<String, Object> root = envelope(pluginVersion, sessionId);
        Map<String, Object> fragment = UnifiedCharacterSnapshot.readFragment(
            accountDir,
            gson,
            ExportDataset.STORAGE
        );

        Map<String, Object> storageFreshness =
            freshness(
                accountDir,
                ExportDataset.STORAGE,
                fragment,
                sessionId
            ).toMap();
        storageFreshness.put("scope", "source_snapshot");
        root.put("freshness", storageFreshness);

        Map<String, Object> itemSemantics = new LinkedHashMap<>();
        itemSemantics.put("items", "owned");
        itemSemantics.put("quantity", "owned_quantity");
        itemSemantics.put(
            "placeholder_reporting",
            "dude-wheres-my-stuff excludes placeholders"
        );
        root.put("item_semantics", itemSemantics);

        if (fragment == null ||
            !"dude-wheres-my-stuff".equals(fragment.get("source")))
        {
            root.put("implemented_sources", Collections.emptyList());
            root.put("stores", Collections.emptyMap());
            root.put("source_records", Collections.emptyMap());
            return root;
        }

        root.put(
            "implemented_sources",
            Collections.singletonList("dude-wheres-my-stuff")
        );

        Object sourceState = fragment.get("source_state");
        if (sourceState instanceof Map)
        {
            root.put("source_state", sourceState);
        }

        Map<String, Object> stores = new LinkedHashMap<>();
        Object normalizedObject = fragment.get("normalized_item_storages");
        if (normalizedObject instanceof List)
        {
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            for (Object rowObject : (List<?>) normalizedObject)
            {
                if (!(rowObject instanceof Map))
                {
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> row =
                    new LinkedHashMap<>((Map<String, Object>) rowObject);
                String category = stringValue(row.get("category"));
                String name = stringValue(row.get("name"));
                if (category == null || name == null)
                {
                    continue;
                }

                String baseKey =
                    "dwms:" + slug(category) + ":" + slug(name);
                int occurrence = occurrences.merge(baseKey, 1, Integer::sum);
                String key = occurrence == 1
                    ? baseKey
                    : baseKey + "#" + occurrence;
                stores.put(key, row);
            }
        }
        if (stores.isEmpty())
        {
            addProfileDetectedItemStores(
                stores,
                fragment.get("profile_storage_properties")
            );
        }
        root.put("stores", stores);

        Map<String, Object> sourceRecords = new LinkedHashMap<>();
        Map<String, Object> dwms = new LinkedHashMap<>();
        Object properties = fragment.get("profile_storage_properties");
        if (properties instanceof Map)
        {
            dwms.put("profile_storage_properties", properties);
        }
        sourceRecords.put("dude-wheres-my-stuff", dwms);
        root.put("source_records", sourceRecords);
        return root;
    }

    private static DatasetFreshness.Observation freshness(
        java.nio.file.Path accountDir,
        ExportDataset dataset,
        Map<String, Object> fragment,
        String sessionId)
    {
        return DatasetFreshness.evaluate(
            dataset,
            fragment,
            ExportLayout.datasetPath(accountDir, dataset),
            sessionId
        );
    }

    private static Map<String, Object> aggregateFreshness(
        Map<String, DatasetFreshness.Observation> observations,
        String scope)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();

        DatasetFreshness.Status overall = DatasetFreshness.Status.CURRENT;
        boolean currentSession = !observations.isEmpty();
        boolean requiresHumanInteraction = false;

        for (Map.Entry<String, DatasetFreshness.Observation> entry :
            observations.entrySet())
        {
            DatasetFreshness.Observation observation = entry.getValue();
            components.put(entry.getKey(), observation.toMap());
            overall = lessFresh(overall, observation.status());
            currentSession &= observation.currentSession();
            requiresHumanInteraction |=
                observation.dataset().interactionBacked();
        }

        out.put("scope", scope);
        out.put("status", overall.jsonValue());
        out.put("current_session", currentSession);
        out.put(
            "requires_human_interaction",
            requiresHumanInteraction
        );
        out.put("components", components);
        return out;
    }

    private static DatasetFreshness.Status lessFresh(
        DatasetFreshness.Status left,
        DatasetFreshness.Status right)
    {
        return freshnessRank(right) > freshnessRank(left)
            ? right
            : left;
    }

    private static int freshnessRank(DatasetFreshness.Status status)
    {
        switch (status)
        {
            case UNKNOWN:
                return 3;
            case STALE:
                return 2;
            case SAVED:
                return 1;
            case CURRENT:
            default:
                return 0;
        }
    }

    private static Map<String, Object> envelope(
        String pluginVersion,
        String sessionId)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("plugin_version", pluginVersion);
        root.put("generated_at", OffsetDateTime.now().toString());
        root.put("session_id", sessionId);
        return root;
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
                if (source == null || line == null || state == null)
                {
                    continue;
                }

                states.put(
                    String.valueOf(source) + "#" + String.valueOf(line),
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
        Map<String, Object> out = new LinkedHashMap<>(source);
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

    @SuppressWarnings("unchecked")
    private static void addProfileDetectedItemStores(
        Map<String, Object> stores,
        Object propertiesObject)
    {
        if (!(propertiesObject instanceof Map))
        {
            return;
        }

        for (Map.Entry<?, ?> entry :
            ((Map<?, ?>) propertiesObject).entrySet())
        {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map))
            {
                continue;
            }

            Map<String, Object> property =
                (Map<String, Object>) entry.getValue();
            Object items = property.get("detected_items");
            if (!(items instanceof List) || ((List<?>) items).isEmpty())
            {
                continue;
            }

            String sourceKey = String.valueOf(entry.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", "dude-wheres-my-stuff-profile");
            row.put("source_key", sourceKey);
            copyIfPresent(property, row, "category");
            copyIfPresent(property, row, "storage_key");
            copyIfPresent(property, row, "last_updated_ms");
            copyIfPresent(property, row, "observed_at");
            copyIfPresent(property, row, "observation_state");
            row.put("item_count", ((List<?>) items).size());
            row.put("items", items);

            stores.put(
                "dwms_profile:" + slug(sourceKey),
                row
            );
        }
    }

    private static String slug(String value)
    {
        String slug = value.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "unnamed" : slug;
    }

    private static String stringValue(Object value)
    {
        return value == null ? null : String.valueOf(value);
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
