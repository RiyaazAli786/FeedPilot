package com.feedpilot.client.feature.upgrade.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.feedpilot.client.BuildConfig

/**
 * Data structure representing a post asset (image content + caption).
 */
data class PostAsset(
    val imageBytes: ByteArray,
    val caption: String,
    val imageName: String,
    val captionName: String?
)

data class UpgradeImageAsset(
    val bytes: ByteArray,
    val fileName: String
)

data class BioAsset(
    val text: String,
    val fileName: String
)

data class CaptionAsset(
    val text: String,
    val fileName: String
)

/**
 * Provider class that encapsulates loading of upgrade checklist assets (posts, profile photos,
 * story images, and biography texts) from remote servers, local filesystem directories, or Android assets.
 *
 * Uniqueness guarantee per username per asset type:
 *   - A shuffled queue of all available indices is generated and stored in SharedPreferences.
 *   - Each call dequeues the next item from the front of the queue.
 *   - When the queue is exhausted a fresh shuffle is generated, ensuring every asset
 *     is used exactly once before any asset repeats (Fisher-Yates rotation).
 */
@Singleton
class UpgradeDataProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "UpgradeDataProvider"
    }

    private val httpClient = OkHttpClient()
    private val remoteCountCache = ConcurrentHashMap<String, Int>()

    // Prefs file names
    private val QUEUE_PREFS = "upgrade_asset_queues"

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Clears the cached remote counts, forcing a fresh server scan on the next request.
     */
    fun clearCache() {
        remoteCountCache.clear()
        folderListCache.clear()
        assignedFolderCache.clear()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Queue-based unique index picking (the core of uniqueness)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the next index for [username]+[gender]+[type] ensuring full uniqueness before
     * rotation.
     *
     * Internally maintains a per-(username,gender,type) JSON queue in SharedPreferences:
     *   • If the queue is empty (or its size no longer matches [totalCount] due to server
     *     changes), a new Fischer-Yates shuffle of [1..totalCount] is generated.
     *   • The first element is popped and returned; the remaining queue is saved back.
     *
     * This means every index from 1 to N is used exactly once in a random order before
     * any index repeats. Keying by gender too means switching an account's gender starts a
     * fresh rotation over that gender's own pool rather than sharing one with the other gender.
     */
    private fun dequeueNextIndex(username: String, gender: String, type: String, totalCount: Int): Int {
        if (totalCount <= 0) return 1

        val prefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val key = "${username}_${gender}_${type}_queue"

        // Load existing queue
        val raw = prefs.getString(key, "") ?: ""
        val queue: ArrayDeque<Int> = if (raw.isBlank()) {
            ArrayDeque()
        } else {
            try {
                raw.split(",").mapNotNull { it.trim().toIntOrNull() }
                   .filter { it in 1..totalCount }
                   .let { ArrayDeque(it) }
            } catch (e: Exception) {
                ArrayDeque()
            }
        }

        // If empty or stale (totalCount changed), regenerate a fresh sequential queue (1..totalCount) for clean loopback
        if (queue.isEmpty()) {
            val sequential = (1..totalCount).toList()
            queue.addAll(sequential)
        }

        // Pop from front
        val next = queue.removeFirst()

        // Persist remaining queue
        prefs.edit().putString(key, queue.joinToString(",")).apply()

        return next
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Asset count resolution
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the remote server assets URL if specified via system property or environment variable.
     */
    private fun getAssetsBaseUrl(): String? {
        BuildConfig.API_BASE_URL
            .takeIf { it.isNotBlank() }
            ?.let { return it.trimEnd('/') + "/upgrade" }
        val sysUrl = System.getProperty("UPGRADE_ASSETS_URL")
        if (!sysUrl.isNullOrBlank()) return sysUrl
        val envUrl = System.getenv("UPGRADE_ASSETS_URL")
        if (!envUrl.isNullOrBlank()) return envUrl
        return null
    }

    /**
     * Resolves the base directory where custom asset folders (posts, profiles, stories, bios) are placed locally.
     */
    private fun getAssetsBaseDir(): File? {
        val sysPropertyPath = System.getProperty("UPGRADE_ASSETS_DIR")
        if (!sysPropertyPath.isNullOrBlank()) {
            val file = File(sysPropertyPath)
            if (file.exists() && file.isDirectory) return file
        }

        val envVarPath = System.getenv("UPGRADE_ASSETS_DIR")
        if (!envVarPath.isNullOrBlank()) {
            val file = File(envVarPath)
            if (file.exists() && file.isDirectory) return file
        }

        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            val file = File(extDir, "upgrade")
            if (file.exists() && file.isDirectory) return file
        }

        return null
    }

    /**
     * Scans the remote folder using sequential HTTP HEAD requests (falling back to GET if HEAD is not allowed)
     * to count how many sequential files exist (e.g. 1.jpg, 2.jpg... up to N).
     */
    private fun countRemoteFiles(baseUrl: String, gender: String, type: String, fileExtension: String): Int {
        val cleanGender = gender.trim().lowercase().takeIf { it == "male" || it == "female" } ?: "female"
        var count = 0
        while (true) {
            val nextIndex = count + 1
            val fileUrl = baseUrl.trimEnd('/') + "/$cleanGender/$type/$nextIndex$fileExtension"
            var request = Request.Builder().url(fileUrl).head().build()
            try {
                var response = httpClient.newCall(request).execute()
                if (response.code == 405) { // Method Not Allowed fallback to GET
                    response.close()
                    request = Request.Builder().url(fileUrl).get().build()
                    response = httpClient.newCall(request).execute()
                }

                response.use { resp ->
                    if (resp.isSuccessful) {
                        count = nextIndex
                    } else {
                        Log.i(TAG, "Remote asset count stopped at $fileUrl code=${resp.code}; count=$count")
                        return count
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote asset count failed for $fileUrl; count=$count", e)
                return count
            }
        }
    }

    /**
     * Downloads raw bytes from the remote server.
     */
    private fun fetchServerBytes(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    Log.i(TAG, "Fetched remote asset $url bytes=${bytes?.size ?: 0}")
                    bytes
                } else {
                    Log.w(TAG, "Remote asset fetch failed $url code=${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote asset fetch threw for $url", e)
            null
        }
    }

    /**
     * Determines how many matching files exist for a given checklist asset type.
     * Uses a short-lived in-memory cache to avoid re-scanning on every single call.
     */
    private fun getAvailableCount(gender: String, type: String, fileExtension: String, defaultCount: Int): Int {
        val baseUrl = getAssetsBaseUrl()
        if (baseUrl != null) {
            val cacheKey = "${gender}_${type}_$fileExtension"
            val cachedCount = remoteCountCache[cacheKey]
            if (cachedCount != null) return cachedCount

            val remoteCount = countRemoteFiles(baseUrl, gender, type, fileExtension)
            val finalCount = if (remoteCount > 0) remoteCount else defaultCount
            Log.i(TAG, "Available remote assets gender=$gender type=$type ext=$fileExtension count=$remoteCount using=$finalCount base=$baseUrl")
            remoteCountCache[cacheKey] = finalCount
            return finalCount
        }

        val baseDir = getAssetsBaseDir()
        if (baseDir != null) {
            val typeDir = File(File(baseDir, gender), type)
            if (typeDir.exists() && typeDir.isDirectory) {
                val files = typeDir.listFiles { _, name -> name.endsWith(fileExtension, ignoreCase = true) }
                if (files != null && files.isNotEmpty()) {
                    return files.size
                }
            }
        }

        try {
            val files = context.assets.list("upgrade/$gender/$type")
            if (files != null) {
                val count = files.count { it.endsWith(fileExtension, ignoreCase = true) }
                if (count > 0) return count
            }
        } catch (e: Exception) {
            // Fallback
        }

        return defaultCount
    }

    // ──────────────────────────────────────────────────────────────────────────
    // File reading
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Helper to read a file's raw bytes either from the remote server, local directories, or Android assets.
     */
    private fun readFileBytes(gender: String, type: String, fileName: String): ByteArray? {
        val cleanGender = gender.trim().lowercase().takeIf { it == "male" || it == "female" } ?: "female"
        val baseUrl = getAssetsBaseUrl()
        if (baseUrl != null) {
            val fileUrl = baseUrl.trimEnd('/') + "/$cleanGender/$type/$fileName"
            return fetchServerBytes(fileUrl)
        }

        val baseDir = getAssetsBaseDir()
        if (baseDir != null) {
            val file = File(File(File(baseDir, cleanGender), type), fileName)
            if (file.exists() && file.isFile) {
                try {
                    return file.readBytes()
                } catch (e: Exception) {
                    // Fallback
                }
            }
        }

        try {
            context.assets.open("upgrade/$cleanGender/$type/$fileName").use { input ->
                return input.readBytes()
            }
        } catch (e: Exception) {
            // Fallback
        }

        return null
    }

    /**
     * Helper to read a text file's contents as UTF-8.
     */
    private fun readTextFile(gender: String, type: String, fileName: String): String? {
        val bytes = readFileBytes(gender, type, fileName) ?: return null
        return String(bytes, Charsets.UTF_8).trim()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Dummy image generator
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Generates a solid random-colour JPEG image as a fallback when no asset file is found.
     */
    fun generateDummyImage(): ByteArray {
        return try {
            generateBitmapImage()
        } catch (t: Throwable) {
            // Tiny 1×1 grey pixel JPEG fallback for headless JVM / server compilation
            byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
                0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x48.toByte(),
                0x00.toByte(), 0x48.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(),
                0x00.toByte(), 0x03.toByte(), 0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x03.toByte(),
                0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x03.toByte(), 0x03.toByte(), 0x03.toByte(), 0x03.toByte(), 0x04.toByte(),
                0x06.toByte(), 0x04.toByte(), 0x04.toByte(), 0x04.toByte(), 0x04.toByte(), 0x04.toByte(), 0x08.toByte(), 0x06.toByte(),
                0x06.toByte(), 0x05.toByte(), 0x06.toByte(), 0x09.toByte(), 0x08.toByte(), 0x0A.toByte(), 0x0A.toByte(), 0x09.toByte(),
                0x08.toByte(), 0x09.toByte(), 0x09.toByte(), 0x0A.toByte(), 0x0C.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x0A.toByte(),
                0x0B.toByte(), 0x0E.toByte(), 0x0B.toByte(), 0x09.toByte(), 0x09.toByte(), 0x0D.toByte(), 0x11.toByte(), 0x0D.toByte(),
                0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x10.toByte(), 0x11.toByte(), 0x10.toByte(), 0x0A.toByte(), 0x0C.toByte(),
                0x12.toByte(), 0x13.toByte(), 0x12.toByte(), 0x10.toByte(), 0x13.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x10.toByte(),
                0x10.toByte(), 0xFF.toByte(), 0xC9.toByte(), 0x00.toByte(), 0x0B.toByte(), 0x08.toByte(), 0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x11.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xCC.toByte(),
                0x00.toByte(), 0x06.toByte(), 0x00.toByte(), 0x10.toByte(), 0x10.toByte(), 0x05.toByte(), 0xFF.toByte(), 0xDA.toByte(),
                0x00.toByte(), 0x08.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3F.toByte(), 0x00.toByte(),
                0xD2.toByte(), 0xCF.toByte(), 0x20.toByte(), 0xFF.toByte(), 0xD9.toByte()
            )
        }
    }

    private fun generateBitmapImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.HSVToColor(floatArrayOf((0..360).random().toFloat(), 0.8f, 0.8f))
        }
        canvas.drawRect(0f, 0f, 100f, 100f, paint)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Profile Folders & User Pointer Tracking (New Upgrade Structure)
    // ──────────────────────────────────────────────────────────────────────────

    data class RemoteFolderInfo(
        val name: String,
        val index: Int,
        val imageCount: Int,
        val imageUrls: List<String>
    )

    private val folderListCache = ConcurrentHashMap<String, List<RemoteFolderInfo>>()

    /**
     * Fetches available profile folders from the backend API for a given gender.
     */
    fun getProfileFolders(gender: String): List<RemoteFolderInfo> {
        val cleanGender = gender.trim().lowercase().takeIf { it == "male" || it == "female" } ?: "female"
        val cached = folderListCache[cleanGender]
        if (cached != null) return cached

        val baseUrl = getAssetsBaseUrl()
        if (baseUrl != null) {
            val serverApi = baseUrl.removeSuffix("/upgrade").trimEnd('/') + "/dashboard/profile-folders?gender=$cleanGender"
            val request = Request.Builder().url(serverApi).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = response.body?.string() ?: ""
                        val jsonArray = org.json.JSONArray(json)
                        val list = mutableListOf<RemoteFolderInfo>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val name = obj.optString("name")
                            val idx = obj.optInt("index", i + 1)
                            val count = obj.optInt("imageCount", 0)
                            val urlsArray = obj.optJSONArray("imageUrls")
                            val urls = mutableListOf<String>()
                            if (urlsArray != null) {
                                for (j in 0 until urlsArray.length()) {
                                    val relUrl = urlsArray.getString(j)
                                    val fullUrl = baseUrl.removeSuffix("/upgrade").trimEnd('/') + relUrl
                                    urls.add(fullUrl)
                                }
                            }
                            list.add(RemoteFolderInfo(name, idx, count, urls))
                        }
                        if (list.isNotEmpty()) {
                            folderListCache[cleanGender] = list
                            return list
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote folder fetch failed for $cleanGender", e)
            }
        }
        return emptyList()
    }

    private val assignedFolderCache = ConcurrentHashMap<String, RemoteFolderInfo>()

    /**
     * Queries backend server to get or assign the profile folder for [username] and [gender].
     * Server maintains global account-wise rotation and persistence across devices.
     */
    fun getAssignedProfileFolder(username: String, gender: String): RemoteFolderInfo? {
        val cleanGender = gender.trim().lowercase().takeIf { it == "male" || it == "female" } ?: "female"
        val cacheKey = "${username}_$cleanGender"
        val cached = assignedFolderCache[cacheKey]
        if (cached != null) return cached

        val baseUrl = getAssetsBaseUrl()
        if (baseUrl != null) {
            val serverApi = baseUrl.removeSuffix("/upgrade").trimEnd('/') + "/dashboard/assign-profile-folder?username=${android.net.Uri.encode(username)}&gender=$cleanGender"
            val request = Request.Builder().url(serverApi).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = response.body?.string() ?: ""
                        val obj = org.json.JSONObject(json)
                        val name = obj.optString("name")
                        val idx = obj.optInt("index", 1)
                        val count = obj.optInt("imageCount", 0)
                        val urlsArray = obj.optJSONArray("imageUrls")
                        val urls = mutableListOf<String>()
                        if (urlsArray != null) {
                            for (j in 0 until urlsArray.length()) {
                                val relUrl = urlsArray.getString(j)
                                val fullUrl = baseUrl.removeSuffix("/upgrade").trimEnd('/') + relUrl
                                urls.add(fullUrl)
                            }
                        }
                        val folder = RemoteFolderInfo(name, idx, count, urls)
                        assignedFolderCache[cacheKey] = folder
                        return folder
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend assign profile folder failed for $username ($cleanGender)", e)
            }
        }
        return null
    }

    /**
     * Resets local cached folder assignment for [username] after full upgrade completion.
     */
    fun resetAccountFolderAssignment(username: String, gender: String) {
        val cleanGender = gender.trim().lowercase().takeIf { it == "male" || it == "female" } ?: "female"
        assignedFolderCache.remove("${username}_$cleanGender")
        val prefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${username}_${cleanGender}_post_counter")
            .apply()
    }

    private fun getNextPostCounter(username: String, gender: String): Int {
        val prefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val key = "${username}_${gender}_post_counter"
        val count = prefs.getInt(key, 0)
        prefs.edit().putInt(key, count + 1).apply()
        return count
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public asset accessors
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the next unique timeline post (image + caption) for [username], drawn from the
     * [gender] asset pool ("male" or "female").
     *
     * If Profile Folders exist, picks 6 post images sequentially from the active server-assigned folder for [username]
     * (reusing images if folder has < 6 images).
     */
    fun getNextPost(username: String, gender: String): PostAsset {
        val folder = getAssignedProfileFolder(username, gender)
        if (folder != null && folder.imageCount > 0 && folder.imageUrls.isNotEmpty()) {
            val postSeq = getNextPostCounter(username, gender)
            val targetImageIndex = postSeq % folder.imageCount // Reuse existing if count < 6
            val imageUrl = folder.imageUrls[targetImageIndex]
            val imgBytes = fetchServerBytes(imageUrl) ?: generateDummyImage()
            val captionAsset = getNextCaptionText(username, gender)
            val caption = captionAsset?.text ?: "Post #${postSeq + 1} from folder ${folder.name}"
            return PostAsset(imgBytes, caption, "${folder.name}_${targetImageIndex + 1}.jpg", captionAsset?.fileName)
        }

        // Fallback to legacy single post pool
        val totalCount = getAvailableCount(gender, "posts", ".jpg", 6)
        val index = dequeueNextIndex(username, gender, "posts", totalCount)
        val imageName = "$index.jpg"
        val legacyCaptionName = "$index.txt"
        val imgBytes = readFileBytes(gender, "posts", imageName) ?: generateDummyImage()
        // Prefers a caption paired with this exact legacy post image if one was ever uploaded
        // that way, otherwise draws from the dedicated Post Captions pool, then a generic default.
        val legacyCaption = readTextFile(gender, "posts", legacyCaptionName)
        val caption = legacyCaption ?: getNextCaptionText(username, gender)?.text ?: "Upgrade post #$index"
        return PostAsset(imgBytes, caption, imageName, if (legacyCaption != null) legacyCaptionName else null)
    }

    /**
     * Returns the next profile picture bytes for [username], drawn from the 8th image of the active folder
     * (or reuses existing if folder has < 8 images). Fallback to profile asset pool if no folders.
     */
    fun getNextProfilePic(username: String, gender: String): UpgradeImageAsset {
        val folder = getAssignedProfileFolder(username, gender)
        if (folder != null && folder.imageCount > 0 && folder.imageUrls.isNotEmpty()) {
            // Profile uses 8th image (index 7), or reuses if count < 8
            val targetIndex = 7 % folder.imageCount
            val imageUrl = folder.imageUrls[targetIndex]
            val bytes = fetchServerBytes(imageUrl) ?: generateDummyImage()
            return UpgradeImageAsset(bytes, "${folder.name}_profile.jpg")
        }

        val totalCount = getAvailableCount(gender, "profiles", ".jpg", 1)
        val index = dequeueNextIndex(username, gender, "profiles", totalCount)
        val fileName = "$index.jpg"
        return UpgradeImageAsset(readFileBytes(gender, "profiles", fileName) ?: generateDummyImage(), fileName)
    }

    /**
     * Returns the next story image bytes for [username], drawn from the 7th image of the active folder
     * (or reuses existing if folder has < 7 images). Fallback to story asset pool if no folders.
     */
    fun getNextStoryImage(username: String, gender: String): UpgradeImageAsset {
        val folder = getAssignedProfileFolder(username, gender)
        if (folder != null && folder.imageCount > 0 && folder.imageUrls.isNotEmpty()) {
            // Story uses 7th image (index 6), or reuses if count < 7
            val targetIndex = 6 % folder.imageCount
            val imageUrl = folder.imageUrls[targetIndex]
            val bytes = fetchServerBytes(imageUrl) ?: generateDummyImage()
            return UpgradeImageAsset(bytes, "${folder.name}_story.jpg")
        }

        val totalCount = getAvailableCount(gender, "stories", ".jpg", 1)
        val index = dequeueNextIndex(username, gender, "stories", totalCount)
        val fileName = "$index.jpg"
        return UpgradeImageAsset(readFileBytes(gender, "stories", fileName) ?: generateDummyImage(), fileName)
    }

    /**
     * Returns the next unique biography text for [username], drawn from the [gender] asset pool.
     */
    fun getNextBioText(username: String, gender: String): BioAsset {
        val totalCount = getAvailableCount(gender, "bios", ".txt", 1)
        val index = dequeueNextIndex(username, gender, "bios", totalCount)
        val fileName = "$index.txt"
        return BioAsset(readTextFile(gender, "bios", fileName) ?: "Content Creator", fileName)
    }

    /**
     * Returns the next unique post caption for [username], drawn from the dashboard-uploaded
     * [gender] "Post Captions" pool. Returns null (rather than a placeholder) when the admin
     * hasn't uploaded any captions for this gender yet, so callers can fall back to their own
     * default text instead of silently publishing a generic one.
     */
    fun getNextCaptionText(username: String, gender: String): CaptionAsset? {
        val totalCount = getAvailableCount(gender, "captions", ".txt", 0)
        if (totalCount <= 0) return null
        val index = dequeueNextIndex(username, gender, "captions", totalCount)
        val fileName = "$index.txt"
        val text = readTextFile(gender, "captions", fileName) ?: return null
        return CaptionAsset(text, fileName)
    }
}
