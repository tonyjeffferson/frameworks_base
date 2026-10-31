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

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ObbInfo
import android.content.res.ObbScanner
import android.database.Cursor
import android.os.Build
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile

/** Expands common split-package archives into one PackageInstaller session. */
internal class PackageArchiveStager(private val context: Context) {
    companion object {
        private const val TAG = "PackageArchiveStager"
        private const val MAX_APK_COUNT = 100
        private const val MAX_OBB_COUNT = 10
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private val ABI_NAMES = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "armeabi")
        private val DENSITIES = mapOf(
            "ldpi" to DisplayMetrics.DENSITY_LOW,
            "mdpi" to DisplayMetrics.DENSITY_MEDIUM,
            "tvdpi" to DisplayMetrics.DENSITY_TV,
            "hdpi" to DisplayMetrics.DENSITY_HIGH,
            "xhdpi" to DisplayMetrics.DENSITY_XHIGH,
            "xxhdpi" to DisplayMetrics.DENSITY_XXHIGH,
            "xxxhdpi" to DisplayMetrics.DENSITY_XXXHIGH,
        )

        fun isArchiveUri(context: Context, uri: android.net.Uri?): Boolean {
            if (uri == null) return false
            val name = if (uri.scheme == "content") {
                var cursor: Cursor? = null
                try {
                    cursor = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )
                    if (cursor?.moveToFirst() == true) cursor.getString(0) else null
                } catch (_: Exception) {
                    null
                } finally {
                    cursor?.close()
                }
            } else uri.lastPathSegment
            val lowerName = name?.lowercase(Locale.ROOT).orEmpty()
            if (lowerName.endsWith(".apk")) return false
            if (listOf(".apks", ".apkm", ".xapk", ".zip").any(lowerName::endsWith)) return true
            val type = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
            return type in setOf(
                "application/zip", "application/x-zip-compressed", "application/vnd.apkm",
                "application/vnd.xapk", "application/vnd.android.apks",
            )
        }
    }

    fun stage(
        input: InputStream,
        session: PackageInstaller.Session,
        isRunning: () -> Boolean,
    ): ArchiveStagingResult {
        val stagingDir = File(context.cacheDir, "package-archive-${System.nanoTime()}")
        if (!stagingDir.mkdirs()) return ArchiveStagingResult(false)
        var stagedObbPayload: StagedObbPayload? = null
        try {
            val archive = extractApks(input, stagingDir, isRunning)
            if (archive.apks.isEmpty() || !isRunning()) return ArchiveStagingResult(false)
            val selected = selectApks(archive.apks, archive.splitIds)
            if (selected.isEmpty()) return ArchiveStagingResult(false)
            selected.forEachIndexed { index, apk ->
                if (!isRunning()) return ArchiveStagingResult(false)
                val outputName = apk.splitName?.let { "split_$it.apk" } ?: "base.apk"
                session.openWrite(outputName, 0, apk.file.length()).use { output ->
                    apk.file.inputStream().use { source ->
                        source.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                    session.fsync(output)
                }
                session.setStagingProgress((index + 1f) / selected.size)
            }
            stagedObbPayload = archive.obbPayload
            archive.apks.forEach { it.file.delete() }
            File(stagingDir, "source.zip").delete()
            return ArchiveStagingResult(true, stagedObbPayload)
        } catch (e: Exception) {
            Log.w(TAG, "Could not expand package archive", e)
            return ArchiveStagingResult(false)
        } finally {
            if (stagedObbPayload == null) stagingDir.deleteRecursively()
        }
    }

    data class ArchiveStagingResult(
        val staged: Boolean,
        val obbPayload: StagedObbPayload? = null,
    )

    private data class ArchivedApk(
        val file: File,
        val entryName: String,
        val splitName: String?,
        val packageName: String,
    )

    private data class ExtractedArchive(
        val apks: List<ArchivedApk>,
        val splitIds: Map<String, String>,
        val obbPayload: StagedObbPayload?,
    )

    private fun extractApks(
        input: InputStream,
        stagingDir: File,
        isRunning: () -> Boolean,
    ): ExtractedArchive {
        val archiveFile = File(stagingDir, "source.zip")
        var archiveBytes = 0L
        input.buffered().use { source ->
            FileOutputStream(archiveFile).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (!isRunning()) return ExtractedArchive(emptyList(), emptyMap(), null)
                    val count = source.read(buffer)
                    if (count < 0) break
                    archiveBytes += count
                    if (archiveBytes > MAX_ARCHIVE_BYTES) error("Archive is too large")
                    output.write(buffer, 0, count)
                }
            }
        }

        val files = mutableListOf<Pair<File, String>>()
        val obbFiles = mutableListOf<StagedObb>()
        var expandedBytes = 0L
        var manifestBytes: ByteArray? = null
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (!isRunning()) return ExtractedArchive(emptyList(), emptyMap(), null)
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.substringAfterLast('/')
                        .equals("manifest.json", ignoreCase = true)) {
                    val manifest = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    zip.getInputStream(entry).use { source ->
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (manifest.size() + count > MAX_MANIFEST_BYTES) {
                                error("XAPK manifest is too large")
                            }
                            manifest.write(buffer, 0, count)
                        }
                    }
                    manifestBytes = manifest.toByteArray()
                } else if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    if (files.size >= MAX_APK_COUNT) error("Archive contains too many APK files")
                    val file = File.createTempFile("split-", ".apk", stagingDir)
                    var entryBytes = 0L
                    FileOutputStream(file).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        zip.getInputStream(entry).use { source ->
                            while (true) {
                                if (!isRunning()) {
                                    return ExtractedArchive(emptyList(), emptyMap(), null)
                                }
                                val count = source.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                expandedBytes += count
                                if (entryBytes > MAX_ENTRY_BYTES || expandedBytes > MAX_ARCHIVE_BYTES) {
                                    error("Archive is too large")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    files += file to entry.name
                } else if (!entry.isDirectory && entry.name.endsWith(".obb", ignoreCase = true)) {
                    if (obbFiles.size >= MAX_OBB_COUNT) {
                        error("Archive contains too many OBB files")
                    }
                    val fileName = entry.name.substringAfterLast('/')
                    if (obbFiles.any { it.fileName.equals(fileName, ignoreCase = true) }) {
                        error("Archive contains duplicate OBB file $fileName")
                    }
                    val file = File.createTempFile("expansion-", ".obb", stagingDir)
                    var entryBytes = 0L
                    FileOutputStream(file).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        zip.getInputStream(entry).use { source ->
                            while (true) {
                                if (!isRunning()) return ExtractedArchive(emptyList(), emptyMap(), null)
                                val count = source.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                expandedBytes += count
                                if (entryBytes > MAX_ENTRY_BYTES || expandedBytes > MAX_ARCHIVE_BYTES) {
                                    error("Archive is too large")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    obbFiles += StagedObb(file, fileName)
                }
            }
        }
        if (files.isEmpty()) return ExtractedArchive(emptyList(), emptyMap(), null)

        val manifest = manifestBytes?.let { JSONObject(String(it, StandardCharsets.UTF_8)) }
        val expectedPackageName = manifest?.optString("package_name")?.takeIf { it.isNotBlank() }
        val splitIds = buildMap {
            val splitApks = manifest?.optJSONArray("split_apks") ?: return@buildMap
            for (index in 0 until splitApks.length()) {
                val split = splitApks.optJSONObject(index) ?: continue
                val fileName = split.optString("file").takeIf { it.isNotBlank() } ?: continue
                val splitId = split.optString("id").takeIf { it.isNotBlank() } ?: continue
                put(fileName, splitId)
            }
        }
        if (expectedPackageName != null && splitIds.isNotEmpty()) {
            val archiveEntries = files.map { it.second }.toSet()
            val missingApks = splitIds.keys - archiveEntries
            if (missingApks.isNotEmpty()) {
                error("XAPK manifest references missing APK files: $missingApks")
            }
        }

        val pm = context.packageManager
        val parsed = files.mapNotNull { (file, name) ->
            val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA)
            if (info == null) {
                file.delete()
                null
            } else {
                if (expectedPackageName != null && info.packageName != expectedPackageName) {
                    error("XAPK manifest package does not match ${name.substringAfterLast('/')}")
                }
                val manifestSplitId = splitIds[name]
                val splitName = when {
                    manifestSplitId.equals("base", ignoreCase = true) -> null
                    manifestSplitId != null -> manifestSplitId
                    else -> info.applicationInfo?.splitNames?.firstOrNull()
                }
                ArchivedApk(file, name, splitName, info.packageName)
            }
        }
        if (parsed.isEmpty()) error("Archive has no valid APK files")
        val packageNames = parsed.map { it.packageName }.toSet()
        if (packageNames.size != 1 || parsed.none { it.splitName == null }) {
            error("Archive must contain one package and a base APK")
        }
        val packageName = parsed.first().packageName
        val stagedObbs = obbFiles.map { obb ->
            val info = ObbScanner.getObbInfo(obb.file.absolutePath)
            if (info.packageName != packageName) {
                error("OBB belongs to ${info.packageName}, expected $packageName")
            }
            val type = if (info.flags and ObbInfo.OBB_OVERLAY != 0) "patch" else "main"
            val expectedFileName = "$type.${info.version}.$packageName.obb"
            if (!obb.fileName.equals(expectedFileName, ignoreCase = true)) {
                error("Invalid OBB filename ${obb.fileName}; expected $expectedFileName")
            }
            obb
        }
        val obbPayload = stagedObbs.takeIf { it.isNotEmpty() }?.let {
            StagedObbPayload(packageName, stagingDir, it)
        }
        return ExtractedArchive(parsed, splitIds, obbPayload)
    }

    private fun selectApks(
        apks: List<ArchivedApk>,
        splitIds: Map<String, String>,
    ): List<ArchivedApk> {
        // Bundletool and several third-party archive formats include a universal APK.
        apks.firstOrNull {
            it.entryName.substringAfterLast('/').equals("universal.apk", true)
        }?.let {
            return listOf(it.copy(splitName = null))
        }

        val apksWithManifestSplits = apks.map { apk ->
            when (val splitId = splitIds[apk.entryName]) {
                null -> apk
                else -> apk.copy(splitName = splitId.takeUnless { it.equals("base", true) })
            }
        }
        val configSplits = apksWithManifestSplits
            .filter { it.splitName?.startsWith("config.") == true }
        val bestDensitySplit = configSplits
            .filter { densityFor(it.splitName!!) != null }
            .minByOrNull {
                kotlin.math.abs(
                    densityFor(it.splitName!!)!! - context.resources.displayMetrics.densityDpi
                )
            }
        val selectedLanguageSplits = selectLanguageSplits(configSplits)

        val supportedAbis = Build.SUPPORTED_ABIS.toSet()
        val selected = apksWithManifestSplits.filter { apk ->
            val split = apk.splitName ?: return@filter true
            if (!split.startsWith("config.")) return@filter true
            val qualifier = split.removePrefix("config.")
            when {
                qualifier in ABI_NAMES -> qualifier in supportedAbis
                densityFor(split) != null -> apk == bestDensitySplit
                localeFor(split) != null -> apk in selectedLanguageSplits
                else -> true
            }
        }
        if (selected.count { it.splitName == null } != 1) error("Archive must have exactly one base APK")
        if (selected.mapNotNull { it.splitName }.distinct().size != selected.count { it.splitName != null }) {
            error("Archive contains duplicate split APKs")
        }
        return selected
    }

    private fun selectLanguageSplits(configSplits: List<ArchivedApk>): Set<ArchivedApk> {
        val languageSplits = configSplits.mapNotNull { apk ->
            localeFor(apk.splitName!!)?.let { apk to it }
        }
        if (languageSplits.isEmpty()) return emptySet()

        val locales = context.resources.configuration.locales
        for (index in 0 until locales.size()) {
            val requested = locales[index]
            val matches = languageSplits.filter { (_, candidate) ->
                candidate.language.equals(requested.language, ignoreCase = true)
            }
            if (matches.isNotEmpty()) {
                val regionalMatch = matches.firstOrNull { (_, candidate) ->
                    candidate.country.equals(requested.country, ignoreCase = true) &&
                        requested.country.isNotEmpty()
                }
                val languageDefault = matches.firstOrNull { (_, candidate) ->
                    candidate.country.isEmpty()
                }
                return setOf((regionalMatch ?: languageDefault ?: matches.first()).first)
            }
        }

        val english = languageSplits.filter { (_, locale) -> locale.language == "en" }
        if (english.isEmpty()) return emptySet()
        return setOf(
            (english.firstOrNull { (_, locale) -> locale.country.isEmpty() } ?: english.first()).first
        )
    }

    private fun densityFor(splitName: String): Int? {
        DENSITIES.entries.firstOrNull { splitName.endsWith(".${it.key}") }?.let {
            return it.value
        }
        val numericDpi = Regex("\\.(\\d+)dpi$").find(splitName)?.groupValues?.get(1)
        return numericDpi?.toIntOrNull()
    }

    private fun localeFor(splitName: String): Locale? {
        val qualifier = splitName.removePrefix("config.")
        if (!qualifier.matches(Regex("[a-z]{2,3}(-r[A-Z]{2})?"))) return null
        return Locale.forLanguageTag(qualifier.replace("-r", "-"))
    }

}
