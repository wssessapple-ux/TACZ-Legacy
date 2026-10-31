package com.tacz.legacy.common.resource

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSerializer
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.modifier.JsonProperty
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.api.modifier.ModifierEvaluator
import com.tacz.legacy.api.modifier.ParameterizedCachePair
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.Loader
import net.minecraftforge.fml.common.ModContainer
import net.minecraftforge.fml.common.versioning.ArtifactVersion
import net.minecraftforge.fml.common.versioning.VersionParser
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile

internal data class TACZPackMeta(
    @SerializedName("namespace")
    val name: String = "",
    @SerializedName("dependencies")
    val dependencies: LinkedHashMap<String, String> = LinkedHashMap(),
)

internal data class TACZPackInfo(
    @SerializedName("version")
    val version: String = "1.0.0",
    @SerializedName("name")
    val name: String = "custom.tacz.error.no_name",
    @SerializedName("desc")
    val description: String = "",
    @SerializedName("license")
    val license: String = "All Rights Reserved",
    @SerializedName("authors")
    val authors: List<String> = emptyList(),
    @SerializedName("date")
    val date: String = "1919-08-10",
    @SerializedName("url")
    val url: String? = null,
)

internal data class TACZGunIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("tooltip")
    val tooltip: String? = null,
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("data")
    val data: ResourceLocation? = null,
    @SerializedName("type")
    val type: String = "",
    @SerializedName("item_type")
    val itemType: String = "modern_kinetic",
    @SerializedName("sort")
    val sort: Int = 0,
)

internal data class TACZAttachmentIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("tooltip")
    val tooltip: String? = null,
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("data")
    val data: ResourceLocation? = null,
    @SerializedName("type")
    val type: String = "",
    @SerializedName("sort")
    val sort: Int = 0,
    @SerializedName("hidden")
    val hidden: Boolean = false,
)

internal data class TACZAmmoIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("stack_size")
    val stackSize: Int = 1,
    @SerializedName("tooltip")
    val tooltip: String? = null,
)

internal data class TACZBlockIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("data")
    val data: ResourceLocation? = null,
    @SerializedName("id")
    val id: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "gun_smith_table"),
    @SerializedName("tooltip")
    val tooltip: String? = null,
    @SerializedName("sort")
    val sort: Int = 0,
)

internal data class TACZRecipeFilterDefinition(
    @SerializedName("whitelist")
    val whitelist: List<String> = emptyList(),
    @SerializedName("blacklist")
    val blacklist: List<String> = emptyList(),
) {
    fun allows(recipeId: ResourceLocation): Boolean {
        val value = recipeId.toString()
        val allowed = whitelist.isEmpty() || whitelist.any { pattern -> matchesRecipe(pattern, value) }
        if (!allowed) {
            return false
        }
        return blacklist.none { pattern -> matchesRecipe(pattern, value) }
    }

    private fun matchesRecipe(pattern: String, recipeId: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        if (trimmed.startsWith("^") || trimmed.endsWith("$")) {
            return runCatching { Pattern.matches(trimmed, recipeId) }.getOrDefault(false)
        }
        return trimmed.equals(recipeId, ignoreCase = true)
    }
}

internal data class TACZWorkbenchTabIconDefinition(
    @SerializedName("item")
    val itemId: ResourceLocation? = null,
    @SerializedName("nbt")
    val nbt: JsonObject? = null,
)

internal data class TACZWorkbenchTabDefinition(
    @SerializedName("id")
    val id: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "misc"),
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("icon")
    val icon: TACZWorkbenchTabIconDefinition? = null,
)

internal data class TACZGunDataDefinition(
    val raw: JsonObject,
    val ammoId: ResourceLocation?,
    val ammoAmount: Int,
    val extendedMagAmmoAmount: IntArray?,
    val roundsPerMinute: Int,
    val weight: Float,
    val aimTime: Float,
    val allowAttachmentTypes: List<String>,
)

internal data class TACZAttachmentDataDefinition(
    val raw: JsonObject,
    val weight: Float,
    val extendedMagLevel: Int,
    val modifiers: Map<String, JsonProperty<*>>,
)

internal data class TACZBlockDataDefinition(
    val raw: JsonObject,
    val filter: ResourceLocation,
    val tabs: List<TACZWorkbenchTabDefinition>,
)

internal data class TACZDisplayDefinition(
    val id: ResourceLocation,
    val raw: JsonObject,
)

internal data class TACZRecipeDefinition(
    val id: ResourceLocation,
    val raw: JsonObject,
)

internal data class TACZRecoilModifierValue(
    val pitch: Modifier = Modifier(),
    val yaw: Modifier = Modifier(),
)

internal data class TACZIgniteValue(
    @SerializedName("entity")
    val igniteEntity: Boolean = false,
    @SerializedName("block")
    val igniteBlock: Boolean = false,
)

internal data class TACZLoadedPack(
    val meta: TACZPackMeta,
    val sourceFile: File,
    val infoByNamespace: Map<String, TACZPackInfo>,
)

internal data class TACZLoadedGun(
    val id: ResourceLocation,
    val index: TACZGunIndexDefinition,
    val data: TACZGunDataDefinition,
)

internal data class TACZLoadedAttachment(
    val id: ResourceLocation,
    val index: TACZAttachmentIndexDefinition,
    val data: TACZAttachmentDataDefinition,
)

internal data class TACZLoadedBlock(
    val id: ResourceLocation,
    val index: TACZBlockIndexDefinition,
    val data: TACZBlockDataDefinition,
)

internal data class TACZRuntimeSnapshot(
    val packs: Map<String, TACZLoadedPack>,
    val packInfos: Map<String, TACZPackInfo>,
    val guns: Map<ResourceLocation, TACZLoadedGun>,
    val attachments: Map<ResourceLocation, TACZLoadedAttachment>,
    val ammos: Map<ResourceLocation, TACZAmmoIndexDefinition>,
    val blocks: Map<ResourceLocation, TACZLoadedBlock>,
    val recipes: Map<ResourceLocation, TACZRecipeDefinition>,
    val recipeFilters: Map<ResourceLocation, TACZRecipeFilterDefinition>,
    val attachmentTags: Map<ResourceLocation, Set<String>>,
    val allowAttachmentTags: Map<ResourceLocation, Set<String>>,
    val gunDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val ammoDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val attachmentDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val blockDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val translations: Map<String, Map<String, String>>,
    val dataScripts: Map<ResourceLocation, String>,
    val issues: List<String>,
) {
    private val gunIdsByItemType: Map<String, List<ResourceLocation>> by lazy {
        guns.values
            .sortedWith(compareBy<TACZLoadedGun> { it.index.sort.coerceIn(0, 65536) }.thenBy { it.id.toString() })
            .groupBy { it.index.itemType }
            .mapValues { (_, values) -> values.map(TACZLoadedGun::id) }
    }

    fun resolveDefaultGunId(itemType: String): ResourceLocation? = gunIdsByItemType[itemType]?.firstOrNull()

    fun gunItemTypes(): Set<String> = gunIdsByItemType.keys

    companion object {
        val EMPTY: TACZRuntimeSnapshot = TACZRuntimeSnapshot(
            packs = emptyMap(),
            packInfos = emptyMap(),
            guns = emptyMap(),
            attachments = emptyMap(),
            ammos = emptyMap(),
            blocks = emptyMap(),
            recipes = emptyMap(),
            recipeFilters = emptyMap(),
            attachmentTags = emptyMap(),
            allowAttachmentTags = emptyMap(),
            gunDisplays = emptyMap(),
            ammoDisplays = emptyMap(),
            attachmentDisplays = emptyMap(),
            blockDisplays = emptyMap(),
            translations = emptyMap(),
            dataScripts = emptyMap(),
            issues = emptyList(),
        )
    }
}

internal object TACZGunPackRuntimeRegistry {
    @Volatile
    private var snapshot: TACZRuntimeSnapshot = TACZRuntimeSnapshot.EMPTY

    internal fun reload(gameDirectory: File): TACZRuntimeSnapshot {
        val packsRoot = File(gameDirectory, TACZLegacy.MOD_ID).apply { mkdirs() }
        TACZDataScriptManager.reload()
        val previousSnapshot = snapshot
        val loaded = try {
            TACZGunPackScanner.scan(packsRoot)
        } catch (throwable: Throwable) {
            val issue = "Gun pack reload failed; keeping previous runtime snapshot (${throwable.message ?: throwable.javaClass.simpleName})."
            TACZLegacy.logger.error("[GunPackRuntime] {}", issue, throwable)
            val fallbackSnapshot = previousSnapshot.copy(issues = previousSnapshot.issues + issue)
            snapshot = fallbackSnapshot
            logSnapshotSummary(fallbackSnapshot)
            return fallbackSnapshot
        }
        snapshot = loaded
        logSnapshotSummary(loaded)
        return loaded
    }

    private fun logSnapshotSummary(loaded: TACZRuntimeSnapshot): Unit {
        TACZLegacy.logger.info(
            "[GunPackRuntime] Loaded {} pack(s), {} gun(s), {} attachment(s), {} ammo index(es), {} block index(es).",
            loaded.packs.size,
            loaded.guns.size,
            loaded.attachments.size,
            loaded.ammos.size,
            loaded.blocks.size,
        )
        if (loaded.issues.isNotEmpty()) {
            loaded.issues.forEach { issue -> TACZLegacy.logger.warn("[GunPackRuntime] {}", issue) }
        }
        // modifier 接入守门：注册的 modifier 必须有审计条目，未生效项在此如实可见
        TACZModifierAudit.logAudit()
    }

    internal fun getSnapshot(): TACZRuntimeSnapshot = snapshot

    @JvmStatic
    fun currentSnapshotForJava(): TACZRuntimeSnapshot = snapshot

    internal fun clearForTests(): Unit {
        snapshot = TACZRuntimeSnapshot.EMPTY
    }
}

internal object TACZGunPackScanner {
    private val GUNPACK_INFO_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/gunpack_info\\.json$", Pattern.CASE_INSENSITIVE)
    private val GUN_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/guns/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val GUN_DATA_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/data/guns/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/attachments/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_DATA_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/data/attachments/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val AMMO_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/ammo/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val BLOCK_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/blocks/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val BLOCK_DATA_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/data/blocks/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val RECIPE_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/recipes/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val RECIPE_FILTER_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/recipe_filters/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_TAG_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/tacz_tags/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val GUN_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/guns/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val AMMO_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/ammo/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/attachments/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val BLOCK_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/blocks/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val LANG_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/lang/([\\w-]+)\\.(json|lang)$", Pattern.CASE_INSENSITIVE)
    private val DATA_SCRIPT_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/scripts/([\\w/.-]+)\\.lua$", Pattern.CASE_INSENSITIVE)

    internal fun scan(packsRoot: File): TACZRuntimeSnapshot {
        if (!packsRoot.exists()) {
            packsRoot.mkdirs()
        }
        val rawPacks = LinkedHashMap<String, TACZLoadedPack>()
        val rawPackInfos = LinkedHashMap<String, TACZPackInfo>()
        val rawGunIndices = LinkedHashMap<ResourceLocation, TACZGunIndexDefinition>()
        val rawGunData = LinkedHashMap<ResourceLocation, TACZGunDataDefinition>()
        val rawAttachmentIndices = LinkedHashMap<ResourceLocation, TACZAttachmentIndexDefinition>()
        val rawAttachmentData = LinkedHashMap<ResourceLocation, TACZAttachmentDataDefinition>()
        val rawAmmoIndices = LinkedHashMap<ResourceLocation, TACZAmmoIndexDefinition>()
        val rawBlockIndices = LinkedHashMap<ResourceLocation, TACZBlockIndexDefinition>()
        val rawBlockData = LinkedHashMap<ResourceLocation, TACZBlockDataDefinition>()
        val rawRecipes = LinkedHashMap<ResourceLocation, TACZRecipeDefinition>()
        val rawRecipeFilters = LinkedHashMap<ResourceLocation, TACZRecipeFilterDefinition>()
        val rawAttachmentTags = LinkedHashMap<ResourceLocation, LinkedHashSet<String>>()
        val rawAllowAttachmentTags = LinkedHashMap<ResourceLocation, LinkedHashSet<String>>()
        val rawGunDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawAmmoDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawAttachmentDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawBlockDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawTranslations = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val rawDataScripts = LinkedHashMap<ResourceLocation, String>()
        val issues = mutableListOf<String>()

        val entries = packsRoot.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        entries.forEach { candidate ->
            scanCandidate(
                candidate = candidate,
                rawPacks = rawPacks,
                rawPackInfos = rawPackInfos,
                rawGunIndices = rawGunIndices,
                rawGunData = rawGunData,
                rawAttachmentIndices = rawAttachmentIndices,
                rawAttachmentData = rawAttachmentData,
                rawAmmoIndices = rawAmmoIndices,
                rawBlockIndices = rawBlockIndices,
                rawBlockData = rawBlockData,
                rawRecipes = rawRecipes,
                rawRecipeFilters = rawRecipeFilters,
                rawAttachmentTags = rawAttachmentTags,
                rawAllowAttachmentTags = rawAllowAttachmentTags,
                rawGunDisplays = rawGunDisplays,
                rawAmmoDisplays = rawAmmoDisplays,
                rawAttachmentDisplays = rawAttachmentDisplays,
                rawBlockDisplays = rawBlockDisplays,
                rawTranslations = rawTranslations,
                rawDataScripts = rawDataScripts,
                issues = issues,
            )
        }

        val guns = LinkedHashMap<ResourceLocation, TACZLoadedGun>()
        rawGunIndices.entries
            .sortedWith(compareBy<Map.Entry<ResourceLocation, TACZGunIndexDefinition>> { it.value.sort.coerceIn(0, 65536) }.thenBy { it.key.toString() })
            .forEach { (id, index) ->
                val dataId = index.data
                val data = dataId?.let(rawGunData::get)
                if (data == null) {
                    issues += "Gun index $id references missing data ${dataId ?: "<null>"}."
                    return@forEach
                }
                if (index.type.isBlank()) {
                    issues += "Gun index $id is missing type field."
                    return@forEach
                }
                guns[id] = TACZLoadedGun(id = id, index = index.copy(sort = index.sort.coerceIn(0, 65536)), data = data)
            }

        val attachments = LinkedHashMap<ResourceLocation, TACZLoadedAttachment>()
        rawAttachmentIndices.entries
            .sortedWith(compareBy<Map.Entry<ResourceLocation, TACZAttachmentIndexDefinition>> { it.value.sort.coerceIn(0, 65536) }.thenBy { it.key.toString() })
            .forEach { (id, index) ->
                val dataId = index.data
                val data = dataId?.let(rawAttachmentData::get)
                if (data == null) {
                    issues += "Attachment index $id references missing data ${dataId ?: "<null>"}."
                    return@forEach
                }
                if (index.type.isBlank()) {
                    issues += "Attachment index $id is missing type field."
                    return@forEach
                }
                attachments[id] = TACZLoadedAttachment(id = id, index = index.copy(sort = index.sort.coerceIn(0, 65536)), data = data)
            }

        val blocks = LinkedHashMap<ResourceLocation, TACZLoadedBlock>()
        rawBlockIndices.entries
            .sortedWith(compareBy<Map.Entry<ResourceLocation, TACZBlockIndexDefinition>> { it.value.sort.coerceIn(0, 65536) }.thenBy { it.key.toString() })
            .forEach { (id, index) ->
                val dataId = index.data
                val data = dataId?.let(rawBlockData::get)
                if (data == null) {
                    issues += "Block index $id references missing data ${dataId ?: "<null>"}."
                    return@forEach
                }
                blocks[id] = TACZLoadedBlock(id = id, index = index.copy(sort = index.sort.coerceIn(0, 65536)), data = data)
            }

        return TACZRuntimeSnapshot(
            packs = rawPacks.toMap(),
            packInfos = rawPackInfos.toMap(),
            guns = guns.toMap(),
            attachments = attachments.toMap(),
            ammos = rawAmmoIndices.toMap(),
            blocks = blocks.toMap(),
            recipes = rawRecipes.toMap(),
            recipeFilters = rawRecipeFilters.toMap(),
            attachmentTags = rawAttachmentTags.mapValues { (_, values) -> values.toSet() },
            allowAttachmentTags = rawAllowAttachmentTags.mapValues { (_, values) -> values.toSet() },
            gunDisplays = rawGunDisplays.toMap(),
            ammoDisplays = rawAmmoDisplays.toMap(),
            attachmentDisplays = rawAttachmentDisplays.toMap(),
            blockDisplays = rawBlockDisplays.toMap(),
            dataScripts = rawDataScripts.toMap(),
            translations = rawTranslations.mapValues { (_, values) -> values.toMap() },
            issues = issues.toList(),
        )
    }

    private fun scanCandidate(
        candidate: File,
        rawPacks: MutableMap<String, TACZLoadedPack>,
        rawPackInfos: MutableMap<String, TACZPackInfo>,
        rawGunIndices: MutableMap<ResourceLocation, TACZGunIndexDefinition>,
        rawGunData: MutableMap<ResourceLocation, TACZGunDataDefinition>,
        rawAttachmentIndices: MutableMap<ResourceLocation, TACZAttachmentIndexDefinition>,
        rawAttachmentData: MutableMap<ResourceLocation, TACZAttachmentDataDefinition>,
        rawAmmoIndices: MutableMap<ResourceLocation, TACZAmmoIndexDefinition>,
        rawBlockIndices: MutableMap<ResourceLocation, TACZBlockIndexDefinition>,
        rawBlockData: MutableMap<ResourceLocation, TACZBlockDataDefinition>,
        rawRecipes: MutableMap<ResourceLocation, TACZRecipeDefinition>,
        rawRecipeFilters: MutableMap<ResourceLocation, TACZRecipeFilterDefinition>,
        rawAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawAllowAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawGunDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAmmoDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAttachmentDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawBlockDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawTranslations: MutableMap<String, LinkedHashMap<String, String>>,
        rawDataScripts: MutableMap<ResourceLocation, String>,
        issues: MutableList<String>,
    ): Unit {
        if (!candidate.isDirectory && !(candidate.isFile && candidate.name.lowercase(Locale.ROOT).endsWith(".zip"))) {
            return
        }
        runCatching {
            when {
                candidate.isDirectory -> TACZDirectoryPackSource(candidate).use { source ->
                    loadPack(
                        source = source,
                        candidate = candidate,
                        rawPacks = rawPacks,
                        rawPackInfos = rawPackInfos,
                        rawGunIndices = rawGunIndices,
                        rawGunData = rawGunData,
                        rawAttachmentIndices = rawAttachmentIndices,
                        rawAttachmentData = rawAttachmentData,
                        rawAmmoIndices = rawAmmoIndices,
                        rawBlockIndices = rawBlockIndices,
                        rawBlockData = rawBlockData,
                        rawRecipes = rawRecipes,
                        rawRecipeFilters = rawRecipeFilters,
                        rawAttachmentTags = rawAttachmentTags,
                        rawAllowAttachmentTags = rawAllowAttachmentTags,
                        rawGunDisplays = rawGunDisplays,
                        rawAmmoDisplays = rawAmmoDisplays,
                        rawAttachmentDisplays = rawAttachmentDisplays,
                        rawBlockDisplays = rawBlockDisplays,
                        rawTranslations = rawTranslations,
                        rawDataScripts = rawDataScripts,
                        issues = issues,
                    )
                }
                else -> ZipFile(candidate).use { zipFile ->
                    TACZZipPackSource(candidate, zipFile).use { source ->
                        loadPack(
                            source = source,
                            candidate = candidate,
                            rawPacks = rawPacks,
                            rawPackInfos = rawPackInfos,
                            rawGunIndices = rawGunIndices,
                            rawGunData = rawGunData,
                            rawAttachmentIndices = rawAttachmentIndices,
                            rawAttachmentData = rawAttachmentData,
                            rawAmmoIndices = rawAmmoIndices,
                            rawBlockIndices = rawBlockIndices,
                            rawBlockData = rawBlockData,
                            rawRecipes = rawRecipes,
                            rawRecipeFilters = rawRecipeFilters,
                            rawAttachmentTags = rawAttachmentTags,
                            rawAllowAttachmentTags = rawAllowAttachmentTags,
                            rawGunDisplays = rawGunDisplays,
                            rawAmmoDisplays = rawAmmoDisplays,
                            rawAttachmentDisplays = rawAttachmentDisplays,
                            rawBlockDisplays = rawBlockDisplays,
                            rawTranslations = rawTranslations,
                            rawDataScripts = rawDataScripts,
                            issues = issues,
                        )
                    }
                }
            }
        }.onFailure { throwable ->
            TACZLegacy.logger.warn("[GunPackRuntime] Skipping pack {} due to load failure.", candidate.name, throwable)
            issues += "Pack ${candidate.name} skipped because it could not be read safely (${throwable.message ?: throwable.javaClass.simpleName})."
        }
    }

    private fun loadPack(
        source: TACZPackSource,
        candidate: File,
        rawPacks: MutableMap<String, TACZLoadedPack>,
        rawPackInfos: MutableMap<String, TACZPackInfo>,
        rawGunIndices: MutableMap<ResourceLocation, TACZGunIndexDefinition>,
        rawGunData: MutableMap<ResourceLocation, TACZGunDataDefinition>,
        rawAttachmentIndices: MutableMap<ResourceLocation, TACZAttachmentIndexDefinition>,
        rawAttachmentData: MutableMap<ResourceLocation, TACZAttachmentDataDefinition>,
        rawAmmoIndices: MutableMap<ResourceLocation, TACZAmmoIndexDefinition>,
        rawBlockIndices: MutableMap<ResourceLocation, TACZBlockIndexDefinition>,
        rawBlockData: MutableMap<ResourceLocation, TACZBlockDataDefinition>,
        rawRecipes: MutableMap<ResourceLocation, TACZRecipeDefinition>,
        rawRecipeFilters: MutableMap<ResourceLocation, TACZRecipeFilterDefinition>,
        rawAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawAllowAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawGunDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAmmoDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAttachmentDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawBlockDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawTranslations: MutableMap<String, LinkedHashMap<String, String>>,
        rawDataScripts: MutableMap<ResourceLocation, String>,
        issues: MutableList<String>,
    ): Unit {
        val packMetaText = source.readText("gunpack.meta.json") ?: return
        val meta = runCatching { TACZJson.fromJson(packMetaText, TACZPackMeta::class.java) }.getOrElse { throwable ->
            issues += "Failed to read gunpack meta from ${candidate.name}: ${throwable.message}"
            return
        }
        if (meta.name.isBlank()) {
            issues += "Pack ${candidate.name} has empty namespace in gunpack.meta.json."
            return
        }
        if (!TACZPackDependencyChecker.allMatch(meta.dependencies)) {
            issues += "Pack ${candidate.name} skipped because dependency version check failed."
            return
        }

        val packInfoByNamespace = LinkedHashMap<String, TACZPackInfo>()
        source.listEntries().forEach { entryName ->
            try {
                when {
                    entryName == "gunpack.meta.json" -> Unit
                    GUNPACK_INFO_PATTERN.matcher(entryName).matches() -> {
                        val matcher = GUNPACK_INFO_PATTERN.matcher(entryName)
                        matcher.find()
                        val namespace = matcher.group(1)
                        val info = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZPackInfo::class.java)
                        packInfoByNamespace[namespace] = info
                        rawPackInfos[namespace] = info
                    }
                    GUN_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, GUN_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZGunIndexDefinition::class.java)
                        rawGunIndices[id] = index
                    }
                    GUN_DATA_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, GUN_DATA_PATTERN)
                        val jsonObject = TACZJson.parseObject(requireNotNull(source.readText(entryName)))
                        rawGunData[id] = parseGunData(jsonObject)
                    }
                    ATTACHMENT_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZAttachmentIndexDefinition::class.java)
                        rawAttachmentIndices[id] = index
                    }
                    ATTACHMENT_DATA_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_DATA_PATTERN)
                        val rawText = requireNotNull(source.readText(entryName))
                        val jsonObject = TACZJson.parseObject(rawText)
                        rawAttachmentData[id] = TACZAttachmentDataDefinition(
                            raw = jsonObject,
                            weight = jsonObject.floatValue("weight"),
                            extendedMagLevel = jsonObject.intValue("extended_mag_level"),
                            modifiers = TACZAttachmentModifierRegistry.readModifiers(rawText, jsonObject),
                        )
                    }
                    AMMO_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, AMMO_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZAmmoIndexDefinition::class.java)
                        rawAmmoIndices[id] = index.copy(stackSize = index.stackSize.coerceAtLeast(1))
                    }
                    BLOCK_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, BLOCK_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZBlockIndexDefinition::class.java)
                        rawBlockIndices[id] = index
                    }
                    BLOCK_DATA_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, BLOCK_DATA_PATTERN)
                        rawBlockData[id] = parseBlockData(TACZJson.parseObject(requireNotNull(source.readText(entryName))))
                    }
                    RECIPE_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, RECIPE_PATTERN)
                        rawRecipes[id] = TACZRecipeDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName))))
                    }
                    RECIPE_FILTER_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, RECIPE_FILTER_PATTERN)
                        rawRecipeFilters[id] = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZRecipeFilterDefinition::class.java)
                    }
                    ATTACHMENT_TAG_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_TAG_PATTERN)
                        val values = parseTagEntries(requireNotNull(source.readText(entryName)))
                        val normalizedPath = id.path.removePrefix(ATTACHMENT_TAG_ROOT_PREFIX)
                        if (normalizedPath.startsWith(ALLOW_ATTACHMENT_PREFIX) && normalizedPath.length > ALLOW_ATTACHMENT_PREFIX.length) {
                            val gunId = ResourceLocation(id.namespace, normalizedPath.substring(ALLOW_ATTACHMENT_PREFIX.length))
                            mergeTagEntries(rawAllowAttachmentTags, gunId, values)
                        } else {
                            mergeTagEntries(rawAttachmentTags, ResourceLocation(id.namespace, normalizedPath), values)
                        }
                    }
                    GUN_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, GUN_DISPLAY_PATTERN)
                        rawGunDisplays[id] = TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName))))
                    }
                    AMMO_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, AMMO_DISPLAY_PATTERN)
                        rawAmmoDisplays[id] = TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName))))
                    }
                    ATTACHMENT_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_DISPLAY_PATTERN)
                        rawAttachmentDisplays[id] = TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName))))
                    }
                    BLOCK_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, BLOCK_DISPLAY_PATTERN)
                        rawBlockDisplays[id] = TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName))))
                    }
                    LANG_PATTERN.matcher(entryName).matches() -> {
                        val matcher = LANG_PATTERN.matcher(entryName)
                        matcher.find()
                        val locale = normalizeLocaleName(matcher.group(2))
                        val extension = matcher.group(3)
                        val translations = parseTranslations(requireNotNull(source.readText(entryName)), extension)
                        if (translations.isNotEmpty()) {
                            rawTranslations.computeIfAbsent(locale) { LinkedHashMap() }.putAll(translations)
                        }
                    }
                    DATA_SCRIPT_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, DATA_SCRIPT_PATTERN)
                        val scriptSource = source.readText(entryName)
                        if (scriptSource != null) {
                            rawDataScripts[id] = scriptSource
                        }
                    }
                }
            } catch (throwable: Throwable) {
                issues += "Failed to parse ${candidate.name}:$entryName (${throwable.message})"
            }
        }

        rawPacks[candidate.name] = TACZLoadedPack(meta = meta, sourceFile = candidate, infoByNamespace = packInfoByNamespace)
    }

    private fun parseGunData(jsonObject: JsonObject): TACZGunDataDefinition = TACZGunDataDefinition(
        raw = jsonObject,
        ammoId = jsonObject.resourceLocation("ammo"),
        ammoAmount = jsonObject.intValue("ammo_amount"),
        extendedMagAmmoAmount = jsonObject.intArray("extended_mag_ammo_amount"),
        roundsPerMinute = jsonObject.intValue("rpm"),
        weight = jsonObject.floatValue("weight"),
        aimTime = jsonObject.floatValue("aim_time"),
        allowAttachmentTypes = jsonObject.stringArray("allow_attachment_types"),
    )

    private fun parseBlockData(jsonObject: JsonObject): TACZBlockDataDefinition {
        val tabs = jsonObject.getAsJsonArray("tabs")
            ?.mapNotNull { element -> runCatching { TACZJson.GSON.fromJson(element, TACZWorkbenchTabDefinition::class.java) }.getOrNull() }
            ?: emptyList()
        return TACZBlockDataDefinition(
            raw = jsonObject,
            filter = jsonObject.resourceLocation("filter") ?: ResourceLocation(TACZLegacy.MOD_ID, "default"),
            tabs = tabs,
        )
    }

    private fun parseTagEntries(json: String): List<String> {
        val element = TACZJson.parseElement(json)
        if (!element.isJsonArray) {
            return emptyList()
        }
        return element.asJsonArray.mapNotNull { entry ->
            runCatching { entry.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank)
        }
    }

    private fun parseTranslations(json: String, extension: String): Map<String, String> {
        return if (extension.equals("json", ignoreCase = true)) {
            val objectNode = TACZJson.parseObject(json)
            val result = LinkedHashMap<String, String>()
            objectNode.entrySet().forEach { entry ->
                runCatching { entry.value.asString }.getOrNull()?.let { translated ->
                    result[entry.key] = translated
                }
            }
            result
        } else {
            parseLangFile(json)
        }
    }

    private fun parseLangFile(content: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            val separator = trimmed.indexOf('=')
            if (separator <= 0) {
                return@forEach
            }
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun mergeTagEntries(target: MutableMap<ResourceLocation, LinkedHashSet<String>>, id: ResourceLocation, values: List<String>): Unit {
        if (values.isEmpty()) {
            return
        }
        target.computeIfAbsent(id) { linkedSetOf() }.addAll(values)
    }

    private fun resourceIdFrom(entryName: String, pattern: Pattern): ResourceLocation {
        val matcher = pattern.matcher(entryName)
        require(matcher.find()) { "Path did not match expected pattern: $entryName" }
        return ResourceLocation(matcher.group(1), matcher.group(2))
    }

    private fun normalizeLocaleName(value: String): String = value.lowercase(Locale.ROOT).replace('-', '_')
}

internal object TACZAttachmentModifierRegistry {
    private val readers: LinkedHashMap<String, TACZModifierReader<*>> = linkedMapOf(
        "ads" to TACZNumericModifierReader(id = "ads", optionalField = "ads_addend"),
        "ammo_speed" to TACZNumericModifierReader(id = "ammo_speed"),
        "armor_ignore" to TACZNumericModifierReader(id = "armor_ignore"),
        "damage" to TACZNumericModifierReader(id = "damage"),
        "effective_range" to TACZNumericModifierReader(id = "effective_range"),
        "explosion" to TACZExplosionModifierReader,
        "head_shot" to TACZNumericModifierReader(id = "head_shot"),
        "ignite" to TACZIgniteModifierReader,
        "inaccuracy" to TACZInaccuracyModifierReader,
        "knockback" to TACZNumericModifierReader(id = "knockback"),
        "pierce" to TACZNumericModifierReader(id = "pierce"),
        "recoil" to TACZRecoilModifierReader,
        "rpm" to TACZNumericModifierReader(id = "rpm"),
        "silence" to TACZRawJsonModifierReader(id = "silence"),
        "weight_modifier" to TACZNumericModifierReader(id = "weight_modifier", optionalField = "weight"),
        "movement_speed" to TACZRawJsonModifierReader(id = "movement_speed"),
    )

    internal fun readModifiers(rawJson: String, jsonObject: JsonObject): Map<String, JsonProperty<*>> {
        val result = LinkedHashMap<String, JsonProperty<*>>()
        readers.forEach { (id, reader) ->
            val property = reader.read(rawJson, jsonObject) ?: return@forEach
            property.initComponents()
            result[id] = property
        }
        return result
    }

    internal fun evalNumeric(modifiers: List<Modifier>, defaultValue: Double): Double = ModifierEvaluator.eval(modifiers, defaultValue)

    internal fun evalRecoil(
        modifiers: List<TACZRecoilModifierValue>,
        defaultPitch: Float,
        defaultYaw: Float,
    ): ParameterizedCachePair<Float, Float> {
        val pitch = modifiers.map(TACZRecoilModifierValue::pitch)
        val yaw = modifiers.map(TACZRecoilModifierValue::yaw)
        return ParameterizedCachePair.of(pitch, yaw, defaultPitch, defaultYaw)
    }

    internal fun evalIgnite(modifiers: List<TACZIgniteValue>, defaultValue: TACZIgniteValue): TACZIgniteValue {
        val igniteEntity = ModifierEvaluator.eval(listOf(defaultValue.igniteEntity) + modifiers.map(TACZIgniteValue::igniteEntity), false)
        val igniteBlock = ModifierEvaluator.eval(listOf(defaultValue.igniteBlock) + modifiers.map(TACZIgniteValue::igniteBlock), false)
        return TACZIgniteValue(igniteEntity = igniteEntity, igniteBlock = igniteBlock)
    }

    internal fun evalInaccuracy(
        modifiers: List<Map<String, Modifier>>,
        defaults: Map<String, Float>,
    ): Map<String, Float> {
        val result = LinkedHashMap<String, Float>()
        defaults.forEach { (key, defaultValue) ->
            val values = modifiers.mapNotNull { modifierMap -> modifierMap[key] ?: modifierMap["default"] }
            result[key] = ModifierEvaluator.eval(values, defaultValue.toDouble()).toFloat()
        }
        return result
    }

    internal fun registeredModifierIds(): Set<String> = readers.keys.toSet()
}

/**
 * 配件 modifier 接入守门审计。
 *
 * 仿 Next 分支"注册了就一定有人消费"的守门思路：
 * - 每个注册的 modifier key 必须在 [audit] 表中登记其消费状态，否则视为可能空转；
 * - 启动/重载时把 APPLIED / GUI_ONLY / UNCONSUMED 三档如实打到日志，
 *   让"注册了没人读"从人肉 grep 变成启动可见的告警，杜绝空转层悄悄蔓延。
 */
internal object TACZModifierAudit {
    internal enum class ConsumerStatus { APPLIED, GUI_ONLY, UNCONSUMED }

    internal data class AuditEntry(
        val status: ConsumerStatus,
        val consumer: String,
        val note: String,
    )

    private val audit: Map<String, AuditEntry> = linkedMapOf(
        "ads" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "改装台预览瞄准时间；战斗 aimTime 未应用配件值"),
        "ammo_speed" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "预览弹速；子弹 speed 用枪械基础值"),
        "armor_ignore" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "预览穿甲；子弹 armorIgnore 用基础 extraDamage"),
        "damage" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "预览伤害；子弹 damage 用基础值"),
        "effective_range" to AuditEntry(ConsumerStatus.UNCONSUMED, "-", "注册读取但无人消费"),
        "explosion" to AuditEntry(ConsumerStatus.UNCONSUMED, "-", "注册读取但无人消费"),
        "head_shot" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "预览爆头倍率；子弹 headShotMultiplier 用基础 extraDamage"),
        "ignite" to AuditEntry(ConsumerStatus.APPLIED, "LivingEntityShoot.performSingleFire", "配件点燃覆盖子弹 igniteEntity/igniteBlock"),
        "inaccuracy" to AuditEntry(ConsumerStatus.APPLIED, "TACZGunPropertyResolver.resolveInaccuracyProfile", "战斗精度"),
        "knockback" to AuditEntry(ConsumerStatus.UNCONSUMED, "-", "注册读取但无人消费"),
        "pierce" to AuditEntry(ConsumerStatus.UNCONSUMED, "-", "注册读取但无人消费"),
        "movement_speed" to AuditEntry(ConsumerStatus.UNCONSUMED, "-", "注册读取但无人消费"),
        "recoil" to AuditEntry(ConsumerStatus.APPLIED, "TACZGunPropertyResolver.resolveCameraRecoil", "后坐力"),
        "rpm" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "预览射速；射速用基础 rpm"),
        "silence" to AuditEntry(ConsumerStatus.APPLIED, "TACZGunSoundRouting.resolveNearbyFireSoundProfile", "开火声音距离/消音"),
        "weight_modifier" to AuditEntry(ConsumerStatus.GUI_ONLY, "GunRefitScreen", "预览重量；移动速度用基础值"),
    )

    internal fun logAudit() {
        val issues = mutableListOf<String>()
        val unconsumed = mutableListOf<String>()
        val guiOnly = mutableListOf<String>()
        TACZAttachmentModifierRegistry.registeredModifierIds().forEach { key ->
            val entry = audit[key]
            if (entry == null) {
                issues += "Modifier '$key' is registered but has NO audit entry (possibly unconsumed)."
            } else {
                when (entry.status) {
                    ConsumerStatus.UNCONSUMED -> unconsumed += key
                    ConsumerStatus.GUI_ONLY -> guiOnly += key
                    ConsumerStatus.APPLIED -> Unit
                }
            }
        }
        if (issues.isNotEmpty()) {
            TACZLegacy.logger.error("[ModifierAudit] Registered modifiers without audit entries: {}", issues)
        }
        if (unconsumed.isNotEmpty()) {
            TACZLegacy.logger.warn("[ModifierAudit] UNCONSUMED (registered but no consumer): {}", unconsumed)
        }
        if (guiOnly.isNotEmpty()) {
            TACZLegacy.logger.warn("[ModifierAudit] GUI_ONLY (preview only, not in combat): {}", guiOnly)
        }
        val applied = audit.values.count { it.status == ConsumerStatus.APPLIED }
        TACZLegacy.logger.info(
            "[ModifierAudit] {} modifier(s) APPLIED, {} GUI_ONLY, {} UNCONSUMED",
            applied, guiOnly.size, unconsumed.size,
        )
    }
}

private interface TACZModifierReader<T> {
    val id: String
    fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<T>?
}

private class TACZNumericModifierReader(
    override val id: String,
    private val optionalField: String? = null,
) : TACZModifierReader<Modifier> {
    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<Modifier>? {
        val modifier = when {
            jsonObject.has(id) -> TACZJson.GSON.fromJson(jsonObject.get(id), Modifier::class.java)
            !optionalField.isNullOrBlank() && jsonObject.has(optionalField) -> Modifier(addend = jsonObject.get(optionalField).asDouble)
            else -> null
        }
        return modifier?.let(::TACZSimpleJsonProperty)
    }
}

private object TACZRecoilModifierReader : TACZModifierReader<TACZRecoilModifierValue> {
    override val id: String = "recoil"

    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<TACZRecoilModifierValue>? {
        val newValue = jsonObject.getAsJsonObject("recoil")?.let { recoilObject ->
            TACZRecoilModifierValue(
                pitch = recoilObject.get("pitch")?.let { TACZJson.GSON.fromJson(it, Modifier::class.java) } ?: Modifier(),
                yaw = recoilObject.get("yaw")?.let { TACZJson.GSON.fromJson(it, Modifier::class.java) } ?: Modifier(),
            )
        }
        if (newValue != null) {
            return TACZSimpleJsonProperty(newValue)
        }
        val oldValue = jsonObject.getAsJsonObject("recoil_modifier")?.let { recoilObject ->
            TACZRecoilModifierValue(
                pitch = Modifier(percent = recoilObject.floatValue("pitch").toDouble()),
                yaw = Modifier(percent = recoilObject.floatValue("yaw").toDouble()),
            )
        }
        return oldValue?.let(::TACZSimpleJsonProperty)
    }
}

private object TACZIgniteModifierReader : TACZModifierReader<TACZIgniteValue> {
    override val id: String = "ignite"

    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<TACZIgniteValue>? {
        val element = jsonObject.get(id) ?: return null
        val value = when {
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> TACZIgniteValue(
                igniteEntity = element.asBoolean,
                igniteBlock = element.asBoolean,
            )
            else -> TACZJson.GSON.fromJson(element, TACZIgniteValue::class.java)
        }
        return TACZSimpleJsonProperty(value)
    }
}

/**
 * 兼容层 ③ C2-exp：配件 explosion 的 typed 值。
 *
 * radius / damage / delay 是 Modifier 四元组，缺省即中性值（等价上游的 `new Modifier()`）；
 * explode / knockback / destroy_block 是布尔，上游语义里它们**不以 explode 为条件**参与聚合。
 */
internal data class TACZExplosionModifierValue(
    val explode: Boolean = false,
    val radius: Modifier = Modifier(),
    val damage: Modifier = Modifier(),
    val knockback: Boolean = false,
    val destroyBlock: Boolean = false,
    val delay: Modifier = Modifier(),
)

private object TACZExplosionModifierReader : TACZModifierReader<TACZExplosionModifierValue> {
    override val id: String = "explosion"

    /**
     * 审计全局-1：explosion 过去用 [TACZRawJsonModifierReader] 把 JsonObject 原样带到结算侧，
     * 每发子弹对每个配件、每个 key 重新 `GSON.fromJson`（TypeAdapter 查找 + 反射 + 装箱），
     * 是射击热路径上唯一的反射点。这里与 recoil / ignite 同构，改成解析期一次成型。
     *
     * 审计 B1-8：格式错误（`radius` 写成裸数字、整个 `explosion` 写成数组）过去被结算侧的
     * `takeIf { isJsonObject }` 静默吞掉，不记 issue。上游靠反射适配器抛 JsonSyntaxException
     * 让该 modifier 可见地失败，所以这里也**直接抛出**，由 readModifiers 的 reader 级隔离记 issue。
     */
    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<TACZExplosionModifierValue>? {
        val element = jsonObject.get(id) ?: return null
        val explosionObject = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw JsonSyntaxException("'explosion' must be a JSON object, but is $element")

        fun modifier(key: String): Modifier {
            val value = explosionObject.get(key) ?: return Modifier()
            if (value.isJsonNull) return Modifier()
            return TACZJson.GSON.fromJson(value, Modifier::class.java) ?: Modifier()
        }

        fun boolean(key: String): Boolean =
            explosionObject.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

        return TACZSimpleJsonProperty(
            TACZExplosionModifierValue(
                explode = boolean("explode"),
                radius = modifier("radius"),
                damage = modifier("damage"),
                knockback = boolean("knockback"),
                destroyBlock = boolean("destroy_block"),
                delay = modifier("delay"),
            ),
        )
    }
}

private object TACZInaccuracyModifierReader : TACZModifierReader<Map<String, Modifier>> {
    override val id: String = "inaccuracy"

    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<Map<String, Modifier>>? {
        val defaultModifier = when {
            jsonObject.has("inaccuracy") -> TACZJson.GSON.fromJson(jsonObject.get("inaccuracy"), Modifier::class.java)
            jsonObject.has("inaccuracy_addend") -> Modifier(addend = jsonObject.get("inaccuracy_addend").asDouble)
            else -> null
        }
        val map = LinkedHashMap<String, Modifier>()
        if (defaultModifier != null) {
            map["default"] = defaultModifier
        }
        jsonObject.get("aim_inaccuracy")?.let { map["aim"] = TACZJson.GSON.fromJson(it, Modifier::class.java) }
        jsonObject.get("sneak_inaccuracy")?.let { map["sneak"] = TACZJson.GSON.fromJson(it, Modifier::class.java) }
        jsonObject.get("lie_inaccuracy")?.let { map["lie"] = TACZJson.GSON.fromJson(it, Modifier::class.java) }
        return if (map.isEmpty()) null else TACZSimpleJsonProperty(map)
    }
}

private class TACZRawJsonModifierReader(
    override val id: String,
) : TACZModifierReader<JsonElement> {
    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<JsonElement>? =
        jsonObject.get(id)?.let(::TACZSimpleJsonProperty)
}

private class TACZSimpleJsonProperty<T>(value: T) : JsonProperty<T>(value) {
    override fun initComponents(): Unit = Unit
}

private object TACZPackDependencyChecker {
    private val versionCache: MutableMap<String, ModContainer?> = ConcurrentHashMap()

    fun allMatch(dependencies: Map<String, String>): Boolean {
        if (dependencies.isEmpty()) {
            return true
        }
        return dependencies.entries.all { (modId, versionRange) -> versionMatch(modId, versionRange) }
    }

    private fun versionMatch(modId: String, version: String): Boolean {
        val container = runCatching {
            versionCache.computeIfAbsent(modId) { Loader.instance().indexedModList[modId] }
        }.getOrNull() ?: return false
        val currentVersion = container.processedVersion
        return runCatching {
            val expected = VersionParser.parseVersionReference("$modId@$version")
            VersionParser.satisfies(expected, currentVersion)
        }.getOrDefault(false)
    }
}

private interface TACZPackSource : AutoCloseable {
    fun listEntries(): Sequence<String>
    fun readText(path: String): String?
    override fun close(): Unit = Unit
}

private class TACZDirectoryPackSource(private val root: File) : TACZPackSource {
    override fun listEntries(): Sequence<String> {
        if (!root.isDirectory) {
            return emptySequence()
        }
        val rootPath = root.toPath()
        val entries = mutableListOf<String>()
        Files.walk(rootPath).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .forEach { path ->
                    entries += rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                }
        }
        entries.sort()
        return entries.asSequence()
    }

    override fun readText(path: String): String? {
        val file = root.toPath().resolve(path)
        if (!Files.isRegularFile(file)) {
            return null
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.readText() }
    }
}

private class TACZZipPackSource(
    private val file: File,
    private val zipFile: ZipFile,
) : TACZPackSource {
    override fun listEntries(): Sequence<String> = try {
        Collections.list(zipFile.entries()).map { it.name }.sorted().asSequence()
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("Failed to enumerate zip entries for ${file.name}", exception)
    }

    override fun readText(path: String): String? {
        try {
            val entry = zipFile.getEntry(path) ?: return null
            return zipFile.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("Failed to read zip entry $path from ${file.name}", exception)
        }
    }
}

internal object TACZJson {
    private val resourceLocationSerializer: JsonSerializer<ResourceLocation> = JsonSerializer { src, _, _ ->
        com.google.gson.JsonPrimitive(src.toString())
    }
    private val resourceLocationDeserializer: JsonDeserializer<ResourceLocation> = JsonDeserializer { json, _, _ ->
        ResourceLocation(json.asString)
    }

    val GSON: Gson = GsonBuilder()
        .registerTypeAdapter(ResourceLocation::class.java, resourceLocationSerializer)
        .registerTypeAdapter(ResourceLocation::class.java, resourceLocationDeserializer)
        .create()

    fun parseObject(json: String): JsonObject = parseElement(json).asJsonObject

    fun parseElement(json: String): JsonElement {
        val reader = JsonReader(StringReader(json))
        reader.isLenient = true
        return JsonParser().parse(reader)
    }

    fun <T> fromJson(json: String, clazz: Class<T>): T {
        val reader = JsonReader(StringReader(json))
        reader.isLenient = true
        return GSON.fromJson(reader, clazz)
    }
}

private fun JsonObject.resourceLocation(key: String): ResourceLocation? =
    get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let(::ResourceLocation)

private fun JsonObject.intValue(key: String): Int = get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0

private fun JsonObject.floatValue(key: String): Float = get(key)?.takeIf { !it.isJsonNull }?.asFloat ?: 0.0f

private fun JsonObject.intArray(key: String): IntArray? =
    getAsJsonArray(key)?.map { it.asInt }?.toIntArray()

private fun JsonObject.stringArray(key: String): List<String> =
    getAsJsonArray(key)?.map { it.asString } ?: emptyList()

private fun JsonObject.getAsJsonObject(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private const val ALLOW_ATTACHMENT_PREFIX: String = "allow_attachments/"
private const val ATTACHMENT_TAG_ROOT_PREFIX: String = "attachments/"
