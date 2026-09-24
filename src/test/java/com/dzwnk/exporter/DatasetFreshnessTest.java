package com.dzwnk.exporter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class DatasetFreshnessTest
{
    private static final Instant NOW =
        Instant.parse("2026-09-23T16:00:00Z");

    @Test
    public void bankBecomesStaleAtTwelveHours()
    {
        Map<String, Object> fragment =
            envelope(
                NOW.minusSeconds(12L * 60L * 60L),
                "old-session"
            );

        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                ExportDataset.BANK,
                fragment,
                null,
                "current-session",
                NOW
            );

        Assert.assertEquals(
            DatasetFreshness.Status.STALE,
            observation.status()
        );
    }

    @Test
    public void currentSessionWinsForInteractiveDataset()
    {
        Map<String, Object> fragment =
            envelope(
                NOW.minusSeconds(13L * 60L * 60L),
                "current-session"
            );

        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                ExportDataset.BANK,
                fragment,
                null,
                "current-session",
                NOW
            );

        Assert.assertEquals(
            DatasetFreshness.Status.CURRENT,
            observation.status()
        );
        Assert.assertTrue(observation.currentSession());
    }

    @Test
    public void collectionPageScrapeIsNotWholeLogFreshness()
    {
        Map<String, Object> fragment =
            envelope(NOW, "current-session");

        Map<String, Object> wholeLog =
            new LinkedHashMap<>();
        wholeLog.put("snapshot_observed", false);
        fragment.put("whole_log", wholeLog);

        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                ExportDataset.COLLECTION_LOG,
                fragment,
                null,
                "current-session",
                NOW
            );

        Assert.assertEquals(
            DatasetFreshness.Status.UNKNOWN,
            observation.status()
        );
        Assert.assertFalse(observation.currentSession());
    }

    @Test
    public void collectionSearchSnapshotCanBeCurrent()
    {
        Map<String, Object> fragment =
            envelope(NOW, "envelope-session");

        Map<String, Object> wholeLog =
            new LinkedHashMap<>();
        wholeLog.put("snapshot_observed", true);
        wholeLog.put(
            "last_observed_at",
            OffsetDateTime.ofInstant(
                NOW,
                ZoneOffset.UTC
            ).toString()
        );
        wholeLog.put(
            "observed_session_id",
            "current-session"
        );
        fragment.put("whole_log", wholeLog);

        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                ExportDataset.COLLECTION_LOG,
                fragment,
                null,
                "current-session",
                NOW
            );

        Assert.assertEquals(
            DatasetFreshness.Status.CURRENT,
            observation.status()
        );
        Assert.assertTrue(observation.currentSession());
    }

    @Test
    public void savedCollectionSnapshotDoesNotExpireAfterTwelveHours()
    {
        Map<String, Object> fragment =
            envelope(NOW.minusSeconds(48L * 60L * 60L), "envelope-session");

        Map<String, Object> wholeLog = new LinkedHashMap<>();
        wholeLog.put("snapshot_observed", true);
        wholeLog.put(
            "last_observed_at",
            OffsetDateTime.ofInstant(
                NOW.minusSeconds(48L * 60L * 60L),
                ZoneOffset.UTC
            ).toString()
        );
        wholeLog.put("observed_session_id", "old-session");
        fragment.put("whole_log", wholeLog);

        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                ExportDataset.COLLECTION_LOG,
                fragment,
                null,
                "current-session",
                NOW
            );

        Assert.assertEquals(
            DatasetFreshness.Status.SAVED,
            observation.status()
        );
    }


    @Test
    public void publicFreshnessMapUsesCanonicalInteractionKey()
    {
        Map<String, Object> fragment =
            envelope(NOW, "current-session");

        Map<String, Object> freshness = DatasetFreshness.evaluate(
            ExportDataset.SEED_VAULT,
            fragment,
            null,
            "current-session",
            NOW
        ).toMap();

        Assert.assertEquals(
            Boolean.TRUE,
            freshness.get("requires_human_interaction")
        );
        Assert.assertFalse(freshness.containsKey("requires_interaction"));
        Assert.assertEquals("Open the Seed Vault", freshness.get("refresh_action"));
    }

    @Test
    public void missingFragmentIsUnknownEvenWhenAPathIsProvided()
    {
        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                ExportDataset.BANK,
                null,
                java.nio.file.Paths.get("missing-bank.json"),
                "current-session",
                NOW
            );

        Assert.assertEquals(
            DatasetFreshness.Status.UNKNOWN,
            observation.status()
        );
    }

    private static Map<String, Object> envelope(
        Instant instant,
        String sessionId)
    {
        Map<String, Object> fragment =
            new LinkedHashMap<>();
        fragment.put(
            "exported_at",
            OffsetDateTime.ofInstant(
                instant,
                ZoneOffset.UTC
            ).toString()
        );
        fragment.put("session_id", sessionId);
        return fragment;
    }
}
