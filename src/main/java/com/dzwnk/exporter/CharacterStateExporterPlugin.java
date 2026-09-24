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

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.MenuAction;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.ScriptEvent;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
    name = "Character Export",
    description = "Exports structured local RuneLite-observable character and account state as JSON.",
    tags = {"export", "data", "bank", "quests", "stats", "seed", "inventory", "equipment", "diary", "combat", "collection", "log"}
)
public class CharacterStateExporterPlugin extends Plugin
{
    private static final String PLUGIN_VERSION = "0.9.1-refactor-rc6";
    private static final Path RUNELITE_DIR = RuneLite.RUNELITE_DIR.toPath().toAbsolutePath().normalize();
    private static final Path BASE_OUTPUT_DIR = RUNELITE_DIR.resolve("character-exporter").normalize();
    // Initialised in startUp() from the injected Gson to satisfy plugin-hub rules.
    private Gson prettyGson;

    private static final String CHARACTER_FILE = "character.json";
    private static final String QUESTS_FILE = "quests.json";
    private static final String DIARIES_FILE = "diaries.json";
    private static final String PROGRESS_FLAGS_FILE = "progress_flags.json";
    private static final String PROGRESS_MANIFEST_FILE = "progress_manifest.json";
    private static final String TRAVEL_GATES_FILE = "travel_gates.json";
    private static final String STATE_FILE = "state.json";
    private static final String LIVE_FILE = "live.json";
    private static final String COMBAT_ACHIEVEMENTS_FILE = "combat_achievements.json";
    private static final String COLLECTION_LOG_FILE = "collection_log.json";
    private static final String STATUS_FILE = "status.json";
    private static final String RECENT_EVENTS_FILE = "recent_events.json";
    private static final String EXPORTER_LOG_FILE = "exporter.log";

    private static final long CHARACTER_EXPORT_INTERVAL_MS = 1000L;
    private static final long QUEST_EXPORT_INTERVAL_MS = 5000L;
    private static final long CONTAINER_EXPORT_INTERVAL_MS = 1000L;
    private static final long INVENTORY_EXPORT_INTERVAL_MS = 5000L;
    private static final long DIARY_EXPORT_INTERVAL_MS = 5000L;
    private static final long PROGRESS_EXPORT_INTERVAL_MS = 250L;
    private static final long TRAVEL_GATE_EXPORT_INTERVAL_MS = 1000L;
    private static final long UNIVERSAL_STATE_EXPORT_INTERVAL_MS = 2000L;
    private static final long COMBAT_ACHIEVEMENT_EXPORT_INTERVAL_MS = 5000L;
    private static final long COLLECTION_LOG_EXPORT_INTERVAL_MS = 2000L;
    private static final long DWMS_STORAGE_EXPORT_INTERVAL_MS = 5000L;
    private static final long OBSERVABILITY_WRITE_INTERVAL_MS = 1000L;
    private static final int COLLECTION_LOG_DELAYED_TRANSMIT_SCRIPT = 4100;
    private static final int COLLECTION_LOG_SETUP_SCRIPT = 7797;
    private static final int COLLECTION_LOG_INIT_SCRIPT = 2240;
    private static final int COLLECTION_LOG_TRANSMIT_SETTLE_TICKS = 3;

    // Achievement diary definitions: {name, easy_varbit, medium_varbit, hard_varbit, elite_varbit}
    // Order must match DIARY_COUNT_VARBITS and DIARY_WIDGET_TITLES
    private static final Object[][] DIARY_DEFINITIONS = {
        {"Ardougne", 4458, 4459, 4460, 4461},
        {"Desert", 4483, 4484, 4485, 4486},
        {"Falador", 4462, 4463, 4464, 4465},
        {"Fremennik", 4491, 4492, 4493, 4494},
        {"Kandarin", 4475, 4476, 4477, 4478},
        {"Karamja", 3578, 3599, 3611, 4566},
        {"Kourend & Kebos", 7925, 7926, 7927, 7928},
        {"Lumbridge & Draynor", 4495, 4496, 4497, 4498},
        {"Morytania", 4487, 4488, 4489, 4490},
        {"Varrock", 4479, 4480, 4481, 4482},
        {"Western Provinces", 4471, 4472, 4473, 4474},
        {"Wilderness", 4466, 4467, 4468, 4469},
    };
    private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};
    // Task count varbits per diary area/tier — order matches DIARY_DEFINITIONS
    private static final int[][] DIARY_COUNT_VARBITS = {
        {6291, 6292, 6293, 6294}, // Ardougne
        {6307, 6308, 6309, 6310}, // Desert
        {6299, 6300, 6301, 6302}, // Falador
        {6323, 6324, 6325, 6326}, // Fremennik
        {6327, 6328, 6329, 6330}, // Kandarin
        {2423, 6288, 6289, 6290}, // Karamja
        {7933, 7934, 7935, 7936}, // Kourend & Kebos
        {6295, 6296, 6297, 6298}, // Lumbridge & Draynor
        {6315, 6316, 6317, 6318}, // Morytania
        {6303, 6304, 6305, 6306}, // Varrock
        {6319, 6320, 6321, 6322}, // Western Provinces
        {6311, 6312, 6313, 6314}, // Wilderness
    };

    // Normalised title text from the diary journal widget — order matches DIARY_DEFINITIONS
    private static final String[] DIARY_WIDGET_TITLES = {
        "ARDOUGNE_AREA_TASKS", "DESERT_TASKS", "FALADOR_AREA_TASKS",
        "FREMENNIK_TASKS", "KANDARIN_TASKS", "KARAMJA_AREA_TASKS",
        "KOUREND_&_KEBOS_TASKS", "LUMBRIDGE_&_DRAYNOR_TASKS", "MORYTANIA_TASKS",
        "VARROCK_TASKS", "WESTERN_AREA_TASKS", "WILDERNESS_AREA_TASKS",
    };

    // Karamja individual task varbits (legacy per-task varbits, Easy/Medium/Hard only)
    private static final String[] KARAMJA_EASY_TASK_NAMES = {
        "Pick 5 bananas from the plantation east of the volcano",
        "Use the rope swing to reach Moss Giant Island north-west of Karamja",
        "Mine gold from the rocks on the north-west peninsula",
        "Travel to Port Sarim by boat from Musa Point",
        "Travel to Ardougne by charter ship from Musa Point",
        "Explore Cairn Island to the west of Karamja",
        "Fish at the south end of Karamja island",
        "Collect 5 seaweed from Karamja",
        "Enter the TzHaar Fight Cave",
        "Kill a Jogre in Pothole Dungeon",
    };
    private static final int[] KARAMJA_EASY_TASK_VARBITS = {3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575};

    private static final String[] KARAMJA_MEDIUM_TASK_NAMES = {
        "Claim a ticket from the Agility Arena in Brimhaven",
        "Discover the hidden wall in the dungeon below the volcano",
        "Visit the Isle of Crandor via the dungeon below the volcano",
        "Use Vigroy and Hajedy's cart service",
        "Earn 100% favour in the Tai Bwo Wannai Cleanup",
        "Cook a spider on a stick",
        "Mine a red topaz from a gem rock",
        "Cut a log from a teak tree",
        "Cut a log from a mahogany tree",
        "Catch a karambwan",
        "Exchange gems for a machete with Gabooty",
        "Use the gnome glider to travel to Karamja",
        "Grow a healthy fruit tree in the patch near Brimhaven",
        "Trap a horned graahk",
        "Chop the vines to gain access to Brimhaven Dungeon",
        "Cross the lava using stepping stones within Brimhaven Dungeon",
        "Climb the stairs within Brimhaven Dungeon",
        "Charter the Lady of the Waves from Cairn Isle to Port Khazard",
        "Charter a ship from the shipyard in the far east of Karamja",
    };
    private static final int[] KARAMJA_MEDIUM_TASK_VARBITS = {
        3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588,
        3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597,
    };

    private static final String[] KARAMJA_HARD_TASK_NAMES = {
        "Become the champion of the Fight Pits",
        "Kill TzTok-Jad in the TzHaar Fight Cave",
        "Eat an Oomlie wrap",
        "Craft some nature runes from essence",
        "Cook a karambwan thoroughly",
        "Kill a deathwing in the dungeon under the Kharazi Jungle",
        "Use the crossbow shortcut south of the volcano",
        "Collect 5 palm leaves",
        "Be assigned a Slayer task by Duradel in Shilo Village",
        "Kill a metal dragon in Brimhaven Dungeon",
    };
    private static final int[] KARAMJA_HARD_TASK_VARBITS = {3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609};

    // Collection log interface and script IDs
    private static final int COLLECTION_LOG_INTERFACE_ID = 621;
    private static final int COLLECTION_LOG_ACTIVE_TAB_VARBIT = 6905;
    private static final String[] COLLECTION_LOG_TABS = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
    // Script 2729 = COLLECTION_DRAW_LIST (fires when entry is selected); 2730/2731 tried as fallbacks
    private static final int[] COLLECTION_LOG_SCRIPT_IDS = {2729, 2730, 2731};
    // Candidate child IDs for the entry title widget (searched in order)
    private static final int[] COLLECTION_LOG_TITLE_CHILDREN = {19, 18, 20, 21, 22};
    // Candidate child IDs for the items container (searched in order)
    private static final int[] COLLECTION_LOG_ITEMS_CHILDREN = {36, 37, 35, 38, 34};

    // Diary journal interface ID (shared with quest journal)
    private static final int JOURNAL_INTERFACE_ID = 741;
    private static final int JOURNAL_TITLE_CHILD = 2;
    private static final int JOURNAL_TEXTLAYER_CHILD = 3;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;
    private static final long EXPORTER_LOG_MAX_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_RECENT_EVENTS = 200;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private CharacterStateExporterConfig config;

    @Inject
    private Gson gson;

    @Inject
    private ConfigManager configManager;

    @Inject
    private EventBus eventBus;

    private final Map<String, AtomicLong> lastExportAt = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> datasetStatus = new ConcurrentHashMap<>();
    private final Map<String, Object> readinessSnapshot = new ConcurrentHashMap<>();
    private final Set<String> warnedFailureKeys = ConcurrentHashMap.newKeySet();
    private final Deque<Map<String, Object>> recentEvents = new ArrayDeque<>();
    private final AtomicLong lastObservabilityWriteAt = new AtomicLong(0L);
    private final String sessionId = UUID.randomUUID().toString();

    private final CombatAchievementExporter combatAchievementExporter =
        new CombatAchievementExporter();

    private final DwmsStorageBridge dwmsStorageBridge =
        new DwmsStorageBridge();

    // Diary journal widget-scraped task data: area name → tier → list of {name, complete}
    private final Map<String, Map<String, List<Map<String, Object>>>> diaryWidgetCache = new ConcurrentHashMap<>();
    private final Set<String> diaryWidgetAreasObservedThisSession = ConcurrentHashMap.newKeySet();

    // Collection log accumulated page metadata retained for backwards compatibility.
    private final Map<String, Map<String, Object>> collectionLogCache = new ConcurrentHashMap<>();

    // Whole-log owned-item snapshot received from the native Collection Log Search
    // transmission (also used by RuneProfile/WikiSync-style consumers).
    private final Map<Integer, Integer> collectionWholeLogQuantities = new ConcurrentHashMap<>();
    private volatile boolean collectionWholeLogDirty;
    private volatile int collectionWholeLogLastTransmitTick = -1;
    private volatile String collectionWholeLogLastObservedAt;
    private volatile String collectionWholeLogObservedSessionId;
    private volatile boolean collectionFullReadTriggeredThisSession;
    private volatile boolean collectionFullReadPending;

    private final AtomicBoolean unifiedRebuildQueued =
        new AtomicBoolean(false);
    private volatile ExportStore exportStore;
    private volatile ExecutorService writer;
    private volatile boolean pendingInitialCharacterExport;
    private volatile boolean writesEnabled;
    private volatile Path accountOutputDir;
    private volatile String currentAccountName;
    private volatile String currentAccountHash;
    private volatile boolean pendingBankRefresh;
    private volatile boolean pendingSeedVaultRefresh;
    private CharacterStateExporterPanel panel;
    private NavigationButton navButton;

    @Provides
    CharacterStateExporterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CharacterStateExporterConfig.class);
    }

    @Override
    protected void startUp()
    {
        prettyGson = gson.newBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

        writer = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("character-state-exporter-%d")
                .build()
        );
        exportStore = new ExportStore(
            prettyGson,
            PLUGIN_VERSION,
            sessionId
        );
        writesEnabled = initializeBaseOutputDirectory();

        panel = new CharacterStateExporterPanel(
            this::manualExportAll,
            PLUGIN_VERSION,
            prettyGson,
            sessionId
        );
        navButton = NavigationButton.builder()
            .tooltip("Character Export")
            .icon(createSidebarIcon())
            .priority(10)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        debug("startup", "export toggles character={} quests={} diaries={} combatAchievements={} bank={} seedVault={} inventory={} equipment={}",
            config.exportCharacter(), config.exportQuests(), config.exportDiaries(), config.exportCombatAchievements(),
            config.exportBank(), config.exportSeedVault(), config.exportInventory(), config.exportEquipment());

        recordEvent("startup", "plugin_started", ImmutableMap.of(
            "writes_enabled", writesEnabled
        ));

        clientThread.invokeLater(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                pendingInitialCharacterExport = true;
                resolveAccount();
                loadCombatTaskCache();
                restoreCollectionLogCache();
                restoreDiaryWidgetCache();
            }

            updateReadinessSnapshot();
            requestObservabilityWrite(true);

            if (pendingInitialCharacterExport)
            {
                exportQuestSnapshot("startup");
                exportDiarySnapshot("startup");
                exportProgressFlags("startup");
                exportTravelGates("startup");
                exportUniversalState("startup");
                exportCombatAchievementSnapshot("startup");
                requestDwmsStorageSnapshot("startup", 0L);
            }
        });
    }

    @Override
    protected void shutDown()
    {
        if (panel != null)
        {
            panel.shutdown();
        }
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }

        debug("shutdown", "plugin shutting down");
        recordEvent("shutdown", "plugin_stopped", ImmutableMap.of());
        requestObservabilityWrite(true);

        ExecutorService active = writer;
        writer = null;
        if (active == null)
        {
            return;
        }

        active.shutdown();
        try
        {
            if (!active.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                log.warn("Character State Exporter writer did not drain within {}s; forcing shutdown",
                    SHUTDOWN_TIMEOUT_SECONDS);
                active.shutdownNow();
            }
        }
        catch (InterruptedException ex)
        {
            log.warn("Character State Exporter writer shutdown was interrupted", ex);
            active.shutdownNow();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gameState = event.getGameState();
        debug("game_state", "GameStateChanged -> {}", gameState);
        recordEvent("game_state", "game_state_changed", ImmutableMap.of("game_state", gameState.name()));

        if (gameState == GameState.LOGGED_IN)
        {
            pendingInitialCharacterExport = true;
            resolveAccount();
            clientThread.invokeLater(() ->
            {
                loadCombatTaskCache();
                restoreCollectionLogCache();
                restoreDiaryWidgetCache();
                exportQuestSnapshot("game_state_changed");
                exportDiarySnapshot("game_state_changed");
                exportProgressFlags("game_state_changed");
                exportTravelGates("game_state_changed");
                exportUniversalState("game_state_changed");
                exportCombatAchievementSnapshot("game_state_changed");
                requestDwmsStorageSnapshot("game_state_changed", 0L);
            });
        }
        else if (gameState == GameState.LOGIN_SCREEN)
        {
            accountOutputDir = null;
            currentAccountName = null;
            currentAccountHash = null;
            pendingBankRefresh = false;
            pendingSeedVaultRefresh = false;
            combatAchievementExporter.clear();
            diaryWidgetCache.clear();
            diaryWidgetAreasObservedThisSession.clear();
            collectionLogCache.clear();
            collectionWholeLogQuantities.clear();
            collectionWholeLogDirty = false;
            collectionWholeLogLastTransmitTick = -1;
            collectionWholeLogLastObservedAt = null;
            collectionWholeLogObservedSessionId = null;
            collectionFullReadTriggeredThisSession = false;
            collectionFullReadPending = false;
            dwmsStorageBridge.resetSession();
            unifiedRebuildQueued.set(false);
            if (exportStore != null)
            {
                exportStore.clearStableCache();
            }
            lastExportAt.clear();
            datasetStatus.clear();
            synchronized (recentEvents)
            {
                recentEvents.clear();
            }
            panel.clearAccount();
        }

        updateReadinessSnapshot();
        requestObservabilityWrite(true);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        updateReadinessSnapshot();

        if (currentAccountName == null)
        {
            resolveAccount();
        }

        refreshPanelAccountIdentity();
        flushPendingContainerRefreshes();
        flushCollectionLogWholeSnapshot();
        exportUniversalState(
            "game_tick",
            UNIVERSAL_STATE_EXPORT_INTERVAL_MS
        );
        requestDwmsStorageSnapshot(
            "game_tick",
            DWMS_STORAGE_EXPORT_INTERVAL_MS
        );

        if (!pendingInitialCharacterExport || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (!isCharacterStateReady())
        {
            debug("character", "Waiting for character state to become ready on game tick");
            return;
        }

        pendingInitialCharacterExport = false;
        updateReadinessSnapshot();
        exportCharacterSnapshot("initial_game_tick");
        exportProgressFlags("initial_game_tick");
        exportTravelGates("initial_game_tick");
        exportUniversalState("initial_game_tick");
        requestObservabilityWrite(false);
    }

    @Subscribe
    public void onPluginMessage(PluginMessage message)
    {
        if (!dwmsStorageBridge.accept(message))
        {
            return;
        }

        exportDwmsStorageSnapshot("dwms_plugin_message");
    }

    private void requestDwmsStorageSnapshot(
        String reason,
        long minimumIntervalMs)
    {
        if (client.getGameState() != GameState.LOGGED_IN ||
            accountOutputDir == null ||
            exportStore == null)
        {
            return;
        }

        if (!acquireExportSlot(
            "dwms_storage_request",
            minimumIntervalMs
        ))
        {
            return;
        }

        // Persist the entire saved DWMS RS-profile storage namespace first.
        // This remains useful even when DWMS is disabled or an older DWMS build
        // does not implement the PluginMessage protocol. A live response then
        // enriches the same fragment with normalized item-bearing storages.
        exportDwmsStorageSnapshot(reason + "_profile");

        try
        {
            eventBus.post(dwmsStorageBridge.requestMessage());
        }
        catch (RuntimeException ex)
        {
            debug(
                "storage",
                "DWMS storage request failed: {}",
                ex.toString()
            );
        }
    }

    private void exportDwmsStorageSnapshot(String reason)
    {
        Map<String, Object> payload = dwmsStorageBridge.buildSnapshot(
            configManager,
            PLUGIN_VERSION,
            sessionId,
            reason
        );
        enrichDwmsItemNames(payload);
        writeJson(
            ExportDataset.STORAGE.key(),
            ExportDataset.STORAGE.fragmentFileName(),
            payload
        );
    }

    @SuppressWarnings("unchecked")
    private void enrichDwmsItemNames(Map<String, Object> payload)
    {
        Object storagesValue = payload.get("normalized_item_storages");
        if (!(storagesValue instanceof List))
        {
            return;
        }

        for (Object storageValue : (List<?>) storagesValue)
        {
            if (!(storageValue instanceof Map))
            {
                continue;
            }

            Object itemsValue =
                ((Map<String, Object>) storageValue).get("items");
            if (!(itemsValue instanceof List))
            {
                continue;
            }

            for (Object itemValue : (List<?>) itemsValue)
            {
                if (!(itemValue instanceof Map))
                {
                    continue;
                }

                Map<String, Object> item =
                    (Map<String, Object>) itemValue;
                Object idValue = item.get("id");
                if (!(idValue instanceof Number))
                {
                    continue;
                }

                item.put(
                    "name",
                    safeItemName(((Number) idValue).intValue())
                );
            }
        }
    }

    private void refreshPanelAccountIdentity()
    {
        if (client.getGameState() != GameState.LOGGED_IN ||
            accountOutputDir == null)
        {
            return;
        }

        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null ||
            player.getName().trim().isEmpty())
        {
            return;
        }

        try
        {
            ExportLayout.migrateLegacyFiles(accountOutputDir);
            ExportLayout.cleanupRetiredCacheFiles(accountOutputDir);
        }
        catch (IOException ex)
        {
            warnOnce(
                "layout:migrate",
                "Failed migrating Character Export account layout",
                ex
            );
        }

        panel.refreshAccountIdentity(player.getName().trim(), accountOutputDir);
    }

    private void flushPendingContainerRefreshes()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (pendingBankRefresh &&
            client.getItemContainer(InventoryID.BANK) != null)
        {
            pendingBankRefresh = false;
            exportContainerSnapshot(
                ContainerKind.BANK,
                "bank_widget_loaded",
                0L
            );
        }

        if (pendingSeedVaultRefresh &&
            client.getItemContainer(InventoryID.SEED_VAULT) != null)
        {
            pendingSeedVaultRefresh = false;
            exportContainerSnapshot(
                ContainerKind.SEED_VAULT,
                "seed_vault_widget_loaded",
                0L
            );
        }
    }
    @Subscribe
    public void onStatChanged(StatChanged event)

    {
        updateReadinessSnapshot();
        debug("stat_changed", "StatChanged skill={} real={} boosted={} xp={}",
            event.getSkill(), event.getLevel(), event.getBoostedLevel(), event.getXp());
        exportCharacterSnapshot("stat_changed", CHARACTER_EXPORT_INTERVAL_MS);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        clientThread.invokeLater(() ->
            exportProgressFlags("varbit_changed", PROGRESS_EXPORT_INTERVAL_MS));
        clientThread.invokeLater(() ->
            exportTravelGates("varbit_changed", TRAVEL_GATE_EXPORT_INTERVAL_MS));
        boolean anyExportNeeded = false;

        if (config.exportQuests())
        {
            AtomicLong lastRun = lastExportAt.get(QUESTS_FILE);
            if (lastRun == null || System.currentTimeMillis() - lastRun.get() >= QUEST_EXPORT_INTERVAL_MS)
            {
                anyExportNeeded = true;
            }
        }

        if (config.exportDiaries())
        {
            AtomicLong lastRun = lastExportAt.get(DIARIES_FILE);
            if (lastRun == null || System.currentTimeMillis() - lastRun.get() >= DIARY_EXPORT_INTERVAL_MS)
            {
                anyExportNeeded = true;
            }
        }

        if (config.exportCombatAchievements())
        {
            AtomicLong lastRun = lastExportAt.get(COMBAT_ACHIEVEMENTS_FILE);
            if (lastRun == null || System.currentTimeMillis() - lastRun.get() >= COMBAT_ACHIEVEMENT_EXPORT_INTERVAL_MS)
            {
                anyExportNeeded = true;
            }
        }

        if (!anyExportNeeded)
        {
            return;
        }

        clientThread.invokeLater(() ->
        {
            exportQuestSnapshot("varbit_changed", QUEST_EXPORT_INTERVAL_MS);
            exportDiarySnapshot("varbit_changed", DIARY_EXPORT_INTERVAL_MS);
            exportCombatAchievementSnapshot("varbit_changed", COMBAT_ACHIEVEMENT_EXPORT_INTERVAL_MS);
        });
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        int groupId = event.getGroupId();

        if (groupId == InterfaceID.BANKMAIN)
        {
            pendingBankRefresh = true;
        }
        else if (groupId == InterfaceID.SEED_VAULT)
        {
            pendingSeedVaultRefresh = true;
        }

        if (groupId == JOURNAL_INTERFACE_ID)
        {
            clientThread.invokeLater(this::scrapeDiaryJournal);
        }
        else if (groupId == COLLECTION_LOG_INTERFACE_ID)
        {
            // Fires when the collection log interface first opens; scrape whatever entry is visible
            clientThread.invokeLater(this::scrapeCollectionLogPage);
        }
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event)
    {
        if (!config.exportCollectionLog() ||
            client.getGameState() != GameState.LOGGED_IN ||
            event.getScriptId() != COLLECTION_LOG_DELAYED_TRANSMIT_SCRIPT ||
            isAnotherPlayersCollectionLog())
        {
            return;
        }

        ScriptEvent scriptEvent = event.getScriptEvent();
        Object[] arguments =
            scriptEvent == null ? null : scriptEvent.getArguments();

        if (arguments == null || arguments.length < 3 ||
            !(arguments[1] instanceof Integer) ||
            !(arguments[2] instanceof Integer))
        {
            return;
        }

        int itemId = (Integer) arguments[1];
        int quantity = (Integer) arguments[2];
        if (itemId <= 0 || quantity <= 0)
        {
            return;
        }

        int canonicalId = itemManager.canonicalize(itemId);
        collectionWholeLogQuantities.merge(
            canonicalId,
            quantity,
            Math::max
        );
        collectionWholeLogDirty = true;
        collectionWholeLogLastTransmitTick = client.getTickCount();
    }

    private boolean isAnotherPlayersCollectionLog()
    {
        try
        {
            return client.getVarbitValue(
                VarbitID.COLLECTION_POH_HOST_BOOK_OPEN
            ) == 1;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private void flushCollectionLogWholeSnapshot()
    {
        int tick = client.getTickCount();

        if (collectionWholeLogDirty &&
            collectionWholeLogLastTransmitTick >= 0 &&
            tick > collectionWholeLogLastTransmitTick +
                COLLECTION_LOG_TRANSMIT_SETTLE_TICKS)
        {
            collectionWholeLogDirty = false;
            collectionWholeLogLastTransmitTick = -1;

            boolean completedFullRead = collectionFullReadPending;
            if (completedFullRead)
            {
                collectionFullReadPending = false;
                collectionWholeLogLastObservedAt =
                    OffsetDateTime.now().toString();
                collectionWholeLogObservedSessionId = sessionId;
            }

            recordEvent(
                "collection_log",
                completedFullRead
                    ? "collection_log_whole_snapshot_observed"
                    : "collection_log_incremental_update",
                ImmutableMap.of(
                    "owned_item_types",
                    collectionWholeLogQuantities.size()
                )
            );

            writeCollectionLogSnapshot(
                completedFullRead
                    ? "collection_log_auto_full_snapshot"
                    : "collection_log_incremental_update"
            );
            return;
        }

    }


    private void maybeTriggerCollectionLogFullRead()
    {
        if (!config.exportCollectionLog() ||
            client.getGameState() != GameState.LOGGED_IN ||
            collectionFullReadTriggeredThisSession ||
            isAnotherPlayersCollectionLog())
        {
            return;
        }

        collectionFullReadTriggeredThisSession = true;
        collectionFullReadPending = true;

        clientThread.invokeLater(this::triggerCollectionLogFullRead);
    }

    private boolean triggerCollectionLogFullRead()
    {
        if (client.getGameState() != GameState.LOGGED_IN ||
            isAnotherPlayersCollectionLog() ||
            client.getWidget(InterfaceID.Collection.FRAME) == null)
        {
            collectionFullReadTriggeredThisSession = false;
            collectionFullReadPending = false;
            return true;
        }

        Widget searchButton =
            client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);
        if (searchButton == null)
        {
            collectionFullReadTriggeredThisSession = false;
            collectionFullReadPending = false;
            return true;
        }

        recordEvent(
            "collection_log",
            "collection_log_auto_search_triggered",
            ImmutableMap.of()
        );

        // Same approach used by WikiSync/RuneProfile: request the native Search
        // operation, then re-run the collection-log init script to return the
        // interface to its normal view. Search causes script 4100 to transmit
        // every obtained item and quantity.
        client.menuAction(
            -1,
            InterfaceID.Collection.SEARCH_TOGGLE,
            MenuAction.CC_OP,
            1,
            -1,
            "Search",
            null
        );
        client.runScript(COLLECTION_LOG_INIT_SCRIPT);
        return true;
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        int scriptId = event.getScriptId();

        if (scriptId == COLLECTION_LOG_SETUP_SCRIPT)
        {
            maybeTriggerCollectionLogFullRead();
        }

        for (int id : COLLECTION_LOG_SCRIPT_IDS)
        {
            if (scriptId == id)
            {
                clientThread.invokeLater(this::scrapeCollectionLogPage);
                return;
            }
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event)
    {
        String command = event.getCommand();
        if (command == null || !command.equalsIgnoreCase("charexport"))
        {
            return;
        }

        String[] arguments = event.getArguments();
        String target = arguments.length > 0 ? arguments[0].toLowerCase() : "all";
        debug("manual_command", "Received ::charexport target={}", target);
        recordEvent("manual_command", "manual_command", ImmutableMap.of("target", target));

        ContainerKind kind = ContainerKind.fromCommand(target);
        if (kind != null)
        {
            exportContainerSnapshot(kind, "manual_command");
            return;
        }

        switch (target)
        {
            case "character":
                exportCharacterSnapshot("manual_command");
                return;
            case "quests":
                clientThread.invokeLater(() -> exportQuestSnapshot("manual_command"));
                return;
            case "diaries":
                clientThread.invokeLater(() -> exportDiarySnapshot("manual_command"));
                return;
            case "progress":
            case "progress_flags":
            case "variables":
                clientThread.invokeLater(() -> exportProgressFlags("manual_command"));
                return;
            case "travel":
            case "travel_gates":
            case "unlocks":
                clientThread.invokeLater(() -> exportTravelGates("manual_command"));
                return;
            case "state":
            case "live":
            case "slayer":
            case "ge":
            case "recurring":
                clientThread.invokeLater(() -> exportUniversalState("manual_command"));
                return;
            case "combat":
            case "combat_achievements":
                clientThread.invokeLater(() -> exportCombatAchievementSnapshot("manual_command"));
                return;
            case "log":
            case "collection_log":
            case "collectionlog":
                clientThread.invokeLater(() -> writeCollectionLogSnapshot("manual_command"));
                return;
            case "all":
                exportCharacterSnapshot("manual_command");
                for (ContainerKind containerKind : ContainerKind.values())
                {
                    exportContainerSnapshot(containerKind, "manual_command");
                }
                clientThread.invokeLater(() ->
                {
                    exportQuestSnapshot("manual_command");
                    exportDiarySnapshot("manual_command");
                    exportProgressFlags("manual_command");
                    exportTravelGates("manual_command");
                    exportUniversalState("manual_command");
                    exportCombatAchievementSnapshot("manual_command");
                    writeCollectionLogSnapshot("manual_command");
                });
                return;
            default:
                debug("manual_command", "Unknown ::charexport target={}", target);
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        ItemContainer changedContainer = event.getItemContainer();
        if (changedContainer == null)
        {
            debug("container_changed", "ItemContainerChanged with null container");
            updateDatasetStatus("container", "skipped", "null_container", null);
            requestObservabilityWrite(false);
            return;
        }

        int changedContainerId = event.getContainerId();

        for (ContainerKind kind : ContainerKind.values())
        {
            if (!kind.isEnabled(config))
            {
                continue;
            }

            if (changedContainerId == kind.getInventoryId())
            {
                debug(
                    "container_changed",
                    "Matched {} container id={}",
                    kind.getDatasetKey(),
                    changedContainerId
                );
                exportContainerSnapshot(
                    kind,
                    "item_container_changed",
                    kind.getMinimumIntervalMs()
                );
                return;
            }
        }
    }

    private void resolveAccount()
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null || player.getName().trim().isEmpty())
        {
            return;
        }

        String name = sanitizeFileName(player.getName().trim());
        String accountHash = Long.toUnsignedString(client.getAccountHash());
        if (name.equals(currentAccountName) && accountHash.equals(currentAccountHash))
        {
            return;
        }

        if (currentAccountName != null &&
            (!name.equals(currentAccountName) || !accountHash.equals(currentAccountHash)))
        {
            combatAchievementExporter.clear();
            diaryWidgetCache.clear();
            diaryWidgetAreasObservedThisSession.clear();
            collectionLogCache.clear();
            collectionWholeLogQuantities.clear();
            collectionWholeLogDirty = false;
            collectionWholeLogLastTransmitTick = -1;
            collectionWholeLogLastObservedAt = null;
            collectionWholeLogObservedSessionId = null;
            collectionFullReadTriggeredThisSession = false;
            collectionFullReadPending = false;
            dwmsStorageBridge.resetSession();
            unifiedRebuildQueued.set(false);
            if (exportStore != null)
            {
                exportStore.clearStableCache();
            }
            lastExportAt.clear();
            datasetStatus.clear();
            synchronized (recentEvents)
            {
                recentEvents.clear();
            }
        }

        currentAccountName = name;
        currentAccountHash = accountHash;
        Path dir = BASE_OUTPUT_DIR.resolve(name).normalize();

        if (!dir.startsWith(BASE_OUTPUT_DIR))
        {
            log.warn("Character State Exporter: account directory escapes base dir: {}", dir);
            return;
        }

        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException ex)
        {
            log.warn("Character State Exporter: could not create account directory {}", dir, ex);
            return;
        }

        accountOutputDir = dir;

        try
        {
            ExportLayout.migrateLegacyFiles(dir);
            ExportLayout.cleanupRetiredCacheFiles(dir);
        }
        catch (IOException ex)
        {
            warnOnce(
                "layout:migrate",
                "Failed migrating Character Export account layout",
                ex
            );
        }

        if (exportStore != null)
        {
            submitWriter(() ->
            {
                try
                {
                    exportStore.restorePublicViews(dir);
                }
                catch (IOException | RuntimeException ex)
                {
                    warnOnce(
                        "public:restore",
                        "Failed restoring public Character Export views",
                        ex
                    );
                }
            });
        }

        panel.setAccount(player.getName().trim(), dir);
        panel.restoreFromDisk(dir);

        if (writesEnabled && config.debugLogging())
        {
            submitWriter(this::resetExporterLogSync);
        }

        debug("account", "Resolved account directory for {}", name);
    }

    private static String sanitizeFileName(String name)
    {
        return name.replaceAll("[^a-zA-Z0-9_\\- ]", "_");
    }

    private void exportCharacterSnapshot(String reason)
    {
        exportCharacterSnapshot(reason, 0L);
    }

    private void exportCharacterSnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportCharacter() || gameState != GameState.LOGGED_IN)
        {
            debug("character", "Skipped character export reason={} enabled={} gameState={}",
                reason, config.exportCharacter(), gameState);
            updateDatasetStatus("character", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason,
                "enabled", config.exportCharacter(),
                "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("character", "Not ready", "Stats are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!isCharacterStateReady())
        {
            debug("character", "Skipped character export reason={} because character state is not ready", reason);
            pendingInitialCharacterExport = true;
            updateReadinessSnapshot();
            updateDatasetStatus("character", "waiting", "character_state_not_ready", ImmutableMap.of(
                "reason", reason
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("character", "Not ready", "Stats are not ready yet. Try again in a moment.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(CHARACTER_FILE, minimumIntervalMs))
        {
            debug("character", "Throttled character export reason={} minimumIntervalMs={}",
                reason, minimumIntervalMs);
            updateDatasetStatus("character", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason,
                "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("character");
            }
            requestObservabilityWrite(false);
            return;
        }

        Player player = client.getLocalPlayer();
        Map<String, Object> payload = basePayload(reason);
        payload.put("account_name", player != null ? player.getName() : null);
        payload.put("world", client.getWorld());
        payload.put("game_state", gameState.name());
        payload.put("world_types", worldTypes());
        payload.put("stats", buildStats());

        debug("character", "Prepared character export reason={} world={}", reason, client.getWorld());
        updateDatasetStatus("character", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "world", client.getWorld()
        ));
        writeJson("character", CHARACTER_FILE, payload);
    }

    private void exportQuestSnapshot(String reason)
    {
        exportQuestSnapshot(reason, 0L);
    }

    private void exportQuestSnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportQuests() || gameState != GameState.LOGGED_IN)
        {
            debug("quests", "Skipped quest export reason={} enabled={} gameState={}",
                reason, config.exportQuests(), gameState);
            updateDatasetStatus("quests", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason,
                "enabled", config.exportQuests(),
                "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("quests", "Not ready", "Quests are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(QUESTS_FILE, minimumIntervalMs))
        {
            debug("quests", "Throttled quest export reason={} minimumIntervalMs={}",
                reason, minimumIntervalMs);
            updateDatasetStatus("quests", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason,
                "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("quests");
            }
            requestObservabilityWrite(false);
            return;
        }

        List<Map<String, Object>> quests = new ArrayList<>();
        int finished = 0;
        int inProgress = 0;
        int notStarted = 0;

        for (Quest quest : Quest.values())
        {
            QuestState state = quest.getState(client);
            if (state == QuestState.FINISHED)
            {
                finished++;
            }
            else if (state == QuestState.IN_PROGRESS)
            {
                inProgress++;
            }
            else
            {
                notStarted++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", quest.getId());
            row.put("name", quest.getName());
            row.put("state", state.name());
            quests.add(row);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("summary", ImmutableMap.of(
            "finished", finished,
            "in_progress", inProgress,
            "not_started", notStarted
        ));
        payload.put("quests", quests);

        debug("quests", "Prepared quest export reason={} finished={} inProgress={} notStarted={}",
            reason, finished, inProgress, notStarted);
        updateDatasetStatus("quests", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "finished", finished,
            "in_progress", inProgress,
            "not_started", notStarted
        ));
        writeJson("quests", QUESTS_FILE, payload);
    }

    private void exportDiarySnapshot(String reason)
    {
        exportDiarySnapshot(reason, 0L);
    }

    private void exportDiarySnapshot(String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportDiaries() || gameState != GameState.LOGGED_IN)
        {
            debug("diaries", "Skipped diary export reason={} enabled={} gameState={}",
                reason, config.exportDiaries(), gameState);
            updateDatasetStatus("diaries", "skipped", "not_logged_in_or_disabled", ImmutableMap.of(
                "reason", reason, "enabled", config.exportDiaries(), "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable("diaries", "Not ready", "Diaries are only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(DIARIES_FILE, minimumIntervalMs))
        {
            debug("diaries", "Throttled diary export reason={} minimumIntervalMs={}", reason, minimumIntervalMs);
            updateDatasetStatus("diaries", "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason, "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked("diaries");
            }
            requestObservabilityWrite(false);
            return;
        }

        Map<String, Object> diaries = new LinkedHashMap<>();
        int tiersComplete = 0;
        int tiersPossible = 0;

        for (int areaIdx = 0; areaIdx < DIARY_DEFINITIONS.length; areaIdx++)
        {
            Object[] area = DIARY_DEFINITIONS[areaIdx];
            String areaName = (String) area[0];
            boolean isKaramja = "Karamja".equals(areaName);
            Map<String, Object> tierMap = new LinkedHashMap<>();

            // Widget-scraped named task data for this area (if available)
            Map<String, List<Map<String, Object>>> widgetAreaData = diaryWidgetCache.get(areaName);

            for (int t = 0; t < DIARY_TIERS.length; t++)
            {
                String tierName = DIARY_TIERS[t];
                int completionVarbit = (int) area[t + 1];
                boolean tierComplete = client.getVarbitValue(completionVarbit) == 1;
                int tasksDone = client.getVarbitValue(DIARY_COUNT_VARBITS[areaIdx][t]);

                Map<String, Object> tierData = new LinkedHashMap<>();
                tierData.put("complete", tierComplete);
                tierData.put("tasks_done", tasksDone);

                // Add named tasks if available
                if (isKaramja && t < 3)
                {
                    // Karamja Easy/Medium/Hard have individual per-task varbits
                    tierData.put("tasks", buildKaramjaTaskList(t));
                    tierData.put("tasks_source", "game_varbit");
                    tierData.put("tasks_current_session", true);
                }
                else if (widgetAreaData != null && widgetAreaData.containsKey(tierName))
                {
                    boolean currentSession = diaryWidgetAreasObservedThisSession.contains(areaName);
                    tierData.put("tasks", widgetAreaData.get(tierName));
                    tierData.put("tasks_source", currentSession ? "widget_current_session" : "persisted_widget_cache");
                    tierData.put("tasks_current_session", currentSession);
                }

                tierMap.put(tierName, tierData);
                tiersPossible++;
                if (tierComplete)
                {
                    tiersComplete++;
                }
            }
            diaries.put(areaName, tierMap);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("summary", ImmutableMap.of(
            "tiers_complete", tiersComplete,
            "tiers_possible", tiersPossible,
            "note", "tasks_done/tier completion are live game vars. Named widget task lists carry source/freshness metadata; progress_flags.json exposes raw diary vars without opening the journal."
        ));
        payload.put("diaries", diaries);

        debug("diaries", "Prepared diary export reason={} tiersComplete={}/{}", reason, tiersComplete, tiersPossible);
        updateDatasetStatus("diaries", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason, "tiers_complete", tiersComplete, "tiers_possible", tiersPossible
        ));
        writeJson("diaries", DIARIES_FILE, payload);
    }

    private List<Map<String, Object>> buildKaramjaTaskList(int tier)
    {
        String[] names;
        int[] varbits;
        if (tier == 0)
        {
            names = KARAMJA_EASY_TASK_NAMES;
            varbits = KARAMJA_EASY_TASK_VARBITS;
        }
        else if (tier == 1)
        {
            names = KARAMJA_MEDIUM_TASK_NAMES;
            varbits = KARAMJA_MEDIUM_TASK_VARBITS;
        }
        else
        {
            names = KARAMJA_HARD_TASK_NAMES;
            varbits = KARAMJA_HARD_TASK_VARBITS;
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (int i = 0; i < names.length; i++)
        {
            boolean complete = client.getVarbitValue(varbits[i]) > 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", names[i]);
            row.put("complete", complete);
            tasks.add(row);
        }
        return tasks;
    }

    private void exportProgressFlags(String reason)
    {
        exportProgressFlags(reason, 0L);
    }

    private void exportProgressFlags(String reason, long minimumIntervalMs)
    {
        if (!config.exportRawVariables() ||
            client.getGameState() != GameState.LOGGED_IN)
        {
            if (isManualPanelReason(reason))
            {
                panel.markUnavailable(
                    "progress_flags",
                    "Disabled",
                    "Game variable export is disabled in plugin settings."
                );
            }
            return;
        }

        if (!acquireExportSlot(PROGRESS_FLAGS_FILE, minimumIntervalMs))
        {
            return;
        }

        Map<String, Object> payload = basePayload(reason);
        payload.putAll(ProgressFlagExporter.snapshot(
            client,
            BASE_OUTPUT_DIR.resolve(PROGRESS_MANIFEST_FILE)
        ));

        updateDatasetStatus("progress_flags", "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "varbit_count", payload.get("varbit_count"),
            "varplayer_count", payload.get("varplayer_count")
        ));
        writeJson("progress_flags", PROGRESS_FLAGS_FILE, payload);
    }

    private void exportTravelGates(String reason)
    {
        exportTravelGates(reason, 0L);
    }

    private void exportTravelGates(String reason, long minimumIntervalMs)
    {
        if (!config.exportTravelState() ||
            client.getGameState() != GameState.LOGGED_IN)
        {
            if (isManualPanelReason(reason))
            {
                panel.markUnavailable(
                    "travel_gates",
                    "Disabled",
                    "Travel-state export is disabled in plugin settings."
                );
            }
            return;
        }

        if (!acquireExportSlot(TRAVEL_GATES_FILE, minimumIntervalMs))
        {
            if (isManualPanelReason(reason))
            {
                panel.markChecked("travel_gates");
            }
            return;
        }

        Map<String, Object> payload = basePayload(reason);
        payload.putAll(TravelGateExporter.snapshot(client));

        updateDatasetStatus(
            "travel_gates",
            "prepared",
            "ready_to_write",
            ImmutableMap.of(
                "reason", reason,
                "gate_count", payload.get("gate_count"),
                "satisfied_count", payload.get("satisfied_count")
            )
        );
        writeJson("travel_gates", TRAVEL_GATES_FILE, payload);
    }

    private void exportUniversalState(String reason)
    {
        exportUniversalState(reason, 0L);
    }

    private void exportUniversalState(String reason, long minimumIntervalMs)
    {
        if (!config.exportUniversalState() ||
            client.getGameState() != GameState.LOGGED_IN)
        {
            if (isManualPanelReason(reason))
            {
                String tooltip =
                    "Live & activities export is disabled in plugin settings.";
                panel.markUnavailable(
                    ExportDataset.STATE.key(),
                    "Disabled",
                    tooltip
                );
                panel.markUnavailable(
                    ExportDataset.LIVE.key(),
                    "Disabled",
                    tooltip
                );
            }
            return;
        }

        if (!acquireExportSlot(STATE_FILE, minimumIntervalMs))
        {
            if (isManualPanelReason(reason))
            {
                panel.markChecked("state");
            }
            return;
        }

        Map<String, Object> direct =
            UniversalStateExporter.snapshot(client, itemManager);

        Map<String, Object> semantic = basePayload(reason);
        semantic.put("slayer", direct.get("slayer"));
        semantic.put("grand_exchange", direct.get("grand_exchange"));
        semantic.put("recurring", direct.get("recurring"));

        updateDatasetStatus(
            "state",
            "prepared",
            "ready_to_write",
            ImmutableMap.of("reason", reason)
        );
        writeJson("state", STATE_FILE, semantic);

        Map<String, Object> livePayload = basePayload(reason);
        livePayload.put("live", direct.get("live"));
        writeJson(ExportDataset.LIVE.key(), LIVE_FILE, livePayload);
    }
    private void exportCombatAchievementSnapshot(String reason)
    {
        exportCombatAchievementSnapshot(reason, 0L);
    }

    private void exportCombatAchievementSnapshot(
        String reason,
        long minimumIntervalMs)
    {
        boolean manualPanelRequest =
            isManualPanelReason(reason);
        GameState gameState = client.getGameState();

        if (!config.exportCombatAchievements() ||
            gameState != GameState.LOGGED_IN)
        {
            debug(
                "combat_achievements",
                "Skipped combat achievement export reason={} enabled={} gameState={}",
                reason,
                config.exportCombatAchievements(),
                gameState
            );

            updateDatasetStatus(
                "combat_achievements",
                "skipped",
                "not_logged_in_or_disabled",
                ImmutableMap.of(
                    "reason",
                    reason,
                    "enabled",
                    config.exportCombatAchievements(),
                    "game_state",
                    gameState.name()
                )
            );

            if (manualPanelRequest)
            {
                panel.markUnavailable(
                    "combat_achievements",
                    "Not ready",
                    "Combat achievements are only available while logged in."
                );
            }

            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(
            COMBAT_ACHIEVEMENTS_FILE,
            minimumIntervalMs))
        {
            updateDatasetStatus(
                "combat_achievements",
                "throttled",
                "minimum_interval",
                ImmutableMap.of(
                    "reason",
                    reason,
                    "minimum_interval_ms",
                    minimumIntervalMs
                )
            );

            if (manualPanelRequest)
            {
                panel.markChecked("combat_achievements");
            }

            requestObservabilityWrite(false);
            return;
        }

        Map<String, Object> snapshot =
            combatAchievementExporter.snapshot(client);

        Map<String, Object> payload = basePayload(reason);
        payload.putAll(snapshot);

        Object summaryObject = snapshot.get("summary");
        Map<?, ?> summary =
            summaryObject instanceof Map
                ? (Map<?, ?>) summaryObject
                : java.util.Collections.emptyMap();

        Object completed =
            summary.get("total_tasks_completed");
        Object tiersCompleted =
            summary.get("total_tiers_completed");
        Object catalogueTasks =
            summary.get("catalogue_tasks");

        debug(
            "combat_achievements",
            "Prepared combat achievement export reason={} catalogue={} tasks={} tiersComplete={}",
            reason,
            catalogueTasks,
            completed,
            tiersCompleted
        );

        updateDatasetStatus(
            "combat_achievements",
            "prepared",
            "ready_to_write",
            ImmutableMap.of(
                "reason",
                reason,
                "catalogue_tasks",
                catalogueTasks == null ? 0 : catalogueTasks,
                "total_tasks_completed",
                completed == null ? 0 : completed,
                "total_tiers_completed",
                tiersCompleted == null ? 0 : tiersCompleted
            )
        );

        writeJson(
            "combat_achievements",
            COMBAT_ACHIEVEMENTS_FILE,
            payload
        );
    }

    private void loadCombatTaskCache()
    {
        int loaded = combatAchievementExporter.reload(client);
        debug(
            "combat",
            "Loaded {} Combat Achievement tasks from live game-cache tier enums",
            loaded
        );
    }

    private void scrapeDiaryJournal()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Widget titleWidget = client.getWidget(JOURNAL_INTERFACE_ID, JOURNAL_TITLE_CHILD);
        if (titleWidget == null)
        {
            return;
        }

        String titleNorm = stripWidgetTags(titleWidget.getText()).trim().toUpperCase().replace(' ', '_');
        if (!titleNorm.startsWith("ACHIEVEMENT_DIARY"))
        {
            return;
        }

        Widget textLayer = client.getWidget(JOURNAL_INTERFACE_ID, JOURNAL_TEXTLAYER_CHILD);
        if (textLayer == null)
        {
            return;
        }

        Widget[] children = textLayer.getStaticChildren();
        if (children == null || children.length == 0)
        {
            children = textLayer.getDynamicChildren();
        }
        if (children == null || children.length == 0)
        {
            return;
        }

        // Identify which area this page is for from the first text child
        String areaName = null;
        for (Widget child : children)
        {
            String text = child.getText();
            if (text == null || text.isEmpty())
            {
                continue;
            }
            String norm = stripWidgetTags(text).trim().toUpperCase().replace(' ', '_');
            for (int i = 0; i < DIARY_WIDGET_TITLES.length; i++)
            {
                if (DIARY_WIDGET_TITLES[i].equals(norm))
                {
                    areaName = (String) DIARY_DEFINITIONS[i][0];
                    break;
                }
            }
            if (areaName != null)
            {
                break;
            }
        }

        if (areaName == null)
        {
            return;
        }

        // Parse task lines grouped by tier header
        Map<String, List<Map<String, Object>>> tierTasks = new LinkedHashMap<>();
        for (String tier : DIARY_TIERS)
        {
            tierTasks.put(tier, new ArrayList<>());
        }

        String currentTier = null;
        boolean foundAreaTitle = false;

        for (Widget child : children)
        {
            String raw = child.getText();
            if (raw == null || raw.isEmpty())
            {
                continue;
            }

            for (DiaryWidgetParser.Line line : DiaryWidgetParser.splitLines(raw))
            {
                String clean = line.text();
                if (clean.isEmpty())
                {
                    continue;
                }

                // Skip the area title line
                String norm = clean.toUpperCase().replace(' ', '_');
                boolean isAreaTitle = false;
                for (String title : DIARY_WIDGET_TITLES)
                {
                    if (title.equals(norm))
                    {
                        isAreaTitle = true;
                        foundAreaTitle = true;
                        break;
                    }
                }
                if (isAreaTitle)
                {
                    continue;
                }

                if (!foundAreaTitle)
                {
                    continue;
                }

                // Check for tier header
                String upperClean = clean.toUpperCase();
                if (upperClean.startsWith("EASY"))
                {
                    currentTier = "easy";
                    continue;
                }
                else if (upperClean.startsWith("MEDIUM"))
                {
                    currentTier = "medium";
                    continue;
                }
                else if (upperClean.startsWith("HARD"))
                {
                    currentTier = "hard";
                    continue;
                }
                else if (upperClean.startsWith("ELITE"))
                {
                    currentTier = "elite";
                    continue;
                }

                if (currentTier != null)
                {
                    DiaryWidgetParser.appendTaskLine(tierTasks.get(currentTier), clean, line.complete());
                }
            }
        }

        // Only store tiers that have at least one task
        Map<String, List<Map<String, Object>>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tierTasks.entrySet())
        {
            if (!entry.getValue().isEmpty())
            {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }

        if (!filtered.isEmpty())
        {
            diaryWidgetCache.put(areaName, filtered);
            diaryWidgetAreasObservedThisSession.add(areaName);
            debug("diaries", "Scraped diary journal for {} — {} tiers with tasks", areaName, filtered.size());
            exportDiarySnapshot("diary_journal_scraped", DIARY_EXPORT_INTERVAL_MS);
        }
    }

    private void scrapeCollectionLogPage()

    {
        if (!config.exportCollectionLog() || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        // Resolve the entry title from the first candidate child that yields non-empty text.
        // We try the widget's own text first, then its static children, then its dynamic children.
        String entryName = null;
        int foundTitleChild = -1;
        for (int childId : COLLECTION_LOG_TITLE_CHILDREN)
        {
            Widget w = client.getWidget(COLLECTION_LOG_INTERFACE_ID, childId);
            if (w == null)
            {
                continue;
            }

            // Direct text on the widget
            String text = w.getText();
            if (text != null && !text.trim().isEmpty())
            {
                entryName = stripWidgetTags(text).trim();
                foundTitleChild = childId;
                break;
            }

            // Static children
            Widget[] statics = w.getStaticChildren();
            if (statics != null)
            {
                for (Widget child : statics)
                {
                    text = child.getText();
                    if (text != null && !text.trim().isEmpty())
                    {
                        entryName = stripWidgetTags(text).trim();
                        foundTitleChild = childId;
                        break;
                    }
                }
            }
            if (entryName != null)
            {
                break;
            }

            // Dynamic children
            Widget[] dynamics = w.getDynamicChildren();
            if (dynamics != null)
            {
                for (Widget child : dynamics)
                {
                    text = child.getText();
                    if (text != null && !text.trim().isEmpty())
                    {
                        entryName = stripWidgetTags(text).trim();
                        foundTitleChild = childId;
                        break;
                    }
                }
            }
            if (entryName != null)
            {
                break;
            }
        }

        if (entryName == null || entryName.isEmpty())
        {
            recordEvent("collection_log", "collection_log_title_not_found", ImmutableMap.of(
                "note", "interface may not be open or title child IDs need updating",
                "children_tried", Arrays.toString(COLLECTION_LOG_TITLE_CHILDREN)
            ));
            return;
        }

        recordEvent("collection_log", "collection_log_title_found", ImmutableMap.of(
            "entry", entryName, "title_child", foundTitleChild
        ));

        // Determine the active tab from varbit
        int tabIndex = client.getVarbitValue(COLLECTION_LOG_ACTIVE_TAB_VARBIT);
        String tabName = (tabIndex >= 0 && tabIndex < COLLECTION_LOG_TABS.length)
            ? COLLECTION_LOG_TABS[tabIndex] : "Other";

        // Find the items container — the first candidate child that has dynamic children with item IDs
        List<Map<String, Object>> items = null;
        int foundItemsChild = -1;
        for (int childId : COLLECTION_LOG_ITEMS_CHILDREN)
        {
            Widget w = client.getWidget(COLLECTION_LOG_INTERFACE_ID, childId);
            if (w == null)
            {
                continue;
            }
            Widget[] dynamics = w.getDynamicChildren();
            if (dynamics == null || dynamics.length == 0)
            {
                continue;
            }
            List<Map<String, Object>> candidates = extractCollectionLogItems(dynamics);
            if (!candidates.isEmpty())
            {
                items = candidates;
                foundItemsChild = childId;
                break;
            }
            debug("collection_log", "scrapeCollectionLogPage: child {} has {} dynamic children but 0 item widgets",
                childId, dynamics.length);
        }

        if (items == null || items.isEmpty())
        {
            recordEvent("collection_log", "collection_log_items_not_found", ImmutableMap.of(
                "entry", entryName,
                "note", "no item widgets found — items child IDs may need updating",
                "children_tried", Arrays.toString(COLLECTION_LOG_ITEMS_CHILDREN)
            ));
            return;
        }

        int obtainedCount = 0;
        for (Map<String, Object> row : items)
        {
            if (Boolean.TRUE.equals(row.get("obtained")))
            {
                obtainedCount++;
            }
        }

        recordEvent("collection_log", "collection_log_page_scraped", ImmutableMap.of(
            "entry", entryName, "tab", tabName,
            "obtained", obtainedCount, "total", items.size(), "items_child", foundItemsChild
        ));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tab", tabName);
        entry.put("obtained_count", obtainedCount);
        entry.put("total_items", items.size());
        entry.put("items", items);
        entry.put("last_scraped", OffsetDateTime.now().toString());
        collectionLogCache.put(entryName, entry);

        writeCollectionLogSnapshot("collection_log_scraped");
    }

    private List<Map<String, Object>> extractCollectionLogItems(Widget[] widgets)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Widget w : widgets)
        {
            int itemId = w.getItemId();
            if (itemId <= 0)
            {
                continue;
            }
            int canonicalId = itemManager.canonicalize(itemId);
            // opacity 0 = fully visible = obtained; any other value = greyed out = not obtained
            boolean obtained = w.getOpacity() == 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", canonicalId);
            row.put("name", safeItemName(canonicalId));
            row.put("obtained", obtained);
            items.add(row);
        }
        return items;
    }

    private void writeCollectionLogSnapshot(String reason)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!config.exportCollectionLog() || gameState != GameState.LOGGED_IN)
        {
            if (manualPanelRequest)
            {
                panel.markUnavailable("collection_log", "Not ready", "Collection log is only available while logged in.");
            }
            return;
        }

        if (collectionLogCache.isEmpty() &&
            collectionWholeLogQuantities.isEmpty() &&
            collectionWholeLogLastObservedAt == null)
        {
            if (manualPanelRequest)
            {
                panel.markUnavailable(
                    "collection_log",
                    "Open log once",
                    "Open your own Collection Log once; Character Export will sync it automatically."
                );
            }
            return;
        }

        if (!acquireExportSlot(COLLECTION_LOG_FILE, COLLECTION_LOG_EXPORT_INTERVAL_MS))
        {
            if (manualPanelRequest)
            {
                panel.markChecked("collection_log");
            }
            return;
        }

        // Organise entries by tab
        Map<String, Map<String, Object>> tabs = new LinkedHashMap<>();
        for (String tab : COLLECTION_LOG_TABS)
        {
            tabs.put(tab, new LinkedHashMap<>());
        }
        for (Map.Entry<String, Map<String, Object>> e : collectionLogCache.entrySet())
        {
            String entryName = e.getKey();
            Map<String, Object> data = e.getValue();
            String tab = (String) data.getOrDefault("tab", "Other");
            Map<String, Object> tabMap = tabs.get(tab);
            if (tabMap == null)
            {
                tabMap = tabs.computeIfAbsent("Other", k -> new LinkedHashMap<>());
            }
            tabMap.put(entryName, data);
        }

        List<Map<String, Object>> wholeOwnedItems = new ArrayList<>();
        collectionWholeLogQuantities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry ->
            {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", entry.getKey());
                item.put("name", safeItemName(entry.getKey()));
                item.put("quantity", entry.getValue());
                wholeOwnedItems.add(item);
            });

        Map<String, Object> wholeLog = new LinkedHashMap<>();
        wholeLog.put(
            "source",
            "native_collection_log_search_transmission"
        );
        wholeLog.put(
            "snapshot_observed",
            collectionWholeLogLastObservedAt != null
        );
        wholeLog.put(
            "last_observed_at",
            collectionWholeLogLastObservedAt
        );
        wholeLog.put(
            "observed_session_id",
            collectionWholeLogObservedSessionId
        );
        wholeLog.put(
            "owned_item_types",
            wholeOwnedItems.size()
        );
        wholeLog.put(
            "owned_items",
            wholeOwnedItems
        );

        Map<String, Object> payload = basePayload(reason);
        payload.put("entries_scraped", collectionLogCache.size());
        payload.put("whole_log", wholeLog);
        payload.put(
            "note",
            "Open your own Collection Log once to reconcile the whole owned-item snapshot automatically. " +
            "New unlocks are merged while RuneLite is running; page scrapes remain supplemental metadata."
        );
        payload.put("tabs", tabs);

        debug("collection_log", "Writing collection log snapshot reason={} entries={}", reason, collectionLogCache.size());
        writeJson("collection_log", COLLECTION_LOG_FILE, payload);
    }

    @SuppressWarnings("unchecked")
    private void restoreDiaryWidgetCache()
    {
        Path dir = accountOutputDir;
        if (dir == null)
        {
            return;
        }

        Path file = ExportLayout.datasetPath(dir, ExportDataset.DIARIES);
        if (!Files.isRegularFile(file))
        {
            return;
        }

        try
        {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> root = gson.fromJson(raw, Map.class);
            Object versionObject = root.get("plugin_version");
            String version = versionObject != null ? String.valueOf(versionObject) : "";

            // Never import the original 0.6.0 widget completions. Its parser
            // treated struck-through requirement text as whole-task completion.
            // All later fork/refactor versions use the corrected parser.
            if (version.startsWith("0.6.0"))
            {
                debug(
                    "diaries",
                    "Ignored unsafe legacy diary cache from plugin_version={}",
                    version
                );
                return;
            }

            Object diariesObject = root.get("diaries");
            if (!(diariesObject instanceof Map))
            {
                return;
            }

            Map<?, ?> diaries = (Map<?, ?>) diariesObject;
            int restoredAreas = 0;

            for (Map.Entry<?, ?> areaEntry : diaries.entrySet())
            {
                if (!(areaEntry.getKey() instanceof String) ||
                    !(areaEntry.getValue() instanceof Map))
                {
                    continue;
                }

                String areaName = (String) areaEntry.getKey();
                Map<?, ?> tiers = (Map<?, ?>) areaEntry.getValue();
                Map<String, List<Map<String, Object>>> restoredTiers =
                    new LinkedHashMap<>();

                for (Map.Entry<?, ?> tierEntry : tiers.entrySet())
                {
                    if (!(tierEntry.getKey() instanceof String) ||
                        !(tierEntry.getValue() instanceof Map))
                    {
                        continue;
                    }

                    Map<?, ?> tierData = (Map<?, ?>) tierEntry.getValue();
                    Object tasksObject = tierData.get("tasks");
                    String source = tierData.get("tasks_source") != null
                        ? String.valueOf(tierData.get("tasks_source"))
                        : "";

                    if (!(tasksObject instanceof List) || "game_varbit".equals(source))
                    {
                        continue;
                    }

                    List<Map<String, Object>> restoredTasks = new ArrayList<>();
                    for (Object rowObject : (List<?>) tasksObject)
                    {
                        if (!(rowObject instanceof Map))
                        {
                            continue;
                        }

                        Map<?, ?> row = (Map<?, ?>) rowObject;
                        Object nameObject = row.get("name");
                        if (!(nameObject instanceof String))
                        {
                            continue;
                        }

                        Map<String, Object> task = new LinkedHashMap<>();
                        task.put("name", nameObject);
                        task.put("complete", Boolean.TRUE.equals(row.get("complete")));
                        restoredTasks.add(task);
                    }

                    if (!restoredTasks.isEmpty())
                    {
                        restoredTiers.put(
                            (String) tierEntry.getKey(),
                            restoredTasks
                        );
                    }
                }

                if (!restoredTiers.isEmpty())
                {
                    diaryWidgetCache.put(areaName, restoredTiers);
                    restoredAreas++;
                }
            }

            debug("diaries",
                "Restored persisted fork diary cache for {} areas",
                restoredAreas);
        }
        catch (Exception ex)
        {
            debug("diaries",
                "Could not restore persisted diary cache: {}",
                ex.toString());
        }
    }
    private void restoreCollectionLogCache()

    {
        Path dir = accountOutputDir;
        if (dir == null)
        {
            return;
        }

        Path file = ExportLayout.datasetPath(dir, ExportDataset.COLLECTION_LOG);
        if (!Files.isRegularFile(file))
        {
            return;
        }

        try
        {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = gson.fromJson(content, Map.class);
            if (saved == null)
            {
                return;
            }

            Object tabsObj = saved.get("tabs");
            int restored = 0;
            if (tabsObj instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> savedTabs =
                    (Map<String, Object>) tabsObj;
                for (Map.Entry<String, Object> tabEntry :
                    savedTabs.entrySet())
                {
                    if (!(tabEntry.getValue() instanceof Map))
                    {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entries =
                        (Map<String, Object>) tabEntry.getValue();
                    for (Map.Entry<String, Object> entry :
                        entries.entrySet())
                    {
                        if (entry.getValue() instanceof Map)
                        {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> entryData =
                                (Map<String, Object>) entry.getValue();
                            collectionLogCache.put(
                                entry.getKey(),
                                entryData
                            );
                            restored++;
                        }
                    }
                }
            }
            Object wholeLogObj = saved.get("whole_log");
            if (wholeLogObj instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> wholeLog =
                    (Map<String, Object>) wholeLogObj;

                Object observedAt = wholeLog.get("last_observed_at");
                if (observedAt != null)
                {
                    collectionWholeLogLastObservedAt =
                        String.valueOf(observedAt);
                }

                Object observedSessionId =
                    wholeLog.get("observed_session_id");
                if (observedSessionId != null)
                {
                    collectionWholeLogObservedSessionId =
                        String.valueOf(observedSessionId);
                }

                Object ownedItemsObj = wholeLog.get("owned_items");
                if (ownedItemsObj instanceof List)
                {
                    for (Object rowObj : (List<?>) ownedItemsObj)
                    {
                        if (!(rowObj instanceof Map))
                        {
                            continue;
                        }

                        Map<?, ?> row = (Map<?, ?>) rowObj;
                        Object idObj = row.get("id");
                        Object quantityObj = row.get("quantity");
                        if (idObj instanceof Number &&
                            quantityObj instanceof Number)
                        {
                            collectionWholeLogQuantities.put(
                                ((Number) idObj).intValue(),
                                ((Number) quantityObj).intValue()
                            );
                        }
                    }
                }
            }

            debug(
                "collection_log",
                "Restored {} page entries and {} whole-log owned item types from disk",
                restored,
                collectionWholeLogQuantities.size()
            );
        }
        catch (Exception ex)
        {
            debug("collection_log", "Could not restore collection log cache from disk: {}", ex.toString());
        }
    }

    private static String stripWidgetTags(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.replaceAll("<[^>]+>", "");
    }

    private void exportContainerSnapshot(ContainerKind kind, String reason)
    {
        exportContainerSnapshot(kind, reason, CONTAINER_EXPORT_INTERVAL_MS);
    }

    private void exportContainerSnapshot(ContainerKind kind, String reason, long minimumIntervalMs)
    {
        boolean manualPanelRequest = isManualPanelReason(reason);
        GameState gameState = client.getGameState();
        if (!kind.isEnabled(config))
        {
            updateDatasetStatus(kind.getDatasetKey(), "skipped", "disabled", ImmutableMap.of("reason", reason));
            if (manualPanelRequest)
            {
                panel.markUnavailable(kind.getDatasetKey(), "Disabled", "This dataset is disabled in plugin settings.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (gameState != GameState.LOGGED_IN)
        {
            debug(kind.getDatasetKey(), "Skipped {} export reason={} because gameState={}",
                kind.getDatasetKey(), reason, gameState);
            updateDatasetStatus(kind.getDatasetKey(), "skipped", "not_logged_in", ImmutableMap.of(
                "reason", reason,
                "game_state", gameState.name()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable(kind.getDatasetKey(), "Not ready", "This data is only available while logged in.");
            }
            requestObservabilityWrite(false);
            return;
        }

        if (!acquireExportSlot(kind.getFileName(), minimumIntervalMs))
        {
            debug(kind.getDatasetKey(), "Throttled {} export reason={} minimumIntervalMs={}",
                kind.getDatasetKey(), reason, minimumIntervalMs);
            updateDatasetStatus(kind.getDatasetKey(), "throttled", "minimum_interval", ImmutableMap.of(
                "reason", reason,
                "minimum_interval_ms", minimumIntervalMs
            ));
            if (manualPanelRequest)
            {
                panel.markChecked(kind.getDatasetKey());
            }
            requestObservabilityWrite(false);
            return;
        }

        ItemContainer container = client.getItemContainer(kind.getInventoryId());
        if (container == null)
        {
            debug(kind.getDatasetKey(), "Skipped {} export reason={} because container {} is unavailable",
                kind.getDatasetKey(), reason, kind.getInventoryId());
            updateDatasetStatus(kind.getDatasetKey(), "waiting", "container_unavailable", ImmutableMap.of(
                "reason", reason,
                "inventory_id", kind.getInventoryId()
            ));
            if (manualPanelRequest)
            {
                panel.markUnavailable(kind.getDatasetKey(), "Open first", containerUnavailableMessage(kind));
            }
            requestObservabilityWrite(false);
            return;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> placeholders = new ArrayList<>();
        Item[] containerItems = container.getItems();
        for (int slot = 0; slot < containerItems.length; slot++)
        {
            Item item = containerItems[slot];
            if (item == null || item.getId() <= 0)
            {
                continue;
            }

            int rawItemId = item.getId();
            if (kind == ContainerKind.BANK &&
                rawItemId == ItemID.BANK_FILLER)
            {
                continue;
            }

            boolean bankPlaceholder =
                kind == ContainerKind.BANK && isBankPlaceholder(rawItemId);

            if (bankPlaceholder)
            {
                int canonicalId = itemManager.canonicalize(rawItemId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slot", slot);
                row.put("id", canonicalId);
                row.put("raw_id", rawItemId);
                row.put("quantity", 0);
                row.put("name", safeItemName(canonicalId));
                row.put("item_state", "placeholder");
                placeholders.add(row);
                continue;
            }

            if (item.getQuantity() <= 0)
            {
                continue;
            }

            int canonicalId = itemManager.canonicalize(rawItemId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("id", canonicalId);
            row.put("quantity", item.getQuantity());
            row.put("name", safeItemName(canonicalId));
            if (kind == ContainerKind.BANK)
            {
                row.put("item_state", "owned");
            }
            items.add(row);
        }

        Map<String, Object> payload = basePayload(reason);
        payload.put("kind", kind.getDatasetKey());
        payload.put("item_count", items.size());
        payload.put("items", items);
        if (kind == ContainerKind.BANK)
        {
            Map<String, Object> semantics = new LinkedHashMap<>();
            semantics.put("items", "owned");
            semantics.put("placeholders", "not_owned");
            semantics.put("quantity", "owned_quantity");
            payload.put("item_semantics", semantics);
            payload.put("placeholder_count", placeholders.size());
            payload.put("placeholders", placeholders);
        }

        debug(kind.getDatasetKey(), "Prepared {} export reason={} itemCount={}",
            kind.getDatasetKey(), reason, items.size());
        updateDatasetStatus(kind.getDatasetKey(), "prepared", "ready_to_write", ImmutableMap.of(
            "reason", reason,
            "inventory_id", kind.getInventoryId(),
            "item_count", items.size()
        ));
        writeJson(kind.getDatasetKey(), kind.getFileName(), payload);
    }

    private Map<String, Object> buildStats()
    {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Skill skill : Skill.values())
        {
            if (!isTrackedSkill(skill))
            {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("real_level", client.getRealSkillLevel(skill));
            row.put("boosted_level", client.getBoostedSkillLevel(skill));
            row.put("experience", client.getSkillExperience(skill));
            stats.put(skill.getName(), row);
        }
        return stats;
    }

    private boolean isCharacterStateReady()
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null || player.getName().trim().isEmpty())
        {
            return false;
        }

        for (Skill skill : Skill.values())
        {
            if (!isTrackedSkill(skill))
            {
                continue;
            }

            if (client.getRealSkillLevel(skill) > 0)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isTrackedSkill(Skill skill)
    {
        return !"OVERALL".equals(skill.name());
    }

    private List<String> worldTypes()
    {
        List<String> worldTypes = new ArrayList<>();
        for (WorldType worldType : client.getWorldType())
        {
            worldTypes.add(worldType.name());
        }
        return worldTypes;
    }

    private boolean isBankPlaceholder(int itemId)
    {
        try
        {
            ItemComposition composition = itemManager.getItemComposition(itemId);
            return composition != null &&
                composition.getPlaceholderTemplateId() != -1;
        }
        catch (RuntimeException ex)
        {
            debug(
                "bank",
                "Could not inspect placeholder state for item id={} error={}",
                itemId,
                ex.toString()
            );
            return false;
        }
    }
    private String safeItemName(int canonicalId)

    {
        try
        {
            ItemComposition composition = itemManager.getItemComposition(canonicalId);
            if (composition == null)
            {
                return "";
            }

            String name = composition.getName();
            return name != null ? name : "";
        }
        catch (RuntimeException ex)
        {
            debug("item_lookup", "Failed to resolve item name for id={} error={}", canonicalId, ex.toString());
            return "";
        }
    }

    private boolean acquireExportSlot(String key, long minimumIntervalMs)
    {
        if (minimumIntervalMs <= 0L)
        {
            lastExportAt.computeIfAbsent(key, ignored -> new AtomicLong()).set(System.currentTimeMillis());
            return true;
        }

        AtomicLong lastRun = lastExportAt.computeIfAbsent(key, ignored -> new AtomicLong(0L));
        long now = System.currentTimeMillis();
        long previous = lastRun.get();
        if (now - previous < minimumIntervalMs)
        {
            return false;
        }

        lastRun.set(now);
        return true;
    }

    private void writeJson(
        String datasetKey,
        String fileName,
        Map<String, Object> payload)
    {
        Path outputDir = accountOutputDir;
        ExportDataset dataset =
            ExportDataset.fromKey(datasetKey);

        if (!writesEnabled ||
            outputDir == null ||
            exportStore == null ||
            dataset == null)
        {
            updateDatasetStatus(
                datasetKey,
                "error",
                "writes_disabled_or_unknown_dataset",
                ImmutableMap.of("file", fileName)
            );
            requestObservabilityWrite(false);
            return;
        }

        if (!dataset.fragmentFileName().equals(fileName))
        {
            updateDatasetStatus(
                datasetKey,
                "error",
                "dataset_file_mismatch",
                ImmutableMap.of(
                    "expected",
                    dataset.fragmentFileName(),
                    "actual",
                    fileName
                )
            );
            requestObservabilityWrite(false);
            return;
        }

        boolean manualRequest =
            isManualPanelRequest(payload);

        submitWriter(() ->
        {
            try
            {
                ExportStore.WriteResult result =
                    exportStore.writeDataset(
                        outputDir,
                        dataset,
                        payload
                    );

                if (result == ExportStore.WriteResult.UNCHANGED)
                {
                    debug(
                        "writer",
                        "Skipped unchanged dataset {}",
                        dataset.key()
                    );

                    updateDatasetStatus(
                        datasetKey,
                        "unchanged",
                        "stable_payload_unchanged",
                        ImmutableMap.of("file", fileName)
                    );

                    if (manualRequest ||
                        dataset.interactionBacked())
                    {
                        panel.markChecked(datasetKey);
                    }

                    requestObservabilityWrite(false);
                    return;
                }

                // A fragment is independently durable once its atomic write
                // succeeds. Rebuild aggregate views only for datasets they can
                // consume; live.json is intentionally high-churn and already
                // has its own direct public mirror.
                if (dataset != ExportDataset.LIVE)
                {
                    queueDerivedViewRebuild(outputDir);
                }

                warnedFailureKeys.remove(
                    "write:" + fileName
                );

                updateDatasetStatus(
                    datasetKey,
                    "success",
                    "write_complete",
                    ImmutableMap.of("file", fileName)
                );

                recordEvent(
                    "writer",
                    "write_complete",
                    ImmutableMap.of("file", fileName)
                );

                panel.markExported(datasetKey);
                requestObservabilityWrite(false);
            }
            catch (IOException | RuntimeException ex)
            {
                updateDatasetStatus(
                    datasetKey,
                    "error",
                    "write_failed",
                    ImmutableMap.of(
                        "file",
                        fileName,
                        "error",
                        ex.toString()
                    )
                );

                recordEvent(
                    "writer",
                    "write_failed",
                    ImmutableMap.of(
                        "file",
                        fileName,
                        "error",
                        ex.toString()
                    )
                );

                if (manualRequest)
                {
                    panel.markFailed(
                        datasetKey,
                        ex.toString()
                    );
                }

                requestObservabilityWrite(false);
                warnOnce(
                    "write:" + fileName,
                    "Failed writing export dataset " +
                        dataset.key(),
                    ex
                );
            }
        });
    }

    private void queueDerivedViewRebuild(
        Path outputDir)
    {
        if (exportStore == null ||
            outputDir == null ||
            !unifiedRebuildQueued.compareAndSet(false, true))
        {
            return;
        }

        submitWriter(() ->
        {
            try
            {
                // Ignore a queued rebuild for an account that is no longer
                // active. Its fragments are already safely persisted.
                if (!outputDir.equals(accountOutputDir))
                {
                    return;
                }

                exportStore.rebuildDerivedViews(outputDir);
                warnedFailureKeys.remove(
                    "write:derived_views"
                );
            }
            catch (IOException | RuntimeException ex)
            {
                warnOnce(
                    "write:derived_views",
                    "Failed rebuilding derived Character Export views for " +
                        outputDir,
                    ex
                );
            }
            finally
            {
                unifiedRebuildQueued.set(false);
            }
        });
    }

    private static boolean isManualPanelRequest(Map<String, Object> payload)
    {
        Object reason = payload.get("reason");
        return reason instanceof String && isManualPanelReason((String) reason);
    }

    private static boolean isManualPanelReason(String reason)
    {
        return reason != null && reason.startsWith("manual_panel");
    }

    private void manualExportAll()
    {
        panel.beginManualSync();
        // A full Collection Log reconciliation requires the Collection Log
        // interface to be open. Character Export triggers Search automatically
        // when that interface opens; Refresh Now does not open interfaces.
        panel.skipDatasetInSync("collection_log");
        clientThread.invokeLater(() ->
        {
            exportCharacterSnapshot("manual_panel");
            for (ContainerKind containerKind : ContainerKind.values())
            {
                exportContainerSnapshot(containerKind, "manual_panel", 0L);
            }
            exportQuestSnapshot("manual_panel");
            exportDiarySnapshot("manual_panel");
            exportProgressFlags("manual_panel");
            exportTravelGates("manual_panel");
            exportUniversalState("manual_panel");
            exportCombatAchievementSnapshot("manual_panel");
            requestDwmsStorageSnapshot("manual_panel", 0L);
        });
    }

    private static String containerUnavailableMessage(ContainerKind kind)
    {
        switch (kind)
        {
            case BANK:
                return "Open your bank first to refresh this file.";
            case SEED_VAULT:
                return "Open your seed vault first to refresh this file.";
            case INVENTORY:
                return "Inventory data is not available yet. Try again in a moment.";
            case EQUIPMENT:
                return "Equipment data is not available yet. Try again in a moment.";
            default:
                return "This data is not available right now.";
        }
    }

    private void updateDatasetStatus(String dataset, String state, String reason, Map<String, Object> details)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset", dataset);
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("session_id", sessionId);
        payload.put("state", state);
        payload.put("reason", reason);
        payload.put("updated_at", OffsetDateTime.now().toString());
        if (details != null && !details.isEmpty())
        {
            payload.put("details", details);
        }
        datasetStatus.put(dataset, payload);
    }

    private void recordEvent(String area, String type, Map<String, Object> details)
    {
        if (!config.debugLogging())
        {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", OffsetDateTime.now().toString());
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("session_id", sessionId);
        payload.put("area", area);
        payload.put("type", type);
        if (details != null && !details.isEmpty())
        {
            payload.put("details", details);
        }

        synchronized (recentEvents)
        {
            recentEvents.addLast(payload);
            while (recentEvents.size() > MAX_RECENT_EVENTS)
            {
                recentEvents.removeFirst();
            }
        }

        if (!writesEnabled)
        {
            return;
        }

        String serialized = serializePayload("event:" + type, payload);
        if (serialized == null)
        {
            return;
        }

        submitWriter(() -> appendExporterLogSync(serialized));
    }

    private void resetExporterLogSync()
    {
        Path outputPath = diagnosticPath(EXPORTER_LOG_FILE);
        if (outputPath == null)
        {
            return;
        }

        try
        {
            AtomicFileWriter.writeUtf8(outputPath, "");
            warnedFailureKeys.remove(
                "log:" + EXPORTER_LOG_FILE
            );
        }
        catch (IOException ex)
        {
            warnOnce(
                "log:" + EXPORTER_LOG_FILE,
                "Failed resetting exporter log " + outputPath,
                ex
            );
        }
    }

    private void appendExporterLogSync(String serializedLine)
    {
        Path outputPath = diagnosticPath(EXPORTER_LOG_FILE);
        if (outputPath == null)
        {
            return;
        }

        try
        {
            Files.createDirectories(outputPath.getParent());
            if (Files.exists(outputPath) &&
                Files.size(outputPath) >= EXPORTER_LOG_MAX_BYTES)
            {
                AtomicFileWriter.writeUtf8(outputPath, "");
            }

            Files.write(
                outputPath,
                (serializedLine + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            );
            warnedFailureKeys.remove(
                "log:" + EXPORTER_LOG_FILE
            );
        }
        catch (IOException ex)
        {
            warnOnce(
                "log:" + EXPORTER_LOG_FILE,
                "Failed writing exporter log " + outputPath,
                ex
            );
        }
    }

    private void requestObservabilityWrite(boolean force)
    {
        if (!writesEnabled ||
            !config.debugLogging() ||
            accountOutputDir == null)
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && !acquireObservabilitySlot(now))
        {
            return;
        }
        if (force)
        {
            lastObservabilityWriteAt.set(now);
        }

        Map<String, Object> statusPayload = new LinkedHashMap<>();
        statusPayload.put("updated_at", OffsetDateTime.now().toString());
        statusPayload.put("plugin_version", PLUGIN_VERSION);
        statusPayload.put("session_id", sessionId);
        statusPayload.put("game_state", readinessSnapshot.get("game_state"));
        statusPayload.put("readiness", new LinkedHashMap<>(readinessSnapshot));
        statusPayload.put("datasets", new LinkedHashMap<>(datasetStatus));

        List<Map<String, Object>> eventsPayload;
        synchronized (recentEvents)
        {
            eventsPayload = new ArrayList<>(recentEvents);
        }

        Map<String, Object> eventsFile = new LinkedHashMap<>();
        eventsFile.put("updated_at", OffsetDateTime.now().toString());
        eventsFile.put("plugin_version", PLUGIN_VERSION);
        eventsFile.put("session_id", sessionId);
        eventsFile.put("events", eventsPayload);

        String statusJson = serializePayload(STATUS_FILE, statusPayload);
        String eventsJson = serializePayload(RECENT_EVENTS_FILE, eventsFile);
        if (statusJson == null || eventsJson == null)
        {
            return;
        }

        submitWriter(() ->
        {
            writeAuxJsonSync(STATUS_FILE, statusJson);
            writeAuxJsonSync(RECENT_EVENTS_FILE, eventsJson);
        });
    }

    private boolean acquireObservabilitySlot(long now)
    {
        long previous = lastObservabilityWriteAt.get();
        if (now - previous < OBSERVABILITY_WRITE_INTERVAL_MS)
        {
            return false;
        }

        return lastObservabilityWriteAt.compareAndSet(previous, now);
    }

    private void writeAuxJsonSync(
        String fileName,
        String json)
    {
        Path outputPath = diagnosticPath(fileName);
        if (outputPath == null)
        {
            return;
        }

        try
        {
            AtomicFileWriter.writeUtf8(
                outputPath,
                json + System.lineSeparator()
            );
            warnedFailureKeys.remove("aux:" + fileName);
        }
        catch (IOException ex)
        {
            warnOnce(
                "aux:" + fileName,
                "Failed writing observability file " + outputPath,
                ex
            );
        }
    }

    private Path diagnosticPath(String fileName)
    {
        Path dir = accountOutputDir;
        return dir == null
            ? null
            : ExportLayout.diagnosticsDir(dir).resolve(fileName);
    }

    private void submitWriter(Runnable task)
    {
        ExecutorService active = writer;
        if (active == null || active.isShutdown())
        {
            return;
        }

        try
        {
            active.submit(task);
        }
        catch (RuntimeException ex)
        {
            log.debug("Dropped writer task during shutdown: {}", ex.toString());
        }
    }

    private void updateReadinessSnapshot()
    {
        Player player = client.getLocalPlayer();
        readinessSnapshot.put("updated_at", OffsetDateTime.now().toString());
        readinessSnapshot.put("plugin_version", PLUGIN_VERSION);
        readinessSnapshot.put("session_id", sessionId);
        readinessSnapshot.put("writes_enabled", writesEnabled);
        readinessSnapshot.put("pending_initial_character_export", pendingInitialCharacterExport);
        readinessSnapshot.put("local_player_present", player != null);
        readinessSnapshot.put("character_state_ready", isCharacterStateReady());
        readinessSnapshot.put("bank_container_present", client.getItemContainer(InventoryID.BANK) != null);
        readinessSnapshot.put("seed_vault_container_present", client.getItemContainer(InventoryID.SEED_VAULT) != null);
        readinessSnapshot.put("inventory_container_present", client.getItemContainer(InventoryID.INV) != null);
        readinessSnapshot.put("equipment_container_present", client.getItemContainer(InventoryID.WORN) != null);
        readinessSnapshot.put("world", client.getWorld());
        if (currentAccountHash != null)
        {
            readinessSnapshot.put("account_hash", currentAccountHash);
        }
        else
        {
            readinessSnapshot.remove("account_hash");
        }
        readinessSnapshot.put("game_state", client.getGameState().name());
    }

    private Map<String, Object> basePayload(String reason)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exported_at", OffsetDateTime.now().toString());
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("session_id", sessionId);
        payload.put("reason", reason);
        Player player = client.getLocalPlayer();
        payload.put("account_name", player != null ? player.getName() : currentAccountName);
        payload.put("account_hash", currentAccountHash);
        return payload;
    }

    private String serializePayload(String context, Object payload)
    {
        try
        {
            return prettyGson.toJson(payload);
        }
        catch (RuntimeException ex)
        {
            warnOnce("serialize:" + context, "Failed serializing payload for " + context, ex);
            return null;
        }
    }

    private boolean initializeBaseOutputDirectory()
    {
        if (!BASE_OUTPUT_DIR.startsWith(RUNELITE_DIR))
        {
            log.warn("Character State Exporter output directory escapes RuneLite dir: {}", BASE_OUTPUT_DIR);
            return false;
        }

        try
        {
            Files.createDirectories(BASE_OUTPUT_DIR);
            return true;
        }
        catch (IOException ex)
        {
            log.warn("Character State Exporter could not create output directory {}", BASE_OUTPUT_DIR, ex);
            return false;
        }
    }

    private void warnOnce(String key, String message, Exception ex)
    {
        if (warnedFailureKeys.add(key))
        {
            log.warn(message, ex);
        }
        else
        {
            log.debug("{}: {}", message, ex.toString());
        }
    }

    private void debug(String area, String message, Object... args)
    {
        if (!config.debugLogging())
        {
            return;
        }

        log.debug("[" + area + "] " + message, args);
    }

    private static BufferedImage createSidebarIcon()
    {
        BufferedImage resourceIcon = loadSidebarIconResource();
        if (resourceIcon != null)
        {
            return resourceIcon;
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color outline = new Color(78, 58, 34);
        Color parchment = new Color(191, 168, 122);
        Color parchmentShadow = new Color(150, 127, 86);
        Color accent = new Color(109, 42, 30);

        g.setColor(outline);
        g.fillRoundRect(2, 1, 11, 14, 3, 3);

        g.setColor(parchment);
        g.fillRoundRect(3, 2, 9, 12, 2, 2);

        g.setColor(parchmentShadow);
        g.fillPolygon(new int[]{9, 12, 12}, new int[]{2, 2, 5}, 3);

        g.setColor(outline);
        g.drawLine(8, 2, 12, 2);
        g.drawLine(12, 2, 12, 5);

        g.setColor(new Color(121, 93, 58));
        g.drawLine(5, 6, 10, 6);
        g.drawLine(5, 8, 9, 8);

        g.setColor(accent);
        g.fillRect(7, 8, 2, 3);
        g.fillPolygon(new int[]{5, 8, 11}, new int[]{10, 13, 10}, 3);

        g.dispose();
        return image;
    }

    private static BufferedImage loadSidebarIconResource()
    {
        try (InputStream stream = CharacterStateExporterPlugin.class.getResourceAsStream("sidebar_icon.png"))
        {
            if (stream == null)
            {
                return null;
            }

            BufferedImage source = ImageIO.read(stream);
            if (source == null)
            {
                return null;
            }

            BufferedImage scaled = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, 16, 16, null);
            g.dispose();
            return scaled;
        }
        catch (IOException ex)
        {
            return null;
        }
    }

    private enum ContainerKind
    {
        BANK(
            ExportDataset.BANK,
            InventoryID.BANK,
            CONTAINER_EXPORT_INTERVAL_MS
        ),
        SEED_VAULT(
            ExportDataset.SEED_VAULT,
            InventoryID.SEED_VAULT,
            CONTAINER_EXPORT_INTERVAL_MS
        ),
        INVENTORY(
            ExportDataset.INVENTORY,
            InventoryID.INV,
            INVENTORY_EXPORT_INTERVAL_MS
        ),
        EQUIPMENT(
            ExportDataset.EQUIPMENT,
            InventoryID.WORN,
            CONTAINER_EXPORT_INTERVAL_MS
        );

        private final ExportDataset dataset;
        private final int inventoryId;
        private final long minimumIntervalMs;

        ContainerKind(
            ExportDataset dataset,
            int inventoryId,
            long minimumIntervalMs)
        {
            this.dataset = dataset;
            this.inventoryId = inventoryId;
            this.minimumIntervalMs = minimumIntervalMs;
        }

        String getDatasetKey()
        {
            return dataset.key();
        }

        String getFileName()
        {
            return dataset.fragmentFileName();
        }

        int getInventoryId()
        {
            return inventoryId;
        }

        long getMinimumIntervalMs()
        {
            return minimumIntervalMs;
        }

        boolean isEnabled(CharacterStateExporterConfig config)
        {
            switch (this)
            {
                case BANK:
                    return config.exportBank();
                case SEED_VAULT:
                    return config.exportSeedVault();
                case INVENTORY:
                    return config.exportInventory();
                case EQUIPMENT:
                    return config.exportEquipment();
                default:
                    return false;
            }
        }

        static ContainerKind fromCommand(String command)
        {
            switch (command)
            {
                case "bank":
                    return BANK;
                case "seedvault":
                case "seed_vault":
                case "seeds":
                    return SEED_VAULT;
                case "inventory":
                case "inv":
                    return INVENTORY;
                case "equipment":
                case "gear":
                    return EQUIPMENT;
                default:
                    return null;
            }
        }
    }
}
