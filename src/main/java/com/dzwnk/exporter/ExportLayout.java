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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the on-disk Character Export layout and one-time layout migrations.
 */
final class ExportLayout
{
    static final String CACHE_DIR = ".cache";
    static final String DIAGNOSTICS_DIR = "diagnostics";

    private static final String MIGRATION_MARKER = ".layout-v2-migrated";

    private static final List<String> LEGACY_ROOT_FILES = Arrays.asList(
        "character.json",
        "quests.json",
        "diaries.json",
        "combat_achievements.json",
        "inventory.json",
        "equipment.json",
        "bank.json",
        "seed_vault.json",
        "collection_log.json",
        "travel_gates.json",
        "progress_flags.json",
        "state.json",
        "live_state.json",
        "slayer.json",
        "grand_exchange.json",
        "recurring.json"
    );

    private static final List<String> RETIRED_CACHE_FILES = Arrays.asList(
        "live_state.json",
        "slayer.json",
        "grand_exchange.json",
        "recurring.json"
    );

    private static final Set<Path> MIGRATED_DIRS =
        Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());

    private ExportLayout()
    {
    }

    static Path cacheDir(Path accountDir)
    {
        return accountDir.resolve(CACHE_DIR).normalize();
    }

    static Path cachePath(Path accountDir, String fileName)
    {
        return cacheDir(accountDir).resolve(fileName).normalize();
    }

    static Path datasetPath(Path accountDir, ExportDataset dataset)
    {
        return cachePath(accountDir, dataset.fragmentFileName());
    }

    static Path publicPath(Path accountDir, String fileName)
    {
        return accountDir.resolve(fileName).normalize();
    }

    static Path panelPath(Path accountDir, ExportDataset dataset)
    {
        String publicFile = dataset.publicFileName();
        return publicFile == null
            ? datasetPath(accountDir, dataset)
            : publicPath(accountDir, publicFile);
    }

    static Path diagnosticsDir(Path accountDir)
    {
        return accountDir.resolve(DIAGNOSTICS_DIR).normalize();
    }

    /**
     * Migrates the old many-files-in-account-root layout into .cache.
     *
     * The process marker is recorded only after the disk migration succeeds.
     * A failed migration therefore remains retryable in the same RuneLite
     * process instead of being silently suppressed.
     */
    static void migrateLegacyFiles(Path accountDir) throws IOException
    {
        if (accountDir == null)
        {
            return;
        }

        Path normalized = accountDir.toAbsolutePath().normalize();
        if (MIGRATED_DIRS.contains(normalized))
        {
            return;
        }

        Path cacheDir = cacheDir(normalized);
        Files.createDirectories(cacheDir);

        Path marker = cacheDir.resolve(MIGRATION_MARKER);
        if (Files.isRegularFile(marker))
        {
            MIGRATED_DIRS.add(normalized);
            return;
        }

        try
        {
            for (String fileName : LEGACY_ROOT_FILES)
            {
                Path legacy = normalized.resolve(fileName);
                if (!Files.isRegularFile(legacy))
                {
                    continue;
                }

                Path destination = cacheDir.resolve(fileName);
                if (Files.isRegularFile(destination))
                {
                    FileTime legacyTime = Files.getLastModifiedTime(legacy);
                    FileTime cachedTime = Files.getLastModifiedTime(destination);
                    if (legacyTime.compareTo(cachedTime) <= 0)
                    {
                        Files.deleteIfExists(legacy);
                        continue;
                    }
                }

                Files.move(
                    legacy,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }

            Files.write(
                marker,
                Collections.singletonList(
                    "Character Export unified layout migrated " +
                        OffsetDateTime.now()
                ),
                StandardCharsets.UTF_8
            );

            MIGRATED_DIRS.add(normalized);
        }
        catch (IOException | RuntimeException ex)
        {
            MIGRATED_DIRS.remove(normalized);
            throw ex;
        }
    }


    static void cleanupRetiredCacheFiles(Path accountDir) throws IOException
    {
        if (accountDir == null)
        {
            return;
        }

        for (String fileName : RETIRED_CACHE_FILES)
        {
            Files.deleteIfExists(cachePath(accountDir, fileName));
        }
    }

    static void forgetMigrationState(Path accountDir)
    {
        if (accountDir != null)
        {
            MIGRATED_DIRS.remove(accountDir.toAbsolutePath().normalize());
        }
    }
}
