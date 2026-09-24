package com.dzwnk.exporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Direct RuneLite account-state collectors which do not require another plugin.
 *
 * This file intentionally exports observations and semantic game state, not
 * guide-specific conclusions.
 */
final class UniversalStateExporter
{
    private static final int SCHEMA_VERSION = 1;

    private static final String[] SLAYER_MASTER_NAMES = {
        null,
        "Turael",
        "Mazchna",
        "Vannaka",
        "Chaeldar",
        "Duradel",
        "Nieve",
        "Krystilia",
        "Konar quo Maten"
    };

    private UniversalStateExporter()
    {
    }

    static Map<String, Object> snapshot(Client client, ItemManager itemManager)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("live", buildLive(client));
        root.put("slayer", buildSlayer(client));
        root.put("grand_exchange", buildGrandExchange(client, itemManager));
        root.put("recurring", buildRecurring(client));
        return root;
    }

    private static Map<String, Object> buildLive(Client client)
    {
        Map<String, Object> live = new LinkedHashMap<>();
        Player player = client.getLocalPlayer();

        live.put("world", client.getWorld());
        live.put("game_state", client.getGameState().name());
        live.put("run_energy_percent", client.getEnergy() / 100.0);
        live.put("weight_kg", client.getWeight());
        live.put(
            "special_attack_percent",
            client.getVarpValue(VarPlayerID.SA_ENERGY) / 10.0
        );
        live.put(
            "special_attack_enabled",
            client.getVarpValue(VarPlayerID.SA_ATTACK) == 1
        );
        live.put(
            "hitpoints_current",
            client.getBoostedSkillLevel(Skill.HITPOINTS)
        );
        live.put(
            "hitpoints_real",
            client.getRealSkillLevel(Skill.HITPOINTS)
        );
        live.put(
            "prayer_current",
            client.getBoostedSkillLevel(Skill.PRAYER)
        );
        live.put(
            "prayer_real",
            client.getRealSkillLevel(Skill.PRAYER)
        );

        int ironmanMode = client.getVarbitValue(VarbitID.IRONMAN);
        live.put("ironman_mode", ironmanMode);
        live.put("is_ironman", ironmanMode != 0);
        live.put(
            "member_account",
            client.getVarcIntValue(VarClientID.PLAYERMEMBER) == 1
        );

        live.put(
            "spellbook_id",
            client.getVarbitValue(VarbitID.SPELLBOOK)
        );
        live.put(
            "spellbook_sublist_id",
            client.getVarbitValue(VarbitID.SPELLBOOK_SUBLIST)
        );

        if (player != null)
        {
            live.put("combat_level", player.getCombatLevel());
            WorldPoint point = player.getWorldLocation();
            if (point != null)
            {
                Map<String, Object> location = new LinkedHashMap<>();
                location.put("x", point.getX());
                location.put("y", point.getY());
                location.put("plane", point.getPlane());
                location.put("region_id", point.getRegionID());
                live.put("location", location);
            }
        }

        return live;
    }

    private static Map<String, Object> buildSlayer(Client client)
    {
        Map<String, Object> slayer = new LinkedHashMap<>();

        slayer.put(
            "points",
            client.getVarbitValue(VarbitID.SLAYER_POINTS)
        );
        slayer.put(
            "tasks_completed",
            client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED)
        );
        slayer.put(
            "wilderness_tasks_completed",
            client.getVarbitValue(
                VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED
            )
        );

        int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
        int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
        int original = client.getVarpValue(
            VarPlayerID.SLAYER_COUNT_ORIGINAL
        );
        int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
        int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
        int bossId = client.getVarbitValue(
            VarbitID.SLAYER_TARGET_BOSSID
        );

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("active", taskId > 0 && remaining > 0);
        current.put("task_id", taskId);
        current.put("task_name", lookupSlayerTaskName(client, taskId));
        current.put("remaining", remaining);
        current.put("original_amount", original);
        current.put("area_id", areaId);
        current.put("area_name", lookupSlayerAreaName(client, areaId));
        current.put("master_id", masterId);
        current.put(
            "master_name",
            masterId > 0 && masterId < SLAYER_MASTER_NAMES.length
                ? SLAYER_MASTER_NAMES[masterId]
                : null
        );
        current.put("boss_id", bossId);
        current.put("boss_name", lookupSlayerBossName(client, bossId));
        slayer.put("current_task", current);

        Map<String, Object> unlocks = new LinkedHashMap<>();
        putVarbit(client, unlocks, "red_dragons", VarbitID.SLAYER_UNLOCK_REDDRAGONS);
        putVarbit(client, unlocks, "mithril_dragons", VarbitID.SLAYER_UNLOCK_MITHRILDRAGONS);
        putVarbit(client, unlocks, "aviansies", VarbitID.SLAYER_UNLOCK_AVIANSIES);
        putVarbit(client, unlocks, "tzhaar", VarbitID.SLAYER_UNLOCK_TZHAAR);
        putVarbit(client, unlocks, "lizardmen", VarbitID.SLAYER_UNLOCK_LIZARDMEN);
        putVarbit(client, unlocks, "basilisks", VarbitID.SLAYER_UNLOCK_BASILISK);
        putVarbit(client, unlocks, "vampyres", VarbitID.SLAYER_UNLOCK_VAMPYRES);
        putVarbit(client, unlocks, "warped_creatures", VarbitID.SLAYER_UNLOCK_WARPED_CREATURES);
        putVarbit(client, unlocks, "aquanites", VarbitID.SLAYER_UNLOCK_AQUANITES);
        putVarbit(client, unlocks, "gryphons", VarbitID.SLAYER_UNLOCK_GRYPHONS);
        putVarbit(client, unlocks, "bosses", VarbitID.SLAYER_UNLOCK_BOSSES);
        putVarbit(client, unlocks, "superior_monsters", VarbitID.SLAYER_UNLOCK_SUPERIORMOBS);
        putVarbit(client, unlocks, "slayer_helmet", VarbitID.SLAYER_HELM_UNLOCKED);
        putVarbit(client, unlocks, "task_storage", VarbitID.SLAYER_UNLOCK_STORAGE);
        putVarbit(client, unlocks, "wildy_extra_tasks", VarbitID.SLAYER_UNLOCK_WILDY_EXTRATASKS);
        slayer.put("reward_unlocks", unlocks);

        Map<String, Object> extensions = new LinkedHashMap<>();
        putVarbit(client, extensions, "aberrant_spectres", VarbitID.SLAYER_LONGER_ABERRANTSPECTRES);
        putVarbit(client, extensions, "abyssal_demons", VarbitID.SLAYER_LONGER_ABYSSALDEMONS);
        putVarbit(client, extensions, "adamant_dragons", VarbitID.SLAYER_LONGER_ADAMANTDRAGONS);
        putVarbit(client, extensions, "ankou", VarbitID.SLAYER_LONGER_ANKOU);
        putVarbit(client, extensions, "aquanites", VarbitID.SLAYER_LONGER_AQUANITES);
        putVarbit(client, extensions, "araxytes", VarbitID.SLAYER_LONGER_ARAXYTES);
        putVarbit(client, extensions, "aviansies", VarbitID.SLAYER_LONGER_AVIANSIES);
        putVarbit(client, extensions, "basilisks", VarbitID.SLAYER_LONGER_BASILISK);
        putVarbit(client, extensions, "black_demons", VarbitID.SLAYER_LONGER_BLACKDEMONS);
        putVarbit(client, extensions, "black_dragons", VarbitID.SLAYER_LONGER_BLACKDRAGONS);
        putVarbit(client, extensions, "bloodveld", VarbitID.SLAYER_LONGER_BLOODVELD);
        putVarbit(client, extensions, "cave_horrors", VarbitID.SLAYER_LONGER_CAVEHORRORS);
        putVarbit(client, extensions, "cave_kraken", VarbitID.SLAYER_LONGER_CAVEKRAKEN);
        putVarbit(client, extensions, "custodians", VarbitID.SLAYER_LONGER_CUSTODIANS);
        putVarbit(client, extensions, "dark_beasts", VarbitID.SLAYER_LONGER_DARKBEASTS);
        putVarbit(client, extensions, "dust_devils", VarbitID.SLAYER_LONGER_DUSTDEVILS);
        putVarbit(client, extensions, "fossil_island_wyverns", VarbitID.SLAYER_LONGER_FOSSILWYVERNS);
        putVarbit(client, extensions, "gargoyles", VarbitID.SLAYER_LONGER_GARGOYLES);
        putVarbit(client, extensions, "greater_demons", VarbitID.SLAYER_LONGER_GREATERDEMONS);
        putVarbit(client, extensions, "metal_dragons", VarbitID.SLAYER_LONGER_METALDRAGONS);
        putVarbit(client, extensions, "mithril_dragons", VarbitID.SLAYER_LONGER_MITHRILDRAGONS);
        putVarbit(client, extensions, "nechryael", VarbitID.SLAYER_LONGER_NECHRYAEL);
        putVarbit(client, extensions, "revenants", VarbitID.SLAYER_LONGER_REVENANTS);
        putVarbit(client, extensions, "rune_dragons", VarbitID.SLAYER_LONGER_RUNEDRAGONS);
        putVarbit(client, extensions, "scabarites", VarbitID.SLAYER_LONGER_SCABARITES);
        putVarbit(client, extensions, "skeletal_wyverns", VarbitID.SLAYER_LONGER_SKELETALWYVERNS);
        putVarbit(client, extensions, "spiritual_creatures", VarbitID.SLAYER_LONGER_SPIRITUALGWD);
        putVarbit(client, extensions, "suqahs", VarbitID.SLAYER_LONGER_SUQAH);
        putVarbit(client, extensions, "vampyres", VarbitID.SLAYER_LONGER_VAMPYRES);
        putVarbit(client, extensions, "wyrms", VarbitID.SLAYER_LONGER_WYRMS);
        slayer.put("task_extensions", extensions);

        return slayer;
    }

    private static Map<String, Object> buildGrandExchange(
        Client client,
        ItemManager itemManager)
    {
        Map<String, Object> ge = new LinkedHashMap<>();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();

        ge.put("loaded", offers != null);

        List<Map<String, Object>> rows = new ArrayList<>();
        int active = 0;

        if (offers != null)
        {
            for (int slot = 0; slot < offers.length; slot++)
            {
                GrandExchangeOffer offer = offers[slot];
                if (offer == null ||
                    offer.getState() == null ||
                    "EMPTY".equals(offer.getState().name()))
                {
                    continue;
                }

                int itemId = offer.getItemId();
                int total = offer.getTotalQuantity();
                int completed = offer.getQuantitySold();

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slot", slot);
                row.put("state", offer.getState().name());
                row.put("item_id", itemId);
                row.put(
                    "item_name",
                    itemId > 0
                        ? itemManager.getItemComposition(itemId).getName()
                        : ""
                );
                row.put("listed_price", offer.getPrice());
                row.put(
                    "market_price",
                    itemId > 0 ? itemManager.getItemPrice(itemId) : 0
                );
                row.put("total_quantity", total);
                row.put("completed_quantity", completed);
                row.put(
                    "remaining_quantity",
                    Math.max(0, total - completed)
                );
                row.put("spent", offer.getSpent());
                rows.add(row);
                active++;
            }
        }

        ge.put("active_offer_count", active);
        ge.put("offers", rows);
        return ge;
    }

    /**
     * Reliable recurring-account markers from the same game vars used by
     * RuneLite's Daily Task Indicator. We export eligibility/raw claim markers
     * rather than pretending every marker alone proves "claim available now".
     */
    private static Map<String, Object> buildRecurring(Client client)
    {
        Map<String, Object> recurring = new LinkedHashMap<>();
        recurring.put(
            "utc_reset_day",
            System.currentTimeMillis() / 86400000L
        );

        addRecurring(
            recurring,
            "zaff_battlestaves",
            client.getVarbitValue(VarbitID.VARROCK_DIARY_EASY_COMPLETE) == 1,
            "last_claimed_marker",
            client.getVarbitValue(VarbitID.ZAFF_LAST_CLAIMED)
        );
        addRecurring(
            recurring,
            "cromperty_essence",
            client.getVarbitValue(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE) == 1,
            "claim_marker",
            client.getVarbitValue(VarbitID.ARDOUGNE_FREE_ESSENCE)
        );
        addRecurring(
            recurring,
            "lundail_runes",
            client.getVarbitValue(VarbitID.WILDERNESS_DIARY_EASY_COMPLETE) == 1,
            "last_claimed_marker",
            client.getVarbitValue(VarbitID.LUNDAIL_LAST_CLAIMED)
        );
        addRecurring(
            recurring,
            "bert_sand",
            client.getVarbitValue(VarbitID.HANDSAND_QUEST) >= 160,
            "claim_marker",
            client.getVarbitValue(VarbitID.YANILLE_SAND_CLAIMED)
        );
        addRecurring(
            recurring,
            "seers_flax",
            client.getVarbitValue(VarbitID.KANDARIN_DIARY_EASY_COMPLETE) == 1,
            "claim_marker",
            client.getVarbitValue(VarbitID.SEERS_FREE_FLAX)
        );
        addRecurring(
            recurring,
            "rantz_arrows",
            client.getVarbitValue(VarbitID.WESTERN_DIARY_EASY_COMPLETE) == 1,
            "claim_marker",
            client.getVarbitValue(VarbitID.WESTERN_RANTZ_ARROWS)
        );
        addRecurring(
            recurring,
            "kourend_dynamite",
            client.getVarbitValue(VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE) == 1,
            "claim_marker",
            client.getVarbitValue(VarbitID.KOUREND_FREE_DYNAMITE)
        );

        Map<String, Object> robin = new LinkedHashMap<>();
        robin.put(
            "eligible",
            client.getVarbitValue(
                VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE
            ) == 1
        );
        robin.put(
            "slime_claimed",
            client.getVarbitValue(VarbitID.MORYTANIA_SLIME_CLAIMED)
        );
        recurring.put("robin_bonemeal_slime", robin);

        Map<String, Object> herbBoxes = new LinkedHashMap<>();
        herbBoxes.put(
            "eligible_account_type",
            client.getVarbitValue(VarbitID.IRONMAN) == 0
        );
        herbBoxes.put(
            "nmz_points",
            client.getVarpValue(VarPlayerID.NZONE_REWARDPOINTS)
        );
        herbBoxes.put(
            "purchased_today",
            client.getVarbitValue(
                VarbitID.NZONE_HERBBOXES_PURCHASED
            )
        );
        recurring.put("nmz_herb_boxes", herbBoxes);

        return recurring;
    }

    private static void addRecurring(
        Map<String, Object> recurring,
        String key,
        boolean eligible,
        String markerName,
        int markerValue)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eligible", eligible);
        row.put(markerName, markerValue);
        recurring.put(key, row);
    }

    private static void putVarbit(
        Client client,
        Map<String, Object> out,
        String key,
        int id)
    {
        out.put(key, client.getVarbitValue(id));
    }

    private static String lookupSlayerTaskName(Client client, int taskId)
    {
        if (taskId <= 0)
        {
            return null;
        }

        try
        {
            List<Integer> rows = client.getDBRowsByValue(
                DBTableID.SlayerTask.ID,
                DBTableID.SlayerTask.COL_ID,
                0,
                taskId
            );
            if (rows == null || rows.isEmpty())
            {
                return null;
            }

            Object[] field = client.getDBTableField(
                rows.get(0),
                DBTableID.SlayerTask.COL_NAME_UPPERCASE,
                0
            );
            return field != null &&
                field.length > 0 &&
                field[0] != null
                ? String.valueOf(field[0])
                : null;
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private static String lookupSlayerAreaName(Client client, int areaId)
    {
        if (areaId <= 0)
        {
            return null;
        }

        try
        {
            List<Integer> rows = client.getDBRowsByValue(
                DBTableID.SlayerArea.ID,
                DBTableID.SlayerArea.COL_AREA_ID,
                0,
                areaId
            );
            if (rows == null || rows.isEmpty())
            {
                return null;
            }

            Object[] field = client.getDBTableField(
                rows.get(0),
                DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER,
                0
            );
            return field != null &&
                field.length > 0 &&
                field[0] != null
                ? String.valueOf(field[0])
                : null;
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private static String lookupSlayerBossName(Client client, int bossId)
    {
        if (bossId <= 0)
        {
            return null;
        }

        try
        {
            List<Integer> rows = client.getDBRowsByValue(
                DBTableID.SlayerTaskSublist.ID,
                DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
                0,
                bossId
            );
            if (rows == null || rows.isEmpty())
            {
                return null;
            }

            Object[] taskField = client.getDBTableField(
                rows.get(0),
                DBTableID.SlayerTaskSublist.COL_TASK,
                0
            );
            if (taskField == null ||
                taskField.length == 0 ||
                taskField[0] == null)
            {
                return null;
            }

            int taskRow = ((Number) taskField[0]).intValue();
            Object[] nameField = client.getDBTableField(
                taskRow,
                DBTableID.SlayerTask.COL_NAME_UPPERCASE,
                0
            );
            return nameField != null &&
                nameField.length > 0 &&
                nameField[0] != null
                ? String.valueOf(nameField[0])
                : null;
        }
        catch (Exception ex)
        {
            return null;
        }
    }
}
