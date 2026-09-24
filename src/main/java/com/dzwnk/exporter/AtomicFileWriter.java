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
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Atomic UTF-8 file replacement with bounded retry for short Windows sharing
 * conflicts (for example a consumer opening character.json while it is being
 * replaced).
 */
final class AtomicFileWriter
{
    private static final long[] RETRY_DELAYS_MS =
        {15L, 30L, 60L, 120L, 240L, 400L};

    @FunctionalInterface
    interface MoveOperation
    {
        void move(Path source, Path target) throws IOException;
    }

    private AtomicFileWriter()
    {
    }

    static void writeUtf8(Path outputPath, String content)
        throws IOException
    {
        Files.createDirectories(outputPath.getParent());

        Path tempPath = outputPath.resolveSibling(
            outputPath.getFileName().toString() +
                ".tmp-" +
                UUID.randomUUID()
        );

        try
        {
            Files.write(
                tempPath,
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );

            moveWithRetry(
                tempPath,
                outputPath,
                AtomicFileWriter::replaceAtomicallyWhenSupported,
                RETRY_DELAYS_MS
            );
        }
        finally
        {
            Files.deleteIfExists(tempPath);
        }
    }

    static void moveWithRetry(
        Path source,
        Path target,
        MoveOperation operation,
        long[] retryDelaysMs)
        throws IOException
    {
        IOException lastFailure = null;
        int attempts = retryDelaysMs.length + 1;

        for (int attempt = 0; attempt < attempts; attempt++)
        {
            try
            {
                operation.move(source, target);
                return;
            }
            catch (IOException ex)
            {
                lastFailure = ex;

                if (!isRetryable(ex) ||
                    attempt >= retryDelaysMs.length)
                {
                    throw ex;
                }

                try
                {
                    Thread.sleep(retryDelaysMs[attempt]);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                        "Interrupted while retrying file replacement for " +
                            target,
                        interrupted
                    );
                }
            }
        }

        throw lastFailure == null
            ? new IOException("Failed replacing " + target)
            : lastFailure;
    }

    private static void replaceAtomicallyWhenSupported(
        Path source,
        Path target)
        throws IOException
    {
        try
        {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
        catch (AtomicMoveNotSupportedException ex)
        {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static boolean isRetryable(IOException ex)
    {
        return ex instanceof AccessDeniedException ||
            ex instanceof FileSystemException;
    }
}
