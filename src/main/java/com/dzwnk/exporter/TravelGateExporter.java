package com.dzwnk.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;

/**
 * Evaluates only the VarBit/VarPlayer gate portion of transport definitions.
 *
 * This deliberately does NOT claim that a transport is fully usable: item,
 * quest, skill, location, wilderness, charge, and other requirements remain
 * separate concerns. The output is therefore "travel gate state", not route
 * availability.
 */
final class TravelGateExporter
{
    private static final int SCHEMA_VERSION = 1;
    private static final String RESOURCE = "travel_gate_manifest.json";
    private static volatile JsonArray cachedRows;

    private TravelGateExporter()
    {
    }

    static Map<String, Object> snapshot(Client client)
    {
        JsonArray rows = loadRows();

        List<Map<String, Object>> gates = new ArrayList<>();
        int satisfied = 0;
        int blocked = 0;
        int unknown = 0;
        int cooldown = 0;

        for (JsonElement element : rows)
        {
            if (element == null || !element.isJsonObject())
            {
                continue;
            }

            JsonObject source = element.getAsJsonObject();
            List<Map<String, Object>> checks = new ArrayList<>();
            EvalSummary summary = new EvalSummary();

            evaluateField(client, source, "varbits", true, checks, summary);
            evaluateField(client, source, "varplayers", false, checks, summary);

            String state;
            if (summary.unknown)
            {
                state = "unknown";
                unknown++;
            }
            else if (summary.allSatisfied)
            {
                state = "satisfied";
                satisfied++;
            }
            else
            {
                state = "blocked";
                blocked++;
            }

            if (summary.hasCooldown)
            {
                cooldown++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            copyString(source, row, "source");
            copyNumber(source, row, "line");
            copyString(source, row, "label");
            copyString(source, row, "origin");
            copyString(source, row, "destination");
            copyString(source, row, "skills");
            copyString(source, row, "items");
            copyString(source, row, "quests");
            row.put("state", state);
            row.put("variable_gate_satisfied",
                summary.unknown ? null : summary.allSatisfied);
            row.put("has_cooldown_condition", summary.hasCooldown);
            row.put("checks", checks);
            gates.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", SCHEMA_VERSION);
        result.put("semantics",
            "Evaluates Shortest Path VarBit/VarPlayer requirements only; " +
            "does not imply full transport availability.");
        result.put("gate_count", gates.size());
        result.put("satisfied_count", satisfied);
        result.put("blocked_count", blocked);
        result.put("unknown_count", unknown);
        result.put("cooldown_gate_count", cooldown);
        result.put("gates", gates);
        return result;
    }

    private static void evaluateField(
        Client client,
        JsonObject source,
        String field,
        boolean varbit,
        List<Map<String, Object>> checks,
        EvalSummary summary)
    {
        JsonElement value = source.get(field);
        if (value == null || value.isJsonNull())
        {
            return;
        }

        String raw = value.getAsString();
        if (raw == null || raw.trim().isEmpty())
        {
            return;
        }

        for (String token : raw.split(";"))
        {
            String clause = token.trim();
            if (clause.isEmpty())
            {
                continue;
            }

            Map<String, Object> check = evaluateClause(client, clause, varbit);
            checks.add(check);

            Object parsed = check.get("parsed");
            Object sat = check.get("satisfied");

            if (!Boolean.TRUE.equals(parsed) || !(sat instanceof Boolean))
            {
                summary.unknown = true;
                summary.allSatisfied = false;
            }
            else if (!Boolean.TRUE.equals(sat))
            {
                summary.allSatisfied = false;
            }

            if ("@".equals(check.get("operator")))
            {
                summary.hasCooldown = true;
            }
        }
    }

    private static Map<String, Object> evaluateClause(
        Client client,
        String clause,
        boolean varbit)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", varbit ? "varbit" : "varplayer");
        out.put("expression", clause);

        int opIndex = -1;
        char operator = 0;
        for (int i = 0; i < clause.length(); i++)
        {
            char c = clause.charAt(i);
            if (c == '=' || c == '>' || c == '<' || c == '&' || c == '@')
            {
                opIndex = i;
                operator = c;
                break;
            }
        }

        if (opIndex <= 0 || opIndex >= clause.length() - 1)
        {
            out.put("parsed", false);
            out.put("satisfied", null);
            return out;
        }

        try
        {
            int id = Integer.parseInt(clause.substring(0, opIndex).trim());
            int expected = Integer.parseInt(clause.substring(opIndex + 1).trim());
            int current = varbit
                ? client.getVarbitValue(id)
                : client.getVarpValue(id);

            boolean satisfied;
            switch (operator)
            {
                case '=':
                    satisfied = current == expected;
                    break;
                case '>':
                    satisfied = current > expected;
                    break;
                case '<':
                    satisfied = current < expected;
                    break;
                case '&':
                    satisfied = (current & expected) > 0;
                    break;
                case '@':
                    // Match Shortest Path's wall-clock minute semantics.
                    satisfied =
                        ((System.currentTimeMillis() / 60000L) - current) >
                        expected;
                    break;
                default:
                    out.put("parsed", false);
                    out.put("satisfied", null);
                    return out;
            }

            out.put("parsed", true);
            out.put("id", id);
            out.put("operator", String.valueOf(operator));
            out.put("expected", expected);
            out.put("current", current);
            out.put("satisfied", satisfied);
            return out;
        }
        catch (RuntimeException ex)
        {
            out.put("parsed", false);
            out.put("satisfied", null);
            out.put("error", ex.getClass().getSimpleName());
            return out;
        }
    }

    private static JsonArray loadRows()
    {
        JsonArray local = cachedRows;
        if (local != null)
        {
            return local;
        }

        synchronized (TravelGateExporter.class)
        {
            if (cachedRows != null)
            {
                return cachedRows;
            }

            try (InputStream stream =
                     TravelGateExporter.class.getResourceAsStream(RESOURCE))
            {
                if (stream == null)
                {
                    cachedRows = new JsonArray();
                    return cachedRows;
                }

                try (InputStreamReader reader =
                         new InputStreamReader(stream, StandardCharsets.UTF_8))
                {
                    JsonObject root =
                        new JsonParser().parse(reader).getAsJsonObject();
                    JsonArray rows = root.getAsJsonArray("rows");
                    cachedRows = rows != null ? rows : new JsonArray();
                    return cachedRows;
                }
            }
            catch (Exception ex)
            {
                cachedRows = new JsonArray();
                return cachedRows;
            }
        }
    }

    private static void copyString(
        JsonObject source,
        Map<String, Object> target,
        String key)
    {
        JsonElement value = source.get(key);
        if (value != null && !value.isJsonNull())
        {
            target.put(key, value.getAsString());
        }
    }

    private static void copyNumber(
        JsonObject source,
        Map<String, Object> target,
        String key)
    {
        JsonElement value = source.get(key);
        if (value != null && !value.isJsonNull())
        {
            target.put(key, value.getAsInt());
        }
    }

    private static final class EvalSummary
    {
        private boolean allSatisfied = true;
        private boolean unknown;
        private boolean hasCooldown;
    }
}
