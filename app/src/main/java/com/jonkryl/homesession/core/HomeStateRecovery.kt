package com.jonkryl.homesession.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Copies raw bytes before AtomicFile recovery or an explicitly confirmed fresh start. */
class HomeStateRecovery(
    private val stateFile: File,
    private val copyFile: (File, File) -> Unit = ::copyAndSync,
) {
    fun preserve(): File {
        val sources = savedFiles(stateFile).filter { it.exists() }
        if (sources.isEmpty()) throw IOException("No saved home state to preserve")
        if (sources.any { !it.isFile }) throw IOException("Saved home state is not a regular file")
        val parent = stateFile.parentFile ?: throw IOException("Missing home state directory")
        val archive = File(File(parent, DIRECTORY), UUID.randomUUID().toString())
        if (!archive.mkdirs()) throw IOException("Could not create recovery copy")
        for (source in sources) {
            val destination = File(archive, source.name)
            copyFile(source, destination)
            if (source.length() != destination.length() || !digest(source).contentEquals(digest(destination))) {
                throw IOException("Recovery copy verification failed")
            }
        }
        return archive
    }

    /** A copy or verification failure must never invoke the writer. Copies survive write failure. */
    fun restartEmpty(writeEmpty: () -> Unit): File {
        val archive = preserve()
        writeEmpty()
        return archive
    }

    companion object {
        const val DIRECTORY = "home-session-recovery"
        fun savedFiles(stateFile: File): List<File> = listOf(
            stateFile, File(stateFile.path + ".bak"), File(stateFile.path + ".new"),
        )

        fun hasPendingAtomicRecovery(stateFile: File): Boolean =
            savedFiles(stateFile).drop(1).any { it.exists() }

        private fun copyAndSync(source: File, destination: File) {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }

        private fun digest(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }
    }
}
