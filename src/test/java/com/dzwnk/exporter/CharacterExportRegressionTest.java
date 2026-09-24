package com.dzwnk.exporter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CharacterExportRegressionTest
{
    @Test
    public void combatAchievementCompletionUsesInternalTaskId()
    {
        int completionVarp = 1 << 24;

        Assert.assertTrue(
            CombatAchievementExporter.isTaskComplete(
                24,
                completionVarp
            )
        );
        Assert.assertFalse(
            CombatAchievementExporter.isTaskComplete(
                1,
                completionVarp
            )
        );
    }

    @Test
    public void appendDiaryTaskLineMergesWrappedTaskText()
    {
        List<Map<String, Object>> tasks = new ArrayList<>();

        DiaryWidgetParser.appendTaskLine(
            tasks,
            "Harvest some strawberries from the Ardougne farming",
            false
        );
        DiaryWidgetParser.appendTaskLine(
            tasks,
            "patch.(31 Farming)",
            false
        );

        Assert.assertEquals(1, tasks.size());
        Assert.assertEquals(
            "Harvest some strawberries from the Ardougne farming patch.(31 Farming)",
            tasks.get(0).get("name")
        );
        Assert.assertEquals(
            Boolean.FALSE,
            tasks.get(0).get("complete")
        );
    }

    @Test
    public void appendDiaryTaskLineSkipsRewardReclaimHint()
    {
        List<Map<String, Object>> tasks = new ArrayList<>();

        DiaryWidgetParser.appendTaskLine(
            tasks,
            "If I ever lose my Rada's blessing, I can speak to Elise outside Kourend Castle.",
            false
        );

        Assert.assertEquals(0, tasks.size());
    }

    @Test
    public void appendDiaryRequirementDoesNotFlipCompletion()
    {
        List<Map<String, Object>> tasks = new ArrayList<>();

        DiaryWidgetParser.appendTaskLine(
            tasks,
            "Use Kharedst's memoirs to teleport to all five cities in Great Kourend.",
            true
        );
        DiaryWidgetParser.appendTaskLine(
            tasks,
            "(The Depths of Despair, The Queen of Thieves, Tale of the Righteous, The Forsaken Tower, The Ascent of Arceuus)",
            true
        );
        DiaryWidgetParser.appendTaskLine(
            tasks,
            "Mine some Volcanic sulphur.(42 Mining)",
            true
        );

        Assert.assertEquals(2, tasks.size());
        Assert.assertEquals(
            "Use Kharedst's memoirs to teleport to all five cities in Great Kourend. " +
                "(The Depths of Despair, The Queen of Thieves, Tale of the Righteous, " +
                "The Forsaken Tower, The Ascent of Arceuus)",
            tasks.get(0).get("name")
        );
        Assert.assertEquals(
            Boolean.TRUE,
            tasks.get(0).get("complete")
        );
        Assert.assertEquals(
            "Mine some Volcanic sulphur.(42 Mining)",
            tasks.get(1).get("name")
        );
    }

    @Test
    public void splitDiaryWidgetLinesPreservesBrSeparatedTasks()
    {
        List<DiaryWidgetParser.Line> lines =
            DiaryWidgetParser.splitLines(
                "<col=ff0000>Use Kharedst's memoirs to teleport to all five cities in Great Kourend.</col><br>" +
                    "<col=ff0000>(The Depths of Despair, The Queen of Thieves, Tale of the Righteous, " +
                    "The Forsaken Tower, The Ascent of Arceuus)</col><br>" +
                    "<str>Mine some Volcanic sulphur.(42 Mining)</str><br>" +
                    "<str>Enter the Farming Guild.(45 Farming)</str>"
            );

        Assert.assertEquals(4, lines.size());
        Assert.assertFalse(lines.get(0).complete());
        Assert.assertFalse(lines.get(1).complete());
        Assert.assertTrue(lines.get(2).complete());
        Assert.assertTrue(lines.get(3).complete());
    }

    @Test
    public void formatStatusTimeStaysCompactAndTooltipIncludesDate()
    {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate yesterday =
            LocalDate.now(zone).minusDays(1);
        Instant olderExport =
            yesterday.atStartOfDay(zone)
                .plusHours(15)
                .toInstant();

        String formatted =
            CharacterStateExporterPanel.formatStatusTime(
                olderExport
            );
        String tooltip =
            CharacterStateExporterPanel.formatStatusTooltip(
                olderExport,
                "Saved"
            );

        Assert.assertFalse(
            formatted.contains(
                String.valueOf(
                    yesterday.getDayOfMonth()
                )
            )
        );
        Assert.assertTrue(tooltip.contains("Saved"));
        Assert.assertTrue(
            tooltip.contains(
                String.valueOf(
                    yesterday.getDayOfMonth()
                )
            )
        );
    }
}
