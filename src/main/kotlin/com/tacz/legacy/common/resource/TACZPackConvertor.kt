package com.tacz.legacy.common.resource

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.resource.TACZJson.parseObject
import net.minecraftforge.fml.common.Loader
import net.minecraftforge.fml.common.versioning.VersionParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object TACZPackVersionChecker {
    private val packInfoPattern: Pattern = Pattern.compile("^\\w+/pack\\.json$", Pattern.CASE_INSENSITIVE)
    private val cache: MutableMap<Path, Boolean> = ConcurrentHashMap()

    internal fun match(dir: File): Boolean = cache.computeIfAbsent(dir.toPath()) { checkDirVersion(dir) }

    internal fun noneMatch(zipFile: ZipFile, zipFilePath: Path): Boolean = !cache.computeIfAbsent(zipFilePath) { checkZipVersion(zipFile) }

    internal fun clearCache(): Unit {
        cache.clear()
    }

    private fun checkDirVersion(root: File): Boolean {
        if (!root.isDirectory) {
            return false
        }
        val packInfoFile = root.toPath().resolve("pack.json")
        if (!packInfoFile.toFile().isFile) {
            return true
        }
        return runCatching {
            val info = TACZJson.fromJson(packInfoFile.toFile().readText(StandardCharsets.UTF_8), TACZDependencyInfo::class.java)
            dependenciesMatch(info.dependencies)
        }.getOrElse {
            TACZLegacy.logger.warn("[GunPackCompat] Failed to read legacy pack.json from {}", packInfoFile, it)
            true
        }
    }

    private fun checkZipVersion(zipFile: ZipFile): Boolean {
        val entries = Collections.list(zipFile.entries()).map { it.name }
        entries.forEach { path ->
            if (!packInfoPattern.matcher(path).matches()) {
                return@forEach
            }
            val entry = zipFile.getEntry(path) ?: return@forEach
            val matches = runCatching {
                zipFile.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use {
                    val info = TACZJson.fromJson(it.readText(), TACZDependencyInfo::class.java)
                    dependenciesMatch(info.dependencies)
                }
            }.getOrElse {
                TACZLegacy.logger.warn("[GunPackCompat] Failed to read legacy pack.json from {} in {}", path, zipFile.name, it)
                true
            }
            if (!matches) {
                return false
            }
        }
        return true
    }

    private fun dependenciesMatch(dependencies: Map<String, String>): Boolean {
        if (dependencies.isEmpty()) {
            return true
        }
        return dependencies.entries.all { (modId, versionRange) ->
            val modContainer = runCatching { Loader.instance().indexedModList[modId] }.getOrNull() ?: return@all false
            val processedVersion = modContainer.processedVersion
            runCatching {
                val expected = VersionParser.parseVersionReference("$modId@$versionRange")
                VersionParser.satisfies(expected, processedVersion)
            }.getOrDefault(false)
        }
    }

    private data class TACZDependencyInfo(
        @SerializedName("dependencies")
        val dependencies: Map<String, String> = emptyMap(),
    )
}

internal object TACZPackConvertor {
    internal val LEGACY_PACK_FOLDER: Path = Paths.get("config", TACZLegacy.MOD_ID, "custom")
    private val packInfoPattern: Pattern = Pattern.compile("^(\\w+)/pack\\.json$", Pattern.CASE_INSENSITIVE)

    internal fun fromZipFile(file: File): LegacyPack? {
        ZipFile(file).use { zipFile ->
            Collections.list(zipFile.entries()).map { it.name }.forEach { path ->
                val matcher = packInfoPattern.matcher(path)
                if (!matcher.find()) {
                    return@forEach
                }
                val namespace = matcher.group(1)
                val entry = zipFile.getEntry(path) ?: return@forEach
                return runCatching {
                    zipFile.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use {
                        val info = TACZJson.fromJson(it.readText(), TACZPackInfo::class.java)
                        LegacyPack(file = file, namespace = namespace, info = info)
                    }
                }.getOrNull()
            }
        }
        return null
    }

    internal data class LegacyPack(
        val file: File,
        val namespace: String,
        val info: TACZPackInfo,
    ) {
        private val displayPattern: Pattern = Pattern.compile("^(\\w+)/(\\w+)/display/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val indexPattern: Pattern = Pattern.compile("^(\\w+)/(\\w+)/index/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val dataPattern: Pattern = Pattern.compile("^(\\w+)/(\\w+)/data/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val modelsPattern: Pattern = Pattern.compile("^(\\w+)/models/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val langPattern: Pattern = Pattern.compile("^(\\w+)/lang/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val animationPattern: Pattern = Pattern.compile("^(\\w+)/animations/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val texturePattern: Pattern = Pattern.compile("^(\\w+)/textures/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val soundPattern: Pattern = Pattern.compile("^(\\w+)/sounds/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val playerAnimatorPattern: Pattern = Pattern.compile("^(\\w+)/player_animator/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val tagsPattern: Pattern = Pattern.compile("^(\\w+)/tags/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val recipePattern: Pattern = Pattern.compile("^(\\w+)/recipes/([\\w/.-]+)$", Pattern.CASE_INSENSITIVE)
        private val packInfoPattern: Pattern = Pattern.compile("^(\\w+)/pack\\.json$", Pattern.CASE_INSENSITIVE)
        // B10：zip 条目名里出现盘符前缀（`C:`）即视为绝对路径。
        private val driveLetterPattern: Pattern = Pattern.compile("^[A-Za-z]:.*")

        fun convertTo(targetDirectory: File): File {
            targetDirectory.mkdirs()
            val newName = file.name.removeSuffix(".zip") + "_converted.zip"
            val targetFile = File(targetDirectory, newName)
            if (targetFile.exists()) {
                throw FileAlreadyExistsException(targetFile.absolutePath)
            }
            // B10：先写同目录临时文件，全部条目写成功后再原子改名到目标。
            // 临时文件必须与目标同目录，rename 才可能是原子的（跨文件系统会退化为复制）。
            // 中途异常（坏 recipe JSON、非法条目名等）只会留下临时文件（并在 finally 前删除），
            // 绝不会留下截断的 _converted.zip 被下一次运行当成"已存在"而永久跳过。
            val tempFile = File(targetDirectory, "$newName.tmp-${System.nanoTime()}")
            try {
                ZipFile(file).use { oldPack ->
                    ZipOutputStream(FileOutputStream(tempFile)).use { newZip ->
                        addMeta(newZip)
                        Collections.list(oldPack.entries()).forEach { entry ->
                            if (parseDisplay(newZip, entry, oldPack)) return@forEach
                            if (parseIndex(newZip, entry, oldPack)) return@forEach
                            if (parseData(newZip, entry, oldPack)) return@forEach
                            if (parseModels(newZip, entry, oldPack)) return@forEach
                            if (parseLang(newZip, entry, oldPack)) return@forEach
                            if (parseAnimation(newZip, entry, oldPack)) return@forEach
                            if (parseTexture(newZip, entry, oldPack)) return@forEach
                            if (parseSound(newZip, entry, oldPack)) return@forEach
                            if (parsePlayerAnimator(newZip, entry, oldPack)) return@forEach
                            if (parseTags(newZip, entry, oldPack)) return@forEach
                            if (parseRecipe(newZip, entry, oldPack)) return@forEach
                            if (parsePackInfo(newZip, entry, oldPack)) return@forEach
                        }
                    }
                }
                publish(tempFile, targetFile)
                return targetFile
            } catch (throwable: Throwable) {
                // 失败路径：删除临时文件，绝不把半成品暴露成目标文件；删除失败只记日志，不掩盖原始异常。
                if (!tempFile.delete() && tempFile.exists()) {
                    TACZLegacy.logger.warn("[GunPackConvert] Failed to delete temporary file {}", tempFile.absolutePath)
                }
                throw throwable
            }
        }

        /**
         * B10：把写好的临时文件发布成目标文件。
         *
         * `ATOMIC_MOVE` 下其余选项按 javadoc 会被忽略，但同目录 rename 在 Windows/Linux 上
         * 本身就会覆盖已存在目标，因此额外带上 `REPLACE_EXISTING` 表达意图。
         * 少数文件系统（部分网络盘）不支持原子移动，此时退化为普通移动——仍比直接写目标安全。
         */
        private fun publish(tempFile: File, targetFile: File): Unit {
            val source = tempFile.toPath()
            val target = targetFile.toPath()
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun parsePackInfo(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, packInfoPattern) { namespace, _, path -> "assets/$namespace/gunpack_info.json" }

        private fun parseRecipe(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean {
            val matcher = recipePattern.matcher(entry.name)
            if (!matcher.find()) {
                return false
            }
            val namespace = matcher.group(1)
            val path = matcher.group(2)
            val newPath = "data/$namespace/recipes/$path"
            oldPack.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val objectNode = parseObject(reader.readText())
                objectNode.addProperty("type", "tacz:gun_smith_table_crafting")
                putEntry(newZip, newPath)
                newZip.write(TACZJson.GSON.toJson(objectNode).toByteArray(StandardCharsets.UTF_8))
                newZip.closeEntry()
            }
            return true
        }

        private fun parseTags(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, tagsPattern) { namespace, _, path -> "data/$namespace/tacz_tags/$path" }

        private fun parsePlayerAnimator(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, playerAnimatorPattern) { namespace, _, path -> "assets/$namespace/player_animator/$path" }

        private fun parseSound(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, soundPattern) { namespace, _, path -> "assets/$namespace/tacz_sounds/$path" }

        private fun parseTexture(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, texturePattern) { namespace, _, path -> "assets/$namespace/textures/$path" }

        private fun parseAnimation(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, animationPattern) { namespace, _, path -> "assets/$namespace/animations/$path" }

        private fun parseLang(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, langPattern) { namespace, _, path -> "assets/$namespace/lang/$path" }

        private fun parseModels(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, modelsPattern) { namespace, _, path -> "assets/$namespace/geo_models/$path" }

        private fun parseDisplay(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, displayPattern) { namespace, type, path -> "assets/$namespace/display/$type/$path" }

        private fun parseIndex(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, indexPattern) { namespace, type, path -> "data/$namespace/index/$type/$path" }

        private fun parseData(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile): Boolean =
            remapEntry(newZip, entry, oldPack, dataPattern) { namespace, type, path -> "data/$namespace/data/$type/$path" }

        private fun remapEntry(
            newZip: ZipOutputStream,
            entry: ZipEntry,
            oldPack: ZipFile,
            pattern: Pattern,
            pathFactory: (namespace: String, type: String, path: String) -> String,
        ): Boolean {
            val matcher = pattern.matcher(entry.name)
            if (!matcher.find()) {
                return false
            }
            val namespace = matcher.group(1)
            val type = if (matcher.groupCount() >= 2) matcher.group(2) else ""
            val path = if (matcher.groupCount() >= 3) matcher.group(3) else type
            writeEntry(newZip, entry, oldPack, pathFactory(namespace, type, path))
            return true
        }

        private fun writeEntry(newZip: ZipOutputStream, entry: ZipEntry, oldPack: ZipFile, newPath: String): Unit {
            putEntry(newZip, newPath)
            oldPack.getInputStream(entry).use { input ->
                input.copyTo(newZip)
            }
            newZip.closeEntry()
        }

        /**
         * B10：所有条目都必须经过这里写入。
         *
         * 旧包条目名会经由各 `*Pattern` 的捕获组拼进新路径，而这些正则允许 `.` 与 `/`，
         * 因此 `../` 能穿过匹配进入 `ZipEntry`。产出的 `_converted.zip` 会被下游解压
         * （资源包加载器、人工解压），带 `..` 的条目名就是 zip-slip 载荷。
         */
        private fun putEntry(newZip: ZipOutputStream, newPath: String): Unit {
            requireSafeEntryName(newPath)
            newZip.putNextEntry(ZipEntry(newPath))
        }

        /**
         * B10：条目名安全校验（只校验字符串，不解压、不触碰文件系统）。
         *
         * 规则：非空；不含反斜杠（部分解压器把 `\` 当分隔符）；不以 `/` 开头；
         * 不匹配盘符前缀（`C:`）；按 `/` 分段后不得出现 `..`。
         * 正常条目（`assets/...`、`data/...`、`gunpack.meta.json`）不受影响。
         * 不合法直接抛 [IllegalArgumentException]，由 `handleConvert` 计入 failed。
         */
        private fun requireSafeEntryName(entryName: String) {
            require(entryName.isNotEmpty()) { "Illegal zip entry name: empty" }
            require(!entryName.contains('\\')) { "Illegal zip entry name (backslash): $entryName" }
            require(!entryName.startsWith("/")) { "Illegal zip entry name (absolute path): $entryName" }
            require(!driveLetterPattern.matcher(entryName).matches()) { "Illegal zip entry name (drive prefix): $entryName" }
            require(entryName.split('/').none { it == ".." }) { "Illegal zip entry name (path traversal): $entryName" }
        }

        private fun addMeta(newZip: ZipOutputStream): Unit {
            putEntry(newZip, "gunpack.meta.json")
            val meta = TACZPackMeta(name = namespace)
            newZip.write(TACZJson.GSON.toJson(meta).toByteArray(StandardCharsets.UTF_8))
            newZip.closeEntry()
        }
    }
}
