package com.dzwnk.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;

/**
 * Snapshots only explicitly-manifested raw game variables.
 *
 * This class intentionally does not interpret route semantics. It exports
 * raw facts; downstream consumers decide what a value proves.
 */
final class ProgressFlagExporter
{
    private static final int SCHEMA_VERSION = 1;
    private static final int[] FALLBACK_VARBITS = {11178, 5421, 3264};
    private static final String EMBEDDED_MANIFEST = "progress_manifest.json";

    private ProgressFlagExporter()
    {
    }

    static Map<String, Object> snapshot(Client client, Path externalManifestPath)
    {
        Set<Integer> varbits = new LinkedHashSet<>();
        Set<Integer> varplayers = new LinkedHashSet<>();
        for (int id : FALLBACK_VARBITS)
        {
            varbits.add(id);
        }

        boolean manifestLoaded = false;
        String manifestSource = "fallback_only";
        String manifestError = null;

        JsonObject manifest = null;

        if (externalManifestPath != null && Files.isRegularFile(externalManifestPath))
        {
            try
            {
                String raw = Files.readString(externalManifestPath, StandardCharsets.UTF_8);
                manifest = new JsonParser().parse(raw).getAsJsonObject();
                manifestLoaded = true;
                manifestSource = "external";
            }
            catch (Exception ex)
            {
                manifestError = ex.toString();
            }
        }

        if (manifest == null)
        {
            try (InputStream stream = ProgressFlagExporter.class.getResourceAsStream(EMBEDDED_MANIFEST))
            {
                if (stream != null)
                {
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
                    {
                        manifest = new JsonParser().parse(reader).getAsJsonObject();
                        manifestLoaded = true;
                        manifestSource = "embedded";
                    }
                }
            }
            catch (Exception ex)
            {
                if (manifestError == null)
                {
                    manifestError = ex.toString();
                }
                else
                {
                    manifestError = manifestError + " | embedded: " + ex;
                }
            }
        }

        if (manifest != null)
        {
            addIds(manifest.getAsJsonArray("varbits"), varbits);
            addIds(manifest.getAsJsonArray("varplayers"), varplayers);
        }

        Map<String, Integer> varbitValues = new LinkedHashMap<>();
        Map<String, Integer> varplayerValues = new LinkedHashMap<>();
        List<Integer> failedVarbits = new ArrayList<>();
        List<Integer> failedVarplayers = new ArrayList<>();

        for (int id : varbits)
        {
            try
            {
                varbitValues.put(Integer.toString(id), client.getVarbitValue(id));
            }
            catch (RuntimeException ex)
            {
                failedVarbits.add(id);
            }
        }

        for (int id : varplayers)
        {
            try
            {
                varplayerValues.put(Integer.toString(id), client.getVarpValue(id));
            }
            catch (RuntimeException ex)
            {
                failedVarplayers.add(id);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", SCHEMA_VERSION);
        result.put("available", true);
        result.put("manifest_loaded", manifestLoaded);
        result.put("manifest_source", manifestSource);
        result.put(
            "external_manifest_override",
            externalManifestPath != null &&
                Files.isRegularFile(externalManifestPath)
        );
        result.put("manifest_error", manifestError);
        result.put("varbit_count", varbitValues.size());
        result.put("varplayer_count", varplayerValues.size());
        result.put("failed_varbits", failedVarbits);
        result.put("failed_varplayers", failedVarplayers);
        result.put("varbits", varbitValues);
        result.put("varplayers", varplayerValues);
        return result;
    }

    private static void addIds(JsonArray array, Set<Integer> output)
    {
        if (array == null)
        {
            return;
        }

        for (JsonElement element : array)
        {
            if (element == null || !element.isJsonPrimitive())
            {
                continue;
            }

            try
            {
                int id = element.getAsInt();
                if (id >= 0)
                {
                    output.add(id);
                }
            }
            catch (RuntimeException ignored)
            {
                // Ignore one malformed manifest entry without losing the rest.
            }
        }
    }
}
