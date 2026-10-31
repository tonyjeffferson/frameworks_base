/*
 * Copyright (C) 2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model

import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal data class StagedObb(
    val file: File,
    val fileName: String,
)

internal data class StagedObbPayload(
    val packageName: String,
    val stagingDirectory: File,
    val files: List<StagedObb>,
) {
    fun save(sessionId: Int): ObbSaveTransaction {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) {
            throw IOException("Invalid package name for OBB destination")
        }

        val obbRoot = File(Environment.getExternalStorageDirectory(), "Android/obb")
            .canonicalFile
        val destinationDirectory = File(obbRoot, packageName)
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw IOException("Could not create Android/obb/$packageName")
        }
        val canonicalDestination = destinationDirectory.canonicalFile
        if (!canonicalDestination.path.startsWith(obbRoot.path + File.separator)) {
            throw IOException("Invalid OBB destination directory")
        }

        val changes = mutableListOf<ObbSaveTransaction.Change>()
        val pendingFiles = mutableListOf<File>()
        val transactionToken = "$sessionId-${System.nanoTime()}"
        try {
            files.forEachIndexed { index, obb ->
                if (obb.fileName != File(obb.fileName).name ||
                    obb.fileName.contains('\\') || !obb.fileName.endsWith(".obb", true)
                ) {
                    throw IOException("Invalid OBB filename")
                }
                if (!obb.file.isFile) throw IOException("Staged OBB is missing")

                val destination = File(canonicalDestination, obb.fileName)
                val temporary = File(canonicalDestination, ".pi-$transactionToken-$index.pending")
                val backup = File(canonicalDestination, ".pi-$transactionToken-$index.backup")
                if (temporary.exists() || backup.exists()) {
                    throw IOException("An OBB save transaction already exists")
                }
                if (destination.exists() && !destination.isFile) {
                    throw IOException("OBB destination is not a file")
                }
                pendingFiles += temporary

                FileInputStream(obb.file).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }

                val hadPreviousFile = destination.exists()
                if (hadPreviousFile && !destination.renameTo(backup)) {
                    throw IOException("Could not preserve existing OBB file")
                }
                if (!temporary.renameTo(destination)) {
                    if (hadPreviousFile) backup.renameTo(destination)
                    throw IOException("Could not save OBB file ${obb.fileName}")
                }
                pendingFiles.remove(temporary)
                changes += ObbSaveTransaction.Change(destination, backup, hadPreviousFile)
            }
            return ObbSaveTransaction(changes)
        } catch (e: Exception) {
            pendingFiles.forEach { it.delete() }
            ObbSaveTransaction(changes).rollback()
            throw e
        }
    }

    fun cleanup() {
        stagingDirectory.deleteRecursively()
    }
}

internal class ObbSaveTransaction(
    private val changes: List<Change>,
) {
    internal data class Change(
        val destination: File,
        val backup: File,
        val hadPreviousFile: Boolean,
    )

    fun commit() {
        changes.forEach { it.backup.delete() }
    }

    fun rollback() {
        changes.asReversed().forEach { change ->
            change.destination.delete()
            if (change.hadPreviousFile && change.backup.exists()) {
                change.backup.renameTo(change.destination)
            }
        }
    }
}
