package com.dzwnk.exporter;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class PublicDomainSnapshotsTest
{
    @Test
    public void storageStartsExplicitlyWithNoImplementedExtraStores()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-storage-empty");
        Map<String, Object> storage =
            PublicDomainSnapshots.buildStorage(
                accountDir,
                new Gson(),
                "test-version",
                "test-session"
            );

        Assert.assertTrue(storage.containsKey("implemented_sources"));
        Assert.assertTrue(storage.containsKey("stores"));
        Assert.assertTrue(storage.containsKey("source_records"));
        Assert.assertEquals(
            0,
            ((List<?>) storage.get("implemented_sources")).size()
        );
        Assert.assertEquals(
            0,
            ((Map<?, ?>) storage.get("stores")).size()
        );
        Map<?, ?> itemSemantics =
            (Map<?, ?>) storage.get("item_semantics");
        Assert.assertEquals("owned", itemSemantics.get("items"));
        Assert.assertEquals(
            "owned_quantity",
            itemSemantics.get("quantity")
        );
        Map<?, ?> freshness = (Map<?, ?>) storage.get("freshness");
        Assert.assertEquals("unknown", freshness.get("status"));
        Assert.assertEquals("source_snapshot", freshness.get("scope"));
    }

    @Test
    public void storageBuildsNormalizedDwmsStoresAndPreservesSourceRecords()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-storage-dwms");
        Files.createDirectories(ExportLayout.cacheDir(accountDir));

        Map<String, Object> runePouch = new LinkedHashMap<>();
        runePouch.put("source", "dude-wheres-my-stuff");
        runePouch.put("category", "carryable");
        runePouch.put("name", "Rune pouch");
        runePouch.put("observation_state", "saved");
        runePouch.put(
            "items",
            Arrays.asList(item(556, 1000L), item(558, 500L))
        );

        Map<String, Object> property = new LinkedHashMap<>();
        property.put("raw", "1700000000000;556x1000,558x500");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("carryable.runepouch", property);

        Map<String, Object> sourceState = new LinkedHashMap<>();
        sourceState.put("profile_storage_property_count", 1);

        Map<String, Object> fragment = new LinkedHashMap<>();
        fragment.put("source", "dude-wheres-my-stuff");
        fragment.put("source_state", sourceState);
        fragment.put("normalized_item_storages", Arrays.asList(runePouch));
        fragment.put("profile_storage_properties", properties);

        Files.write(
            ExportLayout.datasetPath(accountDir, ExportDataset.STORAGE),
            new Gson().toJson(fragment).getBytes(StandardCharsets.UTF_8)
        );

        Map<String, Object> storage =
            PublicDomainSnapshots.buildStorage(
                accountDir,
                new Gson(),
                "test-version",
                "test-session"
            );

        Assert.assertEquals(
            Arrays.asList("dude-wheres-my-stuff"),
            storage.get("implemented_sources")
        );

        Map<?, ?> stores = (Map<?, ?>) storage.get("stores");
        Assert.assertTrue(stores.containsKey("dwms:carryable:rune_pouch"));

        Map<?, ?> sourceRecords = (Map<?, ?>) storage.get("source_records");
        Map<?, ?> dwms = (Map<?, ?>) sourceRecords.get("dude-wheres-my-stuff");
        Assert.assertEquals(properties, dwms.get("profile_storage_properties"));
    }

    @Test
    public void progressFreshnessAggregatesComponentObservations()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-progress-freshness");
        Files.createDirectories(ExportLayout.cacheDir(accountDir));
        Gson gson = new Gson();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("exported_at", "2026-09-23T12:00:00Z");
        raw.put("session_id", "test-session");
        Files.write(
            ExportLayout.datasetPath(accountDir, ExportDataset.PROGRESS_FLAGS),
            gson.toJson(raw).getBytes(StandardCharsets.UTF_8)
        );

        Map<String, Object> travel = new LinkedHashMap<>();
        travel.put("exported_at", "2026-09-23T12:00:00Z");
        travel.put("session_id", "test-session");
        Files.write(
            ExportLayout.datasetPath(accountDir, ExportDataset.TRAVEL_GATES),
            gson.toJson(travel).getBytes(StandardCharsets.UTF_8)
        );

        Map<String, Object> progress =
            PublicDomainSnapshots.buildProgress(
                accountDir,
                gson,
                "test-version",
                "test-session"
            );

        Map<?, ?> freshness = (Map<?, ?>) progress.get("freshness");
        Assert.assertEquals("current", freshness.get("status"));
        Assert.assertEquals(Boolean.TRUE, freshness.get("current_session"));
        Assert.assertEquals("components", freshness.get("scope"));

        Map<?, ?> components = (Map<?, ?>) freshness.get("components");
        Assert.assertTrue(components.containsKey("raw_variables"));
        Assert.assertTrue(components.containsKey("travel"));
    }

    @Test
    public void progressAndStorageDatasetsAreDerivedNotDirectlyMirrored()
    {
        Assert.assertEquals(
            "progress.json",
            ExportDataset.PROGRESS_FLAGS.publicFileName()
        );
        Assert.assertFalse(
            ExportDataset.PROGRESS_FLAGS.mirrorFragmentPublicly()
        );
        Assert.assertEquals(
            "storage.json",
            ExportDataset.STORAGE.publicFileName()
        );
        Assert.assertFalse(
            ExportDataset.STORAGE.mirrorFragmentPublicly()
        );
        Assert.assertTrue(
            ExportDataset.STORAGE.panelVisible()
        );
        Assert.assertTrue(
            ExportDataset.LIVE.panelVisible()
        );
        Assert.assertEquals(
            "live.json",
            ExportDataset.LIVE.publicFileName()
        );
        Assert.assertTrue(
            ExportDataset.LIVE.mirrorFragmentPublicly()
        );
        Assert.assertFalse(
            ExportDataset.TRAVEL_GATES.panelVisible()
        );
        Assert.assertEquals(13, ExportDataset.panelDatasets().size());

        java.util.Set<String> publicFiles = new java.util.LinkedHashSet<>();
        for (ExportDataset dataset : ExportDataset.panelDatasets())
        {
            Assert.assertNotNull(dataset.publicFileName());
            publicFiles.add(dataset.publicFileName());
        }
        Assert.assertEquals(13, publicFiles.size());
    }

    @Test
    public void retiredUniversalCacheFilesAreRemovedButLiveFragmentSurvives()
        throws Exception
    {
        Path accountDir = Files.createTempDirectory("ce-retired-cache");
        Files.createDirectories(ExportLayout.cacheDir(accountDir));

        String[] retired = {
            "live_state.json",
            "slayer.json",
            "grand_exchange.json",
            "recurring.json"
        };
        for (String fileName : retired)
        {
            Files.write(
                ExportLayout.cachePath(accountDir, fileName),
                "{}".getBytes(StandardCharsets.UTF_8)
            );
        }

        Path live = ExportLayout.cachePath(accountDir, "live.json");
        Files.write(live, "{}".getBytes(StandardCharsets.UTF_8));

        ExportLayout.cleanupRetiredCacheFiles(accountDir);

        for (String fileName : retired)
        {
            Assert.assertFalse(
                Files.exists(ExportLayout.cachePath(accountDir, fileName))
            );
        }
        Assert.assertTrue(Files.isRegularFile(live));
    }

    private static Map<String, Object> item(int id, long quantity)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("quantity", quantity);
        return item;
    }
}
