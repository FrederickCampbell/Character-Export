/*
 * Character Export integration:
 * Copyright (c) 2026, DZWNK
 *
 * Runtime Combat Achievement catalogue enumeration and completion-bit
 * decoding in this file are adapted from cdfisher/ca-export.
 *
 * Copyright (c) 2025, cdfisher <https://github.com/cdfisher>
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Reads the Combat Achievement catalogue from the live game cache instead of
 * shipping a hard-coded list of task structs.
 */
final class CombatAchievementExporter
{
    private static final int CA_PARAM_ID = 1306;
    private static final int CA_PARAM_NAME = 1308;

    // These are the six game-cache enums used by the Combat Achievement UI.
    private static final int[] TIER_ENUM_IDS =
        {3981, 3982, 3983, 3984, 3985, 3986};

    private static final String[] TIER_NAMES =
        {"easy", "medium", "hard", "elite", "master", "grandmaster"};

    private static final int[] TIER_COMPLETE_VARBITS =
        {12863, 12864, 12865, 12866, 12867, 12868};

    private static final int[] TIER_TASK_COUNT_VARBITS =
        {12885, 12886, 12887, 12888, 12889, 12890};

    private static final int[] COMPLETION_VARPS = {
        VarPlayerID.CA_TASK_COMPLETED_0,
        VarPlayerID.CA_TASK_COMPLETED_1,
        VarPlayerID.CA_TASK_COMPLETED_2,
        VarPlayerID.CA_TASK_COMPLETED_3,
        VarPlayerID.CA_TASK_COMPLETED_4,
        VarPlayerID.CA_TASK_COMPLETED_5,
        VarPlayerID.CA_TASK_COMPLETED_6,
        VarPlayerID.CA_TASK_COMPLETED_7,
        VarPlayerID.CA_TASK_COMPLETED_8,
        VarPlayerID.CA_TASK_COMPLETED_9,
        VarPlayerID.CA_TASK_COMPLETED_10,
        VarPlayerID.CA_TASK_COMPLETED_11,
        VarPlayerID.CA_TASK_COMPLETED_12,
        VarPlayerID.CA_TASK_COMPLETED_13,
        VarPlayerID.CA_TASK_COMPLETED_14,
        VarPlayerID.CA_TASK_COMPLETED_15,
        VarPlayerID.CA_TASK_COMPLETED_16,
        VarPlayerID.CA_TASK_COMPLETED_17,
        VarPlayerID.CA_TASK_COMPLETED_18,
        VarPlayerID.CA_TASK_COMPLETED_19,
        VarPlayerID.CA_TASK_COMPLETED_20
    };

    private final List<Task> tasks = new ArrayList<>();

    void clear()
    {
        tasks.clear();
    }

    int reload(Client client)
    {
        LinkedHashMap<Integer, Task> byId = new LinkedHashMap<>();

        for (int tierIndex = 0;
             tierIndex < TIER_ENUM_IDS.length;
             tierIndex++)
        {
            EnumComposition tierEnum =
                client.getEnum(TIER_ENUM_IDS[tierIndex]);
            if (tierEnum == null)
            {
                continue;
            }

            int[] structIds = tierEnum.getIntVals();
            if (structIds == null)
            {
                continue;
            }

            for (int structId : structIds)
            {
                StructComposition struct =
                    client.getStructComposition(structId);
                if (struct == null)
                {
                    continue;
                }

                int id = struct.getIntValue(CA_PARAM_ID);
                if (id < 0)
                {
                    continue;
                }

                String name = struct.getStringValue(CA_PARAM_NAME);
                if (name == null || name.trim().isEmpty())
                {
                    name = "Task " + id;
                }

                byId.put(
                    id,
                    new Task(id, name, tierIndex)
                );
            }
        }

        tasks.clear();
        tasks.addAll(byId.values());
        return tasks.size();
    }

    Map<String, Object> snapshot(Client client)
    {
        if (tasks.isEmpty())
        {
            reload(client);
        }

        Map<String, List<Map<String, Object>>> tierTasks =
            new LinkedHashMap<>();
        for (String tierName : TIER_NAMES)
        {
            tierTasks.put(tierName, new ArrayList<>());
        }

        boolean completionBitsCoverCatalogue = true;

        for (Task task : tasks)
        {
            int varpIndex = task.id / 32;
            Boolean complete = null;

            if (varpIndex >= 0 &&
                varpIndex < COMPLETION_VARPS.length)
            {
                int value =
                    client.getVarpValue(COMPLETION_VARPS[varpIndex]);
                complete = isTaskComplete(task.id, value);
            }
            else
            {
                completionBitsCoverCatalogue = false;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.id);
            row.put("name", task.name);
            row.put("complete", complete);
            tierTasks.get(TIER_NAMES[task.tierIndex]).add(row);
        }

        Map<String, Object> tiers = new LinkedHashMap<>();
        int totalTasksCompleted = 0;
        int totalTiersCompleted = 0;
        boolean mappingConsistent =
            !tasks.isEmpty() && completionBitsCoverCatalogue;

        for (int i = 0; i < TIER_NAMES.length; i++)
        {
            String tierName = TIER_NAMES[i];
            List<Map<String, Object>> rows = tierTasks.get(tierName);

            int decodedCompleted = 0;
            boolean decodedComplete = true;
            for (Map<String, Object> row : rows)
            {
                Object complete = row.get("complete");
                if (Boolean.TRUE.equals(complete))
                {
                    decodedCompleted++;
                }
                else if (!(complete instanceof Boolean))
                {
                    decodedComplete = false;
                }
            }

            int officialCompleted =
                client.getVarbitValue(TIER_TASK_COUNT_VARBITS[i]);

            boolean tierMappingConsistent =
                decodedComplete &&
                decodedCompleted == officialCompleted;

            if (!tierMappingConsistent)
            {
                mappingConsistent = false;
            }

            boolean tierComplete =
                client.getVarbitValue(TIER_COMPLETE_VARBITS[i]) == 2;

            Map<String, Object> tier = new LinkedHashMap<>();
            tier.put("complete", tierComplete);
            tier.put("tasks_completed", officialCompleted);
            tier.put("decoded_tasks_completed", decodedCompleted);
            tier.put("tasks_total", rows.size());
            tier.put(
                "named_mapping_consistent",
                tierMappingConsistent
            );
            tier.put("tasks", rows);
            tiers.put(tierName, tier);

            totalTasksCompleted += officialCompleted;
            if (tierComplete)
            {
                totalTiersCompleted++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("catalogue_tasks", tasks.size());
        summary.put(
            "total_tasks_completed",
            totalTasksCompleted
        );
        summary.put(
            "total_tiers_completed",
            totalTiersCompleted
        );
        summary.put(
            "named_data_available",
            !tasks.isEmpty() && mappingConsistent
        );
        summary.put(
            "named_mapping_consistent",
            mappingConsistent
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("tiers", tiers);
        return result;
    }

    static boolean isTaskComplete(int taskId, int varpValue)
    {
        return (varpValue & (1 << (taskId % 32))) != 0;
    }

    private static final class Task
    {
        private final int id;
        private final String name;
        private final int tierIndex;

        private Task(
            int id,
            String name,
            int tierIndex)
        {
            this.id = id;
            this.name = name;
            this.tierIndex = tierIndex;
        }
    }
}
