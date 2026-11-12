package io.github.user4ooksk.stopcard.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ModuleStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cleanTempFilesRemovesOnlyExpiredRegularTempEntries() {
        val dir = folder.newFolder("stop_card")
        val now = 1_000_000L
        val cutoff = now - 60_000L

        val staleReady = File(dir, ".ready.json.034de64e-d99f-445f-b9af-bd6f3c8c11ef.tmp").apply {
            writeText("stale")
            setLastModified(cutoff - 5_000L)
        }
        val staleState = File(dir, ".state.json.tmp").apply {
            writeText("stale")
            setLastModified(cutoff - 1_000L)
        }
        val freshTmp = File(dir, "config.json.999.tmp").apply {
            writeText("in-flight")
            setLastModified(now)
        }
        val durable = File(dir, "state.json").apply {
            writeText("{}")
            setLastModified(cutoff - 100_000L)
        }

        ModuleStore.cleanTempFiles(dir, cutoff)

        assertFalse(staleReady.exists())
        assertFalse(staleState.exists())
        assertTrue(freshTmp.exists())
        assertTrue(durable.exists())
    }

    @Test fun cleanTempFilesNeverFollowsOrRemovesSymbolicLinks() {
        assumeTrue(System.getProperty("os.name").contains("Linux"))
        val dir = folder.newFolder("stop_card_links")
        val target = folder.newFile("target.json").apply {
            writeText("keep")
            setLastModified(1_000L)
        }
        val symlink = dir.toPath().resolve("evil.tmp")
        Files.createSymbolicLink(symlink, target.toPath())

        ModuleStore.cleanTempFiles(dir, cutoffMs = Long.MAX_VALUE)

        assertTrue(Files.exists(symlink, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(target.exists())
    }
}
