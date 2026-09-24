package com.dzwnk.exporter;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiaryWidgetMarkupTest
{
    @Test
    public void requirementStrikeDoesNotMarkWholeTaskComplete()
    {
        String raw =
            "Do an unfinished diary task " +
            "(<col=000080><str>50 Agility</str><col=ffffff>)";

        List<DiaryWidgetParser.Line> lines =
            DiaryWidgetParser.splitLines(raw);

        assertFalse(lines.get(0).complete());
    }

    @Test
    public void leadingTaskStrikeStillMarksTaskComplete()
    {
        String raw =
            "<col=000080><str>Do a completed diary task</str>";

        List<DiaryWidgetParser.Line> lines =
            DiaryWidgetParser.splitLines(raw);

        assertTrue(lines.get(0).complete());
    }
}
