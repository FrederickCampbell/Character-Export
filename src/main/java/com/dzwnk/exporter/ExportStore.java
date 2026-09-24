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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single persistence layer for Character Export JSON.
 *
 * Internal collector fragments live under .cache. Stable, user-facing domain
 * files live at the account root. Direct domain files mirror one fragment;
 * derived domain files are rebuilt from multiple fragments.
 */
final class ExportStore
{
    enum WriteResult
    {
        CHANGED,
        UNCHANGED
    }

    private static final String INTERNAL_UNIFIED_FILE =
        "unified_character.json";

    private final Gson gson;
    private final String pluginVersion;
    private final String sessionId;
    private final Map<String, String> stablePayloadByPath =
        new ConcurrentHashMap<>();

    ExportStore(
        Gson gson,
        String pluginVersion,
        String sessionId)
    {
        this.gson = gson;
        this.pluginVersion = pluginVersion;
        this.sessionId = sessionId;
    }

    WriteResult writeDataset(
        Path accountDir,
        ExportDataset dataset,
        Map<String, Object> payload)
        throws IOException
    {
        if (dataset == null)
        {
            throw new IllegalArgumentException("dataset");
        }

        WriteResult fragmentResult = write(
            ExportLayout.datasetPath(accountDir, dataset),
            payload,
            dataset.interactionBacked()
        );

        WriteResult publicResult = WriteResult.UNCHANGED;
        if (dataset.mirrorFragmentPublicly() &&
            dataset.publicFileName() != null)
        {
            publicResult = write(
                ExportLayout.publicPath(
                    accountDir,
                    dataset.publicFileName()
                ),
                publicDatasetPayload(
                    accountDir,
                    dataset,
                    payload
                ),
                dataset.interactionBacked()
            );
        }

        return fragmentResult == WriteResult.CHANGED ||
            publicResult == WriteResult.CHANGED
            ? WriteResult.CHANGED
            : WriteResult.UNCHANGED;
    }

    WriteResult writePublic(
        Path accountDir,
        String fileName,
        Map<String, Object> payload)
        throws IOException
    {
        return write(
            ExportLayout.publicPath(accountDir, fileName),
            payload,
            false
        );
    }

    /**
     * Restores stable public domain files from saved internal fragments when an
     * account is selected. This preserves saved Bank/Seed Vault/Collection Log
     * data without requiring those interfaces to be reopened merely to recreate
     * the public files.
     */
    void restorePublicViews(Path accountDir) throws IOException
    {
        for (ExportDataset dataset : ExportDataset.values())
        {
            if (!dataset.mirrorFragmentPublicly() ||
                dataset.publicFileName() == null)
            {
                continue;
            }

            Path fragment = ExportLayout.datasetPath(accountDir, dataset);
            if (!Files.isRegularFile(fragment))
            {
                continue;
            }

            Map<String, Object> payload =
                UnifiedCharacterSnapshot.readFragment(
                    accountDir,
                    gson,
                    dataset
                );
            if (payload == null)
            {
                // Preserve the previous fallback behavior if a fragment exists
                // but cannot be parsed as an object.
                String json = new String(
                    Files.readAllBytes(fragment),
                    StandardCharsets.UTF_8
                );
                AtomicFileWriter.writeUtf8(
                    ExportLayout.publicPath(
                        accountDir,
                        dataset.publicFileName()
                    ),
                    ensureTrailingNewline(json)
                );
                continue;
            }

            write(
                ExportLayout.publicPath(
                    accountDir,
                    dataset.publicFileName()
                ),
                publicDatasetPayload(
                    accountDir,
                    dataset,
                    payload
                ),
                dataset.interactionBacked()
            );
        }

        // variables.json was a development-era raw mirror. progress.json is the
        // stable public home for raw variables plus travel/unlock state.
        Files.deleteIfExists(
            ExportLayout.publicPath(accountDir, "variables.json")
        );

        rebuildDerivedViews(accountDir);
    }

    /**
     * Rebuilds the derived public domain files and the internal all-in-one
     * snapshot used for debugging/compatibility. The unified snapshot is no
     * longer a public contract.
     */
    void rebuildDerivedViews(Path accountDir) throws IOException
    {
        Map<String, Object> unified =
            UnifiedCharacterSnapshot.build(
                accountDir,
                gson,
                pluginVersion,
                sessionId
            );
        AtomicFileWriter.writeUtf8(
            ExportLayout.cachePath(
                accountDir,
                INTERNAL_UNIFIED_FILE
            ),
            gson.toJson(unified) + System.lineSeparator()
        );

        writePublic(
            accountDir,
            "progress.json",
            PublicDomainSnapshots.buildProgress(
                accountDir,
                gson,
                pluginVersion,
                sessionId
            )
        );

        writePublic(
            accountDir,
            "storage.json",
            PublicDomainSnapshots.buildStorage(
                accountDir,
                gson,
                pluginVersion,
                sessionId
            )
        );
    }

    void clearStableCache()
    {
        stablePayloadByPath.clear();
    }


    private Map<String, Object> publicDatasetPayload(
        Path accountDir,
        ExportDataset dataset,
        Map<String, Object> payload)
    {
        Map<String, Object> out = new LinkedHashMap<>(payload);
        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                dataset,
                payload,
                ExportLayout.datasetPath(accountDir, dataset),
                sessionId
            );
        out.put("freshness", observation.toMap());
        return out;
    }

    private WriteResult write(
        Path outputPath,
        Map<String, Object> payload,
        boolean preserveObservationTime)
        throws IOException
    {
        String json = gson.toJson(payload);
        String stableJson = stableJson(
            payload,
            preserveObservationTime
        );
        String stableKey =
            outputPath.toAbsolutePath().normalize().toString();

        String previous = stablePayloadByPath.get(stableKey);
        if (stableJson.equals(previous) &&
            Files.isRegularFile(outputPath))
        {
            return WriteResult.UNCHANGED;
        }

        AtomicFileWriter.writeUtf8(
            outputPath,
            json + System.lineSeparator()
        );

        stablePayloadByPath.put(stableKey, stableJson);
        return WriteResult.CHANGED;
    }

    private String stableJson(
        Map<String, Object> payload,
        boolean preserveObservationTime)
    {
        Map<String, Object> stable = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet())
        {
            String key = entry.getKey();
            if ((!preserveObservationTime &&
                    ("exported_at".equals(key) ||
                     "generated_at".equals(key))) ||
                "reason".equals(key))
            {
                continue;
            }

            if (!preserveObservationTime &&
                "freshness".equals(key))
            {
                Object signature = stableFreshness(entry.getValue());
                if (signature != null)
                {
                    stable.put(key, signature);
                }
                continue;
            }

            stable.put(key, entry.getValue());
        }

        return gson.toJson(stable);
    }

    /**
     * Keeps semantic freshness transitions in the stable-write signature while
     * excluding clock-only fields. This lets restored derived views move from
     * saved/stale to current when their source fragments are re-observed in the
     * active session, without turning observed_at/age_hours updates into write
     * churn.
     */
    private Object stableFreshness(Object value)
    {
        if (!(value instanceof Map))
        {
            return value;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
        {
            if (entry.getKey() == null)
            {
                continue;
            }

            String key = String.valueOf(entry.getKey());
            if ("observed_at".equals(key) ||
                "age_hours".equals(key))
            {
                continue;
            }

            Object child = entry.getValue();
            if (child instanceof Map)
            {
                child = stableFreshness(child);
            }
            out.put(key, child);
        }
        return out;
    }

    private static String ensureTrailingNewline(String value)
    {
        if (value.endsWith("\n") || value.endsWith("\r"))
        {
            return value;
        }
        return value + System.lineSeparator();
    }
}
