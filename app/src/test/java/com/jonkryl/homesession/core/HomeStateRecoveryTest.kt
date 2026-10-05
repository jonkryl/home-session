package com.jonkryl.homesession.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class HomeStateRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val original = byteArrayOf(123, 34, -1, 0, 10)
    private fun base(): File = File(folder.root, "home-session-state-v1.json").apply { writeBytes(original) }

    @Test fun preservesRawBaseBackupAndInterruptedWriteWithoutChangingAnySource() {
        val base = base()
        val backup = File(base.path + ".bak").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pending = File(base.path + ".new").apply { writeBytes(byteArrayOf(4, 5)) }
        val before = listOf(base, backup, pending).associate { it.name to it.readBytes() }
        val archive = HomeStateRecovery(base).preserve()
        for ((name, bytes) in before) {
            assertArrayEquals(bytes, File(archive, name).readBytes())
            assertArrayEquals(bytes, File(folder.root, name).readBytes())
        }
    }

    @Test fun completeVerifiedCopyExistsBeforeReplacementCanRun() {
        val base = base()
        var writes = 0
        HomeStateRecovery(base).restartEmpty {
            val archive = File(folder.root, HomeStateRecovery.DIRECTORY).listFiles()!!.single()
            assertArrayEquals(original, File(archive, base.name).readBytes())
            writes++
            base.writeText(HomeJson.encode(HomeState()))
        }
        assertEquals(1, writes)
        assertEquals(HomeState(), HomeJson.decode(base.readText()))
        assertArrayEquals(original, archives().single().resolve(base.name).readBytes())
    }

    @Test fun failedCopyNeverInvokesWriterOrChangesSourceFiles() {
        val base = base()
        val backup = File(base.path + ".bak").apply { writeBytes(byteArrayOf(7, 8)) }
        var writes = 0
        val recovery = HomeStateRecovery(base) { source, destination ->
            if (source == backup) throw IOException("Simulated full storage")
            source.copyTo(destination)
        }
        assertThrows(IOException::class.java) { recovery.restartEmpty { writes++ } }
        assertEquals(0, writes)
        assertArrayEquals(original, base.readBytes())
        assertArrayEquals(byteArrayOf(7, 8), backup.readBytes())
    }

    @Test fun sameLengthIncorrectCopyIsRejectedBeforeWriterRuns() {
        val base = base()
        var writes = 0
        val recovery = HomeStateRecovery(base) { _, destination -> destination.writeBytes(ByteArray(original.size)) }
        assertThrows(IOException::class.java) { recovery.restartEmpty { writes++ } }
        assertEquals(0, writes)
        assertArrayEquals(original, base.readBytes())
    }

    @Test fun failedWriteLeavesVerifiedOriginalRecoverableAndDoesNotReportSuccess() {
        val base = base()
        assertThrows(IOException::class.java) {
            HomeStateRecovery(base).restartEmpty { throw IOException("Simulated atomic write failure") }
        }
        assertArrayEquals(original, base.readBytes())
        assertArrayEquals(original, archives().single().resolve(base.name).readBytes())
    }

    @Test fun blockedArchiveDirectoryDoesNotInvokeWriter() {
        val base = base()
        File(folder.root, HomeStateRecovery.DIRECTORY).writeText("keep")
        var writes = 0
        assertThrows(IOException::class.java) { HomeStateRecovery(base).restartEmpty { writes++ } }
        assertEquals(0, writes)
        assertArrayEquals(original, base.readBytes())
    }

    @Test fun recoveryCopiesNeverOverwriteEarlierOriginals() {
        val base = base()
        val first = HomeStateRecovery(base).preserve()
        base.writeBytes(byteArrayOf(9, 10))
        val second = HomeStateRecovery(base).preserve()
        assertFalse(first == second)
        assertArrayEquals(original, first.resolve(base.name).readBytes())
        assertArrayEquals(byteArrayOf(9, 10), second.resolve(base.name).readBytes())
    }

    @Test fun pendingAtomicRecoveryIncludesBackupOrNewButNotOrdinaryBase() {
        val base = base()
        assertFalse(HomeStateRecovery.hasPendingAtomicRecovery(base))
        val backup = File(base.path + ".bak").apply { writeText("backup") }
        assertTrue(HomeStateRecovery.hasPendingAtomicRecovery(base))
        assertTrue(backup.delete())
        File(base.path + ".new").writeText("pending")
        assertTrue(HomeStateRecovery.hasPendingAtomicRecovery(base))
    }

    private fun archives(): List<File> = File(folder.root, HomeStateRecovery.DIRECTORY).listFiles()!!.toList()
}
