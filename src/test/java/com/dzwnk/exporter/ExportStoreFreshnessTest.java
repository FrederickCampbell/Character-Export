package com.dzwnk.exporter;

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class ExportStoreFreshnessTest
{
    @Test
    public void directPublicMirrorGetsFreshnessWithoutMutatingFragment()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-public-freshness");
        Gson gson = new Gson();
        ExportStore store = new ExportStore(
            gson,
            "test-version",
            "current-session"
        );

        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("exported_at", OffsetDateTime.now().toString());
        bank.put("plugin_version", "old-version");
        bank.put("session_id", "current-session");
        bank.put("items", java.util.Collections.emptyList());

        store.writeDataset(accountDir, ExportDataset.BANK, bank);

        Map<String, Object> fragment = readObject(
            ExportLayout.datasetPath(accountDir, ExportDataset.BANK),
            gson
        );
        Map<String, Object> publicBank = readObject(
            ExportLayout.publicPath(accountDir, "bank.json"),
            gson
        );

        Assert.assertFalse(fragment.containsKey("freshness"));
        Assert.assertEquals(bank.get("exported_at"), fragment.get("exported_at"));
        Assert.assertEquals(bank.get("exported_at"), publicBank.get("exported_at"));

        Map<?, ?> freshness = (Map<?, ?>) publicBank.get("freshness");
        Assert.assertNotNull(freshness);
        Assert.assertEquals("current", freshness.get("status"));
        Assert.assertEquals(Boolean.TRUE, freshness.get("current_session"));
        Assert.assertEquals(
            Boolean.TRUE,
            freshness.get("requires_human_interaction")
        );
        Assert.assertEquals("Open the Bank", freshness.get("refresh_action"));
    }

    @Test
    public void restoringOldInteractiveFragmentMarksPublicMirrorStale()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-public-stale");
        Files.createDirectories(ExportLayout.cacheDir(accountDir));
        Gson gson = new Gson();

        String observedAt = "2026-09-20T00:00:00Z";
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("exported_at", observedAt);
        bank.put("plugin_version", "old-version");
        bank.put("session_id", "old-session");
        bank.put("items", java.util.Collections.emptyList());
        Files.write(
            ExportLayout.datasetPath(accountDir, ExportDataset.BANK),
            gson.toJson(bank).getBytes(StandardCharsets.UTF_8)
        );

        ExportStore store = new ExportStore(
            gson,
            "test-version",
            "new-session"
        );
        store.restorePublicViews(accountDir);

        Map<String, Object> publicBank = readObject(
            ExportLayout.publicPath(accountDir, "bank.json"),
            gson
        );
        Map<?, ?> freshness = (Map<?, ?>) publicBank.get("freshness");

        Assert.assertEquals(observedAt, publicBank.get("exported_at"));
        Assert.assertEquals("stale", freshness.get("status"));
        Assert.assertEquals(Boolean.FALSE, freshness.get("current_session"));
        Assert.assertEquals(observedAt, freshness.get("observed_at"));
    }


    @Test
    public void semanticFreshnessTransitionForcesRewriteButClockOnlyChangeDoesNot()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-freshness-dedupe");
        Gson gson = new Gson();
        ExportStore store = new ExportStore(
            gson,
            "test-version",
            "current-session"
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("plugin_version", "test-version");
        payload.put("session_id", "current-session");
        payload.put("generated_at", "2026-09-23T13:00:00Z");
        payload.put("value", 42);

        Map<String, Object> savedFreshness = new LinkedHashMap<>();
        savedFreshness.put("scope", "components");
        savedFreshness.put("status", "saved");
        savedFreshness.put("current_session", false);
        savedFreshness.put("requires_human_interaction", false);
        savedFreshness.put("observed_at", "2026-09-23T12:00:00Z");
        savedFreshness.put("age_hours", 1.0);
        payload.put("freshness", savedFreshness);

        Assert.assertEquals(
            ExportStore.WriteResult.CHANGED,
            store.writePublic(accountDir, "progress.json", payload)
        );

        Map<String, Object> currentPayload = new LinkedHashMap<>(payload);
        Map<String, Object> currentFreshness = new LinkedHashMap<>(savedFreshness);
        currentFreshness.put("status", "current");
        currentFreshness.put("current_session", true);
        currentFreshness.put("observed_at", "2026-09-23T13:05:00Z");
        currentFreshness.put("age_hours", 0.0);
        currentPayload.put("generated_at", "2026-09-23T13:05:00Z");
        currentPayload.put("freshness", currentFreshness);

        Assert.assertEquals(
            ExportStore.WriteResult.CHANGED,
            store.writePublic(accountDir, "progress.json", currentPayload)
        );

        Map<String, Object> clockOnlyPayload = new LinkedHashMap<>(currentPayload);
        Map<String, Object> clockOnlyFreshness = new LinkedHashMap<>(currentFreshness);
        clockOnlyFreshness.put("observed_at", "2026-09-23T13:10:00Z");
        clockOnlyFreshness.put("age_hours", 0.08);
        clockOnlyPayload.put("generated_at", "2026-09-23T13:10:00Z");
        clockOnlyPayload.put("freshness", clockOnlyFreshness);

        Assert.assertEquals(
            ExportStore.WriteResult.UNCHANGED,
            store.writePublic(accountDir, "progress.json", clockOnlyPayload)
        );

        Map<String, Object> publicProgress = readObject(
            ExportLayout.publicPath(accountDir, "progress.json"),
            gson
        );
        Map<?, ?> freshness = (Map<?, ?>) publicProgress.get("freshness");
        Assert.assertEquals("current", freshness.get("status"));
        Assert.assertEquals(Boolean.TRUE, freshness.get("current_session"));
        Assert.assertEquals(
            "2026-09-23T13:05:00Z",
            freshness.get("observed_at")
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(Path file, Gson gson)
        throws Exception
    {
        try (Reader reader = Files.newBufferedReader(
            file,
            StandardCharsets.UTF_8))
        {
            return (Map<String, Object>) gson.fromJson(reader, Object.class);
        }
    }
}
