package com.dzwnk.exporter;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class AtomicFileWriterTest
{
    @Test
    public void retriesShortAccessDeniedFailures()
        throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();
        Path source = Paths.get("source.tmp");
        Path target = Paths.get("target.json");

        AtomicFileWriter.moveWithRetry(
            source,
            target,
            (from, to) ->
            {
                if (attempts.incrementAndGet() < 3)
                {
                    throw new AccessDeniedException(
                        to.toString()
                    );
                }
            },
            new long[]{0L, 0L, 0L}
        );

        Assert.assertEquals(3, attempts.get());
    }

    @Test
    public void nonFileSystemFailureIsNotRetried()
    {
        AtomicInteger attempts = new AtomicInteger();

        try
        {
            AtomicFileWriter.moveWithRetry(
                Paths.get("source.tmp"),
                Paths.get("target.json"),
                (from, to) ->
                {
                    attempts.incrementAndGet();
                    throw new IOException("synthetic");
                },
                new long[]{0L, 0L, 0L}
            );
            Assert.fail("Expected IOException");
        }
        catch (IOException expected)
        {
            Assert.assertEquals(
                "synthetic",
                expected.getMessage()
            );
        }

        Assert.assertEquals(1, attempts.get());
    }
}
