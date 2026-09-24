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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure parser for Achievement Diary journal widget markup.
 *
 * The parser is intentionally detached from RuneLite UI state so the tricky
 * wrapped-line and strike-through rules can be regression-tested without a
 * client.
 */
final class DiaryWidgetParser
{
    enum ContinuationKind
    {
        NONE,
        TEXT,
        REQUIREMENT
    }

    static final class Line
    {
        private final String text;
        private final boolean complete;

        private Line(String text, boolean complete)
        {
            this.text = text;
            this.complete = complete;
        }

        String text()
        {
            return text;
        }

        boolean complete()
        {
            return complete;
        }
    }

    private DiaryWidgetParser()
    {
    }

    static void appendTaskLine(
        List<Map<String, Object>> tasks,
        String clean,
        boolean complete)
    {
        if (shouldSkipTaskLine(clean))
        {
            return;
        }

        ContinuationKind continuationKind =
            classifyContinuation(tasks, clean);

        if (continuationKind != ContinuationKind.NONE)
        {
            Map<String, Object> previousTask =
                tasks.get(tasks.size() - 1);
            String previousName =
                String.valueOf(previousTask.get("name"));

            previousTask.put(
                "name",
                joinTaskText(previousName, clean)
            );

            if (continuationKind == ContinuationKind.TEXT &&
                complete)
            {
                previousTask.put("complete", true);
            }
            return;
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", clean);
        task.put("complete", complete);
        tasks.add(task);
    }

    static List<Line> splitLines(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return java.util.Collections.emptyList();
        }

        String normalized = raw.replace('\r', '\n');
        List<Line> lines = new ArrayList<>();

        for (String part :
            normalized.split("(?i)<br\\s*/?>"))
        {
            String clean = stripWidgetTags(part).trim();
            if (!clean.isEmpty())
            {
                lines.add(
                    new Line(
                        clean,
                        hasLeadingTaskStrike(part)
                    )
                );
            }
        }

        return lines;
    }

    static boolean hasLeadingTaskStrike(String rawLine)
    {
        if (rawLine == null)
        {
            return false;
        }

        String remaining =
            rawLine.trim().toLowerCase();

        while (remaining.startsWith("<col="))
        {
            int end = remaining.indexOf('>');
            if (end < 0)
            {
                break;
            }

            remaining =
                remaining.substring(end + 1).trim();
        }

        return remaining.startsWith("<str>");
    }

    private static boolean shouldSkipTaskLine(String clean)
    {
        String normalized =
            clean.trim().toLowerCase();
        return normalized.startsWith("if i ever lose my ");
    }

    private static ContinuationKind classifyContinuation(
        List<Map<String, Object>> tasks,
        String clean)
    {
        if (tasks.isEmpty() || clean.isEmpty())
        {
            return ContinuationKind.NONE;
        }

        if (clean.startsWith("("))
        {
            return ContinuationKind.REQUIREMENT;
        }

        char first = clean.charAt(0);
        if (Character.isLowerCase(first) ||
            isInlinePunctuation(first))
        {
            return ContinuationKind.TEXT;
        }

        String previousName = String.valueOf(
            tasks.get(tasks.size() - 1).get("name")
        );

        if (!endsSentence(previousName))
        {
            return ContinuationKind.TEXT;
        }

        return ContinuationKind.NONE;
    }

    private static String joinTaskText(
        String previous,
        String continuation)
    {
        if (previous.isEmpty())
        {
            return continuation;
        }

        if (continuation.isEmpty())
        {
            return previous;
        }

        if (isInlinePunctuation(continuation.charAt(0)))
        {
            return previous + continuation;
        }

        return previous + " " + continuation;
    }

    private static boolean endsSentence(String text)
    {
        if (text == null || text.isEmpty())
        {
            return false;
        }

        char last = text.charAt(text.length() - 1);
        return last == '.' ||
            last == '!' ||
            last == '?' ||
            last == ')';
    }

    private static boolean isInlinePunctuation(char c)
    {
        return c == '.' ||
            c == ',' ||
            c == ';' ||
            c == ':' ||
            c == ')' ||
            c == ']' ||
            c == '}';
    }

    private static String stripWidgetTags(String text)
    {
        if (text == null)
        {
            return "";
        }

        return text.replaceAll("<[^>]+>", "");
    }
}
