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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.PluginMessage;

/**
 * Adapter for Dude, Where's My Stuff? (DWMS) state.
 *
 * <p>The bridge intentionally has no compile-time dependency on DWMS. It uses
 * DWMS's public PluginMessage protocol for normalized item-bearing storages and
 * RuneLite's RS-profile configuration API as the durable fallback/source-of-truth
 * for every persisted DWMS storage record. This means Character Export still
 * retains saved storage state when DWMS is temporarily disabled or has not yet
 * answered a PluginMessage request in the current session.</p>
 */
final class DwmsStorageBridge
{
    static final String NAMESPACE = "dudewheresmystuff";
    static final String REQUEST_NAME = "storages-request";
    static final String RESPONSE_NAME = "storages-response";
    static final String REQUEST_SOURCE = "Character Export";
    static final int SUPPORTED_PROTOCOL_VERSION = 1;

    private static final long MIN_REASONABLE_TIMESTAMP_MS = 1_420_070_400_000L; // 2015-01-01 UTC
    private static final long MAX_FUTURE_SKEW_MS = 86_400_000L;
    private static final Pattern ITEM_ENTRY = Pattern.compile("^(\\d+)x(\\d+)$");

    private volatile int responseProtocolVersion = -1;
    private volatile List<Map<String, Object>> responseStorages = Collections.emptyList();
    private volatile boolean responseObservedThisSession;

    PluginMessage requestMessage()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", REQUEST_SOURCE);
        return new PluginMessage(NAMESPACE, REQUEST_NAME, data);
    }

    boolean accept(PluginMessage message)
    {
        if (message == null ||
            !NAMESPACE.equals(message.getNamespace()) ||
            !RESPONSE_NAME.equals(message.getName()) ||
            message.getData() == null)
        {
            return false;
        }

        Map<String, Object> data = message.getData();
        if (!REQUEST_SOURCE.equals(String.valueOf(data.get("target"))))
        {
            return false;
        }

        Object versionValue = data.get("version");
        int version = numberToInt(versionValue, -1);
        if (version <= 0)
        {
            return false;
        }

        Object storagesValue = data.get("storages");
        if (!(storagesValue instanceof List))
        {
            return false;
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object storageValue : (List<?>) storagesValue)
        {
            Map<String, Object> storage = normalizeStorage(storageValue);
            if (storage != null)
            {
                normalized.add(storage);
            }
        }

        normalized.sort(
            Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("category")))
                .thenComparing(row -> String.valueOf(row.get("name")))
                .thenComparing(row -> String.valueOf(row.get("items")))
        );

        responseProtocolVersion = version;
        responseStorages = Collections.unmodifiableList(normalized);
        responseObservedThisSession = true;
        return true;
    }

    void resetSession()
    {
        responseProtocolVersion = -1;
        responseStorages = Collections.emptyList();
        responseObservedThisSession = false;
    }

    Map<String, Object> buildSnapshot(
        ConfigManager configManager,
        String pluginVersion,
        String sessionId,
        String reason)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 1);
        root.put("plugin_version", pluginVersion);
        root.put("exported_at", OffsetDateTime.now().toString());
        root.put("session_id", sessionId);
        root.put("reason", reason);
        root.put("source", "dude-wheres-my-stuff");

        String profileKey = configManager == null
            ? null
            : configManager.getRSProfileKey();

        Map<String, Object> sourceState = new LinkedHashMap<>();
        sourceState.put("namespace", NAMESPACE);
        sourceState.put("plugin_message_protocol", SUPPORTED_PROTOCOL_VERSION);
        sourceState.put("live_response_observed_this_session", responseObservedThisSession);
        if (responseProtocolVersion > 0)
        {
            sourceState.put("response_protocol_version", responseProtocolVersion);
        }

        List<Map<String, Object>> stores = copyStorages(responseStorages);
        sourceState.put("normalized_item_storage_count", stores.size());
        sourceState.put("normalized_items_are_owned", true);
        sourceState.put("placeholder_reporting", "excluded_by_dwms");
        root.put("normalized_item_storages", stores);

        Map<String, Object> properties = readStorageProperties(configManager, profileKey);
        sourceState.put("profile_storage_property_count", properties.size());
        sourceState.put(
            "profile_state_available",
            profileKey != null && !properties.isEmpty()
        );
        root.put("profile_storage_properties", properties);
        root.put("source_state", sourceState);
        return root;
    }

    private static Map<String, Object> readStorageProperties(
        ConfigManager configManager,
        String profileKey)
    {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (configManager == null || profileKey == null)
        {
            return properties;
        }

        List<String> keys;
        try
        {
            keys = new ArrayList<>(
                configManager.getRSProfileConfigurationKeys(
                    NAMESPACE,
                    profileKey,
                    ""
                )
            );
        }
        catch (RuntimeException ex)
        {
            return properties;
        }

        Collections.sort(keys);
        for (String key : keys)
        {
            if (!isStorageStateKey(key))
            {
                continue;
            }

            String raw;
            try
            {
                raw = configManager.getConfiguration(
                    NAMESPACE,
                    profileKey,
                    key
                );
            }
            catch (RuntimeException ex)
            {
                continue;
            }

            if (raw == null)
            {
                continue;
            }

            Map<String, Object> decoded = decodeProperty(raw);
            int separator = key.indexOf('.');
            decoded.put("category", key.substring(0, separator));
            decoded.put("storage_key", key.substring(separator + 1));
            properties.put(key, decoded);
        }
        return properties;
    }

    /**
     * DWMS storage keys are manager.storage (and sometimes manager.storage.uuid).
     * UI/configuration keys are normally flat; the dotted exclusions below are
     * known non-storage namespaces. This deliberately permits unknown future
     * storage managers so Character Export does not regress to a fixed 12/169
     * style decoder.
     */
    static boolean isStorageStateKey(String key)
    {
        if (key == null || key.isEmpty() || key.indexOf('.') <= 0)
        {
            return false;
        }

        String lower = key.toLowerCase(Locale.ROOT);
        return !lower.startsWith("storeditemcountinclude.") &&
            !lower.startsWith("debug.");
    }

    static Map<String, Object> decodeProperty(String raw)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw", raw);

        String[] parts = raw.split(";", -1);
        int dataStart = 0;
        if (parts.length > 0)
        {
            Long timestamp = parseObservationTimestamp(parts[0]);
            if (timestamp != null)
            {
                out.put("last_updated_ms", timestamp);
                out.put("observed_at", isoTimestamp(timestamp));
                out.put("observation_state", "saved");
                dataStart = 1;
            }
            else
            {
                out.put("observation_state", "unknown");
            }
        }

        List<String> fields = new ArrayList<>();
        List<Map<String, Object>> detectedItems = new ArrayList<>();
        for (int i = dataStart; i < parts.length; i++)
        {
            fields.add(parts[i]);
            detectItems(parts[i], detectedItems);
        }
        out.put("field_count", fields.size());
        out.put("fields", fields);
        if (!detectedItems.isEmpty())
        {
            out.put("detected_items", detectedItems);
        }
        return out;
    }

    private static Long parseObservationTimestamp(String value)
    {
        try
        {
            long parsed = Long.parseLong(value);
            long max = System.currentTimeMillis() + MAX_FUTURE_SKEW_MS;
            return parsed >= MIN_REASONABLE_TIMESTAMP_MS && parsed <= max
                ? parsed
                : null;
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    private static String isoTimestamp(long epochMs)
    {
        return OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(epochMs),
            ZoneOffset.UTC
        ).toString();
    }

    private static void detectItems(
        String field,
        List<Map<String, Object>> destination)
    {
        if (field == null || field.isEmpty())
        {
            return;
        }

        String[] entries = field.split(",");
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (String entry : entries)
        {
            Matcher matcher = ITEM_ENTRY.matcher(entry);
            if (!matcher.matches())
            {
                return;
            }

            try
            {
                int id = Integer.parseInt(matcher.group(1));
                long quantity = Long.parseLong(matcher.group(2));
                if (id <= 0 || quantity <= 0)
                {
                    return;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("quantity", quantity);
                item.put("item_state", "owned");
                parsed.add(item);
            }
            catch (NumberFormatException ex)
            {
                return;
            }
        }

        destination.addAll(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeStorage(Object value)
    {
        if (!(value instanceof Map))
        {
            return null;
        }

        Map<?, ?> input = (Map<?, ?>) value;
        Object categoryValue = input.get("category");
        Object nameValue = input.get("name");
        Object itemsValue = input.get("items");
        if (categoryValue == null || nameValue == null || !(itemsValue instanceof List))
        {
            return null;
        }

        String category = String.valueOf(categoryValue);
        String name = String.valueOf(nameValue);
        if (isDedicatedCharacterExportContainer(category, name))
        {
            return null;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object itemValue : (List<?>) itemsValue)
        {
            if (!(itemValue instanceof Map))
            {
                continue;
            }

            Map<?, ?> itemInput = (Map<?, ?>) itemValue;
            int id = numberToInt(itemInput.get("id"), -1);
            long quantity = numberToLong(itemInput.get("quantity"), -1L);
            if (id <= 0 || quantity <= 0)
            {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("quantity", quantity);
            item.put("item_state", "owned");
            items.add(item);
        }

        items.sort(
            Comparator.comparingInt(row -> ((Number) row.get("id")).intValue())
        );
        if (items.isEmpty())
        {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "dude-wheres-my-stuff");
        out.put("category", category);
        out.put("name", name);

        long lastUpdated = numberToLong(input.get("lastUpdated"), -1L);
        if (lastUpdated > 0)
        {
            out.put("last_updated_ms", lastUpdated);
            out.put("observed_at", isoTimestamp(lastUpdated));
            out.put("observation_state", "saved");
        }
        else
        {
            out.put("observation_state", "unknown");
        }

        out.put("item_count", items.size());
        out.put("items", items);
        return out;
    }

    private static boolean isDedicatedCharacterExportContainer(
        String category,
        String name)
    {
        String categoryLower = category.toLowerCase(Locale.ROOT);
        String nameLower = name.toLowerCase(Locale.ROOT);

        if ("carryable".equals(categoryLower) &&
            ("inventory".equals(nameLower) || "equipment".equals(nameLower)))
        {
            return true;
        }

        return "world".equals(categoryLower) &&
            ("bank".equals(nameLower) || "seed vault".equals(nameLower));
    }

    private static List<Map<String, Object>> copyStorages(
        List<Map<String, Object>> storages)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> storage : storages)
        {
            Map<String, Object> copy = new LinkedHashMap<>(storage);
            Object items = storage.get("items");
            if (items instanceof List)
            {
                List<Map<String, Object>> itemCopies = new ArrayList<>();
                for (Object itemValue : (List<?>) items)
                {
                    if (!(itemValue instanceof Map))
                    {
                        continue;
                    }

                    Map<String, Object> itemCopy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry :
                        ((Map<?, ?>) itemValue).entrySet())
                    {
                        if (entry.getKey() != null)
                        {
                            itemCopy.put(
                                String.valueOf(entry.getKey()),
                                entry.getValue()
                            );
                        }
                    }
                    itemCopies.add(itemCopy);
                }
                copy.put("items", itemCopies);
            }
            out.add(copy);
        }
        return out;
    }

    private static int numberToInt(Object value, int fallback)
    {
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        try
        {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        }
        catch (NumberFormatException ex)
        {
            return fallback;
        }
    }

    private static long numberToLong(Object value, long fallback)
    {
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        try
        {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException ex)
        {
            return fallback;
        }
    }
}
