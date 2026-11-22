package com.music.bitchord.data.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Runtime values that a skin is allowed to override in Zotify.
 *
 * Mochy Music's Look manifest is used as an import format. Zotify deliberately
 * applies only settings it can render natively; custom player code, JavaScript
 * and GPU shaders are never executed or loaded from a skin package.
 */
data class AppearanceLookOverrides(
    val accentArgb: Int? = null,
    val pureBlack: Boolean? = null,
    val thumbnailCornerRadius: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("accentArgb", accentArgb ?: JSONObject.NULL)
        .put("pureBlack", pureBlack ?: JSONObject.NULL)
        .put("thumbnailCornerRadius", thumbnailCornerRadius ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): AppearanceLookOverrides = AppearanceLookOverrides(
            accentArgb = if (value.has("accentArgb") && !value.isNull("accentArgb")) value.optInt("accentArgb") else null,
            pureBlack = if (value.has("pureBlack") && !value.isNull("pureBlack")) value.optBoolean("pureBlack") else null,
            thumbnailCornerRadius = if (value.has("thumbnailCornerRadius") && !value.isNull("thumbnailCornerRadius")) value.optInt("thumbnailCornerRadius") else null,
        )
    }
}

data class AppearanceSkinPack(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val source: String,
    val overrides: AppearanceLookOverrides,
    val supportedSettings: List<String>,
    val unsupportedSettings: List<String>,
    val rawManifest: String,
) {
    val canApply: Boolean
        get() = supportedSettings.isNotEmpty()
}

/**
 * Persistent, native appearance overrides used by imported skin packs.
 * Initialized with AppSettings so the selected look is present on first draw.
 */
object AppearanceSkinState {
    private const val PREFS = "appearance_skin_runtime"
    private const val KEY_ACCENT = "accent_argb"
    private const val KEY_PURE_BLACK = "pure_black"
    private const val KEY_CORNER = "thumbnail_corner_radius"

    val accentArgb = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val pureBlack = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val thumbnailCornerRadius = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    @Volatile private var preferences: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences = prefs
        accentArgb.value = if (prefs.contains(KEY_ACCENT)) prefs.getInt(KEY_ACCENT, 0) else null
        pureBlack.value = if (prefs.contains(KEY_PURE_BLACK)) prefs.getBoolean(KEY_PURE_BLACK, false) else null
        thumbnailCornerRadius.value = if (prefs.contains(KEY_CORNER)) {
            prefs.getInt(KEY_CORNER, 24).coerceIn(0, 45)
        } else null
    }

    fun snapshot(): AppearanceLookOverrides = AppearanceLookOverrides(
        accentArgb = accentArgb.value,
        pureBlack = pureBlack.value,
        thumbnailCornerRadius = thumbnailCornerRadius.value,
    )

    fun apply(value: AppearanceLookOverrides) {
        accentArgb.value = value.accentArgb
        pureBlack.value = value.pureBlack
        thumbnailCornerRadius.value = value.thumbnailCornerRadius?.coerceIn(0, 45)
        val prefs = preferences ?: return
        prefs.edit().apply {
            if (value.accentArgb == null) remove(KEY_ACCENT) else putInt(KEY_ACCENT, value.accentArgb)
            if (value.pureBlack == null) remove(KEY_PURE_BLACK) else putBoolean(KEY_PURE_BLACK, value.pureBlack)
            if (value.thumbnailCornerRadius == null) remove(KEY_CORNER)
            else putInt(KEY_CORNER, value.thumbnailCornerRadius.coerceIn(0, 45))
        }.apply()
    }
}

/**
 * Local Mochy-compatible Look pack manager.
 *
 * Accepted inputs: a .zip with manifest.json, a manifest.json file, an HTTPS
 * manifest/zip URL, or a public GitHub repository/release URL. The ZIP reader
 * enforces entry, per-file, and total-size limits and never extracts paths.
 */
object AppearanceSkinLibrary {
    private const val PREFS = "appearance_skin_library"
    private const val KEY_PACKS = "installed_packs"
    private const val KEY_ACTIVE = "active_pack"
    private const val KEY_BASELINE = "baseline_look"

    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_PACKAGE_BYTES = 30 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 200
    private val validId = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")

    fun list(context: Context): List<AppearanceSkinPack> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACKS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    val manifest = row.optString("manifest").takeIf { it.isNotBlank() } ?: continue
                    val source = row.optString("source", "Imported file")
                    runCatching { parseManifest(manifest, source) }.getOrNull()?.let(::add)
                }
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun activeId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)

    suspend fun importFile(context: Context, uri: Uri): AppearanceSkinPack = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_PACKAGE_BYTES) }
            ?: error("Could not open the selected skin file.")
        val source = uri.lastPathSegment?.substringAfterLast('/')?.take(120) ?: "Selected file"
        parsePackage(bytes, source)
    }

    suspend fun importUrl(context: Context, input: String): AppearanceSkinPack = withContext(Dispatchers.IO) {
        val cleaned = input.trim()
        require(cleaned.startsWith("https://", ignoreCase = true)) {
            "Only HTTPS skin links are accepted."
        }
        val candidates = resolveGithubUrls(cleaned)
        var lastFailure: Throwable? = null
        for (candidate in candidates) {
            try {
                return@withContext parsePackage(downloadHttps(candidate), candidate)
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw IllegalArgumentException(
            "No compatible Mochy Look manifest or skin ZIP was found at this link.",
            lastFailure,
        )
    }

    fun install(context: Context, pack: AppearanceSkinPack) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = list(context).filterNot { it.id == pack.id }.toMutableList()
        all += pack
        val array = JSONArray()
        all.forEach { item ->
            array.put(JSONObject().put("manifest", item.rawManifest).put("source", item.source))
        }
        prefs.edit().putString(KEY_PACKS, array.toString()).apply()
        if (activeId(context) == pack.id) enable(context, pack.id)
    }

    fun enable(context: Context, id: String) {
        val pack = list(context).firstOrNull { it.id == id } ?: error("This skin is no longer installed.")
        require(pack.canApply) { "This pack has no appearance settings that Zotify currently supports." }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentActive = prefs.getString(KEY_ACTIVE, null)
        if (currentActive == null) {
            prefs.edit().putString(KEY_BASELINE, AppearanceSkinState.snapshot().toJson().toString()).apply()
        } else {
            restoreBaseline(context, prefs)
        }
        AppearanceSkinState.apply(pack.overrides)
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun disable(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restoreBaseline(context, prefs)
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_BASELINE).apply()
    }

    fun remove(context: Context, id: String) {
        if (activeId(context) == id) disable(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray()
        list(context).filterNot { it.id == id }.forEach { item ->
            array.put(JSONObject().put("manifest", item.rawManifest).put("source", item.source))
        }
        prefs.edit().putString(KEY_PACKS, array.toString()).apply()
    }

    private fun restoreBaseline(context: Context, prefs: android.content.SharedPreferences) {
        val raw = prefs.getString(KEY_BASELINE, null) ?: return
        val baseline = runCatching { AppearanceLookOverrides.fromJson(JSONObject(raw)) }.getOrNull() ?: return
        AppearanceSkinState.apply(baseline)
    }

    private fun parsePackage(bytes: ByteArray, source: String): AppearanceSkinPack {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PACKAGE_BYTES) { "Skin package is empty or too large." }
        val looksLikeZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val manifestBytes = if (looksLikeZip || source.substringBefore('?').endsWith(".zip", true)) {
            findManifestInZip(bytes)
        } else {
            require(bytes.size <= MAX_MANIFEST_BYTES) { "manifest.json is larger than 256 KB." }
            bytes
        }
        val manifest = manifestBytes.toString(Charsets.UTF_8)
        return parseManifest(manifest, source)
    }

    private fun findManifestInZip(bytes: ByteArray): ByteArray {
        var manifest: ByteArray? = null
        var entries = 0
        var totalUncompressed = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                require(entries <= MAX_ZIP_ENTRIES) { "Skin ZIP contains too many files (limit: 200)." }
                val normalized = entry.name.replace('\\', '/')
                val segments = normalized.split('/')
                require(!normalized.startsWith('/') && segments.none { it == ".." }) {
                    "Skin ZIP contains an unsafe file path."
                }
                val isManifest = !entry.isDirectory && segments.lastOrNull().equals("manifest.json", true)
                val limit = if (isManifest) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                val content = readLimited(zip, limit)
                totalUncompressed += content.size
                require(totalUncompressed <= MAX_PACKAGE_BYTES) { "Skin ZIP expands beyond the 30 MB limit." }
                if (isManifest && manifest == null) manifest = content
                zip.closeEntry()
            }
        }
        return manifest ?: error("No manifest.json was found in the skin ZIP.")
    }

    private fun parseManifest(raw: String, source: String): AppearanceSkinPack {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "manifest.json is larger than 256 KB." }
        val root = JSONObject(raw)
        val id = root.optString("id").trim()
        require(validId.matches(id)) { "The skin manifest has a missing or invalid id." }
        val look = root.optJSONObject("look") ?: error("This package has no Mochy-compatible look settings.")
        val supported = linkedMapOf<String, String>()
        val unsupported = linkedSetOf<String>()
        val keys = look.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = look.opt(key)?.toString()?.trim().orEmpty()
            when (key) {
                "accentColor" -> {
                    val color = value.removePrefix("#")
                    if (color.matches(Regex("[0-9a-fA-F]{6}"))) supported[key] = "#$color"
                    else unsupported += key
                }
                "pureBlack" -> {
                    val parsed = value.toBooleanStrictOrNull()
                    if (parsed != null) supported[key] = parsed.toString() else unsupported += key
                }
                "thumbnailCornerRadius" -> {
                    val radius = value.toIntOrNull()
                    if (radius != null && radius in 0..45) supported[key] = radius.toString()
                    else unsupported += key
                }
                else -> unsupported += key
            }
        }
        if (root.has("player")) unsupported += "player layout"
        if (root.has("font")) unsupported += "custom font"
        if (root.has("glass")) unsupported += "shader glass"
        if (root.has("pages")) unsupported += "custom pages"
        val name = root.optString("name").trim().ifBlank { id }.take(80)
        val version = root.optString("version", "1.0").take(40)
        val description = root.optString("description").take(300)
        val author = root.optString("author").take(80)
        return AppearanceSkinPack(
            id = id,
            name = name,
            version = version,
            description = description,
            author = author,
            source = source.take(300),
            overrides = AppearanceLookOverrides(
                accentArgb = supported["accentColor"]?.let(::parseColor),
                pureBlack = supported["pureBlack"]?.toBooleanStrictOrNull(),
                thumbnailCornerRadius = supported["thumbnailCornerRadius"]?.toIntOrNull(),
            ),
            supportedSettings = supported.keys.toList(),
            unsupportedSettings = unsupported.toList(),
            rawManifest = raw,
        )
    }

    private fun parseColor(value: String): Int? = runCatching {
        android.graphics.Color.parseColor(value)
    }.getOrNull()

    private fun fetchGithubRefNames(owner: String, repo: String, collection: String): List<String> {
        require(collection == "branches" || collection == "tags")
        val names = mutableListOf<String>()
        for (page in 1..10) {
            val refs = JSONArray(
                downloadHttps(
                    "https://api.github.com/repos/$owner/$repo/$collection?per_page=100&page=$page",
                ).toString(Charsets.UTF_8),
            )
            for (index in 0 until refs.length()) {
                val name = refs.optJSONObject(index)?.optString("name").orEmpty()
                if (name.isNotBlank()) names += name
            }
            if (refs.length() < 100) break
        }
        return names
    }

    private fun resolveGithubRefPath(
        owner: String,
        repo: String,
        remainingPath: List<String>,
    ): Pair<String, String> {
        val fullPath = remainingPath.joinToString("/")
        fun matchRef(names: List<String>): Pair<String, String>? {
            val ref = names
                .filter { fullPath == it || fullPath.startsWith("$it/") }
                .maxByOrNull { it.length }
                ?: return null
            val relativePath = if (fullPath == ref) "" else fullPath.removePrefix("$ref/")
            return ref to relativePath
        }

        // Branch and tag names may contain slashes. Match the longest known ref
        // before interpreting the remaining segments as a repository file path.
        val branch = runCatching {
            matchRef(fetchGithubRefNames(owner, repo, "branches"))
        }.getOrNull()
        if (branch != null) return branch

        val tag = runCatching {
            matchRef(fetchGithubRefNames(owner, repo, "tags"))
        }.getOrNull()
        if (tag != null) return tag

        // GitHub blob/tree links may also point directly to a full or abbreviated
        // commit SHA. Resolve it through the API so raw URLs use the canonical SHA.
        val possibleSha = fullPath.substringBefore('/')
        if (possibleSha.matches(Regex("[0-9a-fA-F]{7,40}"))) {
            val canonicalSha = runCatching {
                JSONObject(
                    downloadHttps(
                        "https://api.github.com/repos/$owner/$repo/commits/$possibleSha",
                    ).toString(Charsets.UTF_8),
                ).optString("sha")
            }.getOrNull().orEmpty()
            if (canonicalSha.matches(Regex("[0-9a-fA-F]{40}"))) {
                val relativePath = if (fullPath == possibleSha) "" else fullPath.removePrefix("$possibleSha/")
                return canonicalSha to relativePath
            }
        }

        error("Could not identify a branch, tag, or commit in this GitHub URL.")
    }

    private fun resolveGithubUrls(input: String): List<String> {
        val url = URL(input)
        if (!url.host.equals("github.com", ignoreCase = true)) return listOf(input)
        val parts = Uri.decode(url.path).trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return listOf(input)
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return listOf(input)

        // Git refs may contain slashes. Resolve a branch, tag, or commit SHA before
        // separating the ref from the file path.
        if ((parts.size >= 5 && parts[2] == "blob") ||
            (parts.size >= 4 && parts[2] == "tree")
        ) {
            val (branch, relativePath) = resolveGithubRefPath(owner, repo, parts.drop(3))
            val filePath = if (parts[2] == "tree") {
                if (relativePath.isBlank()) "manifest.json" else "$relativePath/manifest.json"
            } else {
                require(relativePath.isNotBlank()) { "The GitHub blob URL must point to a file." }
                relativePath
            }
            return listOf("https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath")
        }
        // A direct release-asset URL is already the exact file the user selected.
        if (parts.size >= 6 && parts[2] == "releases" && parts[3] == "download" &&
            parts.last().endsWith(".zip", ignoreCase = true)
        ) return listOf(input)

        // A repository URL uses GitHub's declared default branch, never an assumed "main".
        val metadata = try {
            JSONObject(downloadHttps("https://api.github.com/repos/$owner/$repo").toString(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw IllegalArgumentException(
                "Could not resolve this GitHub repository's default branch. Paste a direct manifest.json or ZIP URL instead.",
                failure,
            )
        }
        val defaultBranch = metadata.optString("default_branch").takeIf { it.isNotBlank() }
            ?: error("GitHub did not provide a default branch for this repository.")

        // Try every ZIP asset, prioritising skin/look-named archives and validating each
        // package's manifest before selecting it. Repositories often publish several ZIPs.
        val candidates = mutableListOf<Pair<Int, String>>()
        val api = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val release = runCatching { JSONObject(downloadHttps(api).toString(Charsets.UTF_8)) }.getOrNull()
        val assets = release?.optJSONArray("assets")
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val download = asset.optString("browser_download_url")
                if (!name.endsWith(".zip", true) || !download.startsWith("https://")) continue
                val lowerName = name.lowercase()
                val priority = if ("skin" in lowerName || "look" in lowerName || "mochy" in lowerName) 0 else 1
                candidates += priority to download
            }
        }
        val manifest = "https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/manifest.json"
        return candidates.sortedBy { it.first }.map { it.second }.distinct() + manifest
    }

    private fun downloadHttps(input: String): ByteArray {
        var current = URL(input)
        for (redirectCount in 0 until 5) {
            require(current.protocol.equals("https", ignoreCase = true)) { "Skin links must stay on HTTPS." }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Zotify-Music-Skin-Importer")
            connection.setRequestProperty("Accept", "application/json, application/zip, */*")
            var redirect: URL? = null
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Skin host returned an invalid redirect.")
                    redirect = URL(current, location)
                } else {
                    require(status == HttpURLConnection.HTTP_OK) {
                        "Skin download failed (HTTP $status)."
                    }
                    val declared = connection.contentLengthLong
                    require(declared <= MAX_PACKAGE_BYTES || declared < 0) {
                        "Skin download exceeds the 30 MB limit."
                    }
                    return connection.inputStream.use { readLimited(it, MAX_PACKAGE_BYTES) }
                }
            } finally {
                connection.disconnect()
            }
            current = redirect ?: error("Skin host returned an invalid redirect.")
        }
        error("Too many redirects while downloading this skin.")
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Skin file exceeds the size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)
}
