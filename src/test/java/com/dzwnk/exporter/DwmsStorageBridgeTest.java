package com.dzwnk.exporter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.events.PluginMessage;
import org.junit.Assert;
import org.junit.Test;

public class DwmsStorageBridgeTest
{
    @Test
    public void recognizesStorageKeysWithoutHardcodingCurrentManagers()
    {
        Assert.assertTrue(
            DwmsStorageBridge.isStorageStateKey("carryable.runepouch")
        );
        Assert.assertTrue(
            DwmsStorageBridge.isStorageStateKey("futuremanager.futurestore")
        );
        Assert.assertTrue(
            DwmsStorageBridge.isStorageStateKey(
                "death.deathpile.123e4567-e89b-12d3-a456-426614174000"
            )
        );
        Assert.assertFalse(
            DwmsStorageBridge.isStorageStateKey("showEmptyStorages")
        );
        Assert.assertFalse(
            DwmsStorageBridge.isStorageStateKey(
                "storedItemCountInclude.world.bank"
            )
        );
        Assert.assertFalse(
            DwmsStorageBridge.isStorageStateKey("debug.menu.logCoords")
        );
    }

    @Test
    public void decodesTimestampAndGenericItemList()
    {
        long now = System.currentTimeMillis();
        Map<String, Object> decoded =
            DwmsStorageBridge.decodeProperty(
                now + ";556x1000,558x500"
            );

        Assert.assertEquals(now, decoded.get("last_updated_ms"));
        Assert.assertEquals("saved", decoded.get("observation_state"));
        Assert.assertTrue(decoded.containsKey("observed_at"));

        List<?> items = (List<?>) decoded.get("detected_items");
        Assert.assertEquals(2, items.size());
        Assert.assertEquals(
            556,
            ((Number) ((Map<?, ?>) items.get(0)).get("id")).intValue()
        );
        Assert.assertEquals(
            1000L,
            ((Number) ((Map<?, ?>) items.get(0)).get("quantity")).longValue()
        );
        Assert.assertEquals(
            "owned",
            ((Map<?, ?>) items.get(0)).get("item_state")
        );
    }

    @Test
    public void pluginMessageNormalizesExtraStoresAndDropsDedicatedContainers()
    {
        DwmsStorageBridge bridge = new DwmsStorageBridge();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "Dude, Where's My Stuff?");
        data.put("target", DwmsStorageBridge.REQUEST_SOURCE);
        data.put("version", 1);
        data.put(
            "storages",
            Arrays.asList(
                storage("carryable", "Inventory", 1L, item(995, 100L)),
                storage("carryable", "Rune pouch", 2L, item(556, 1000L)),
                storage("world", "Bank", 3L, item(385, 5L)),
                storage("world", "Group Storage", 4L, item(1511, 20L))
            )
        );

        Assert.assertTrue(
            bridge.accept(
                new PluginMessage(
                    DwmsStorageBridge.NAMESPACE,
                    DwmsStorageBridge.RESPONSE_NAME,
                    data
                )
            )
        );

        Map<String, Object> snapshot = bridge.buildSnapshot(
            null,
            "test-version",
            "test-session",
            "test"
        );

        List<?> stores =
            (List<?>) snapshot.get("normalized_item_storages");
        Assert.assertEquals(2, stores.size());
        Assert.assertEquals(
            "Rune pouch",
            ((Map<?, ?>) stores.get(0)).get("name")
        );
        Assert.assertEquals(
            "Group Storage",
            ((Map<?, ?>) stores.get(1)).get("name")
        );

        Map<?, ?> state = (Map<?, ?>) snapshot.get("source_state");
        Assert.assertEquals(
            Boolean.TRUE,
            state.get("live_response_observed_this_session")
        );
        Assert.assertEquals(
            Boolean.TRUE,
            state.get("normalized_items_are_owned")
        );
        Assert.assertEquals(
            "excluded_by_dwms",
            state.get("placeholder_reporting")
        );

        Map<?, ?> normalizedRunePouch = (Map<?, ?>) stores.get(0);
        List<?> normalizedItems = (List<?>) normalizedRunePouch.get("items");
        Assert.assertEquals(
            "owned",
            ((Map<?, ?>) normalizedItems.get(0)).get("item_state")
        );
    }

    private static Map<String, Object> storage(
        String category,
        String name,
        long lastUpdated,
        Map<String, Object> item)
    {
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("category", category);
        storage.put("name", name);
        storage.put("lastUpdated", lastUpdated);
        storage.put("items", Arrays.asList(item));
        return storage;
    }

    private static Map<String, Object> item(int id, long quantity)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("quantity", quantity);
        return item;
    }
}
