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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared freshness semantics used by both the public snapshot and sidebar.
 */
final class DatasetFreshness
{
    enum Status
    {
        CURRENT("current"),
        SAVED("saved"),
        STALE("stale"),
        UNKNOWN("unknown");

        private final String jsonValue;

        Status(String jsonValue)
        {
            this.jsonValue = jsonValue;
        }

        String jsonValue()
        {
            return jsonValue;
        }
    }

    static final class Observation
    {
        private final ExportDataset dataset;
        private final Instant observedAt;
        private final String observedAtText;
        private final boolean currentSession;
        private final Status status;
        private final long ageMillis;

        private Observation(
            ExportDataset dataset,
            Instant observedAt,
            String observedAtText,
            boolean currentSession,
            Status status,
            long ageMillis)
        {
            this.dataset = dataset;
            this.observedAt = observedAt;
            this.observedAtText = observedAtText;
            this.currentSession = currentSession;
            this.status = status;
            this.ageMillis = ageMillis;
        }

        ExportDataset dataset()
        {
            return dataset;
        }

        Instant observedAt()
        {
            return observedAt;
        }

        boolean currentSession()
        {
            return currentSession;
        }

        Status status()
        {
            return status;
        }

        long ageMillis()
        {
            return ageMillis;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(
                "requires_human_interaction",
                dataset.interactionBacked()
            );

            if (dataset.refreshAction() != null)
            {
                out.put("refresh_action", dataset.refreshAction());
            }

            if (observedAtText != null)
            {
                out.put("observed_at", observedAtText);
            }

            out.put("current_session", currentSession);

            if (observedAt != null)
            {
                double ageHours = ageMillis / 3600000.0;
                out.put(
                    "age_hours",
                    Math.round(ageHours * 100.0) / 100.0
                );
            }

            out.put("status", status.jsonValue());
            return out;
        }
    }

    private DatasetFreshness()
    {
    }

    static Observation evaluate(
        ExportDataset dataset,
        Map<String, Object> fragment,
        Path fragmentPath,
        String currentSessionId)
    {
        return evaluate(
            dataset,
            fragment,
            fragmentPath,
            currentSessionId,
            Instant.now()
        );
    }

    static Observation evaluate(
        ExportDataset dataset,
        Map<String, Object> fragment,
        Path fragmentPath,
        String currentSessionId,
        Instant now)
    {
        if (dataset == null)
        {
            throw new IllegalArgumentException("dataset");
        }

        String observedAtText;
        String observedSessionId;

        if (dataset == ExportDataset.COLLECTION_LOG)
        {
            Map<String, Object> wholeLog =
                mapValue(fragment == null ? null : fragment.get("whole_log"));

            if (wholeLog == null ||
                !Boolean.TRUE.equals(wholeLog.get("snapshot_observed")))
            {
                return new Observation(
                    dataset,
                    null,
                    null,
                    false,
                    Status.UNKNOWN,
                    0L
                );
            }

            observedAtText =
                stringValue(wholeLog.get("last_observed_at"));
            observedSessionId =
                stringValue(wholeLog.get("observed_session_id"));
        }
        else
        {
            observedAtText =
                stringValue(fragment == null ? null : fragment.get("exported_at"));
            observedSessionId =
                stringValue(fragment == null ? null : fragment.get("session_id"));
        }

        Instant observedAt = parseInstant(observedAtText);
        if (observedAt == null &&
            fragment != null &&
            dataset != ExportDataset.COLLECTION_LOG &&
            fragmentPath != null)
        {
            try
            {
                if (Files.isRegularFile(fragmentPath))
                {
                    observedAt =
                        Files.getLastModifiedTime(fragmentPath).toInstant();
                }
            }
            catch (IOException ignored)
            {
                // Missing mtime is equivalent to unknown freshness.
            }
        }

        if (observedAt == null)
        {
            return new Observation(
                dataset,
                null,
                observedAtText,
                false,
                Status.UNKNOWN,
                0L
            );
        }

        boolean currentSession =
            currentSessionId != null &&
            observedSessionId != null &&
            currentSessionId.equals(observedSessionId);

        long ageMillis = Math.max(
            0L,
            Duration.between(observedAt, now).toMillis()
        );

        Status status;
        if (currentSession)
        {
            status = Status.CURRENT;
        }
        else if (dataset == ExportDataset.COLLECTION_LOG)
        {
            // Collection Log ownership is monotonic. Once a complete native
            // Search snapshot has been observed, it remains a valid saved
            // baseline until the next automatic reconciliation.
            status = Status.SAVED;
        }
        else if (dataset.interactionBacked() &&
            ageMillis >=
                ExportDataset.INTERACTIVE_STALE_AFTER.toMillis())
        {
            status = Status.STALE;
        }
        else
        {
            status = Status.SAVED;
        }

        return new Observation(
            dataset,
            observedAt,
            observedAtText,
            currentSession,
            status,
            ageMillis
        );
    }

    static Instant parseInstant(String value)
    {
        if (value == null)
        {
            return null;
        }

        try
        {
            return OffsetDateTime.parse(value).toInstant();
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value)
    {
        return value instanceof Map
            ? (Map<String, Object>) value
            : null;
    }

    private static String stringValue(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }
}
