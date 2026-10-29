package com.e2ee.plugin

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.discord.api.message.LocalAttachment
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.attachments.AttachmentUtilsKt
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import com.lytefast.flexinput.model.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.SecretKey

/**
 * Encrypted file support. The file is `IV(12) || AES-GCM`, the name carries a ".enc" marker
 * before the extension and the caption starts with a "[sz:W,H]" tag.
 */
object Media {
    private const val MAX_IMAGE_BYTES = 50 * 1024 * 1024
    private const val MAX_SIDE = 1600
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    private val ENC_MARKER = Pattern.compile("\\.enc(?=\\.|$)", Pattern.CASE_INSENSITIVE)
    private val IMAGE_NAME = Pattern.compile("\\.(?:png|jpe?g|gif|webp|bmp)$", Pattern.CASE_INSENSITIVE)
    private val VIDEO_NAME = Pattern.compile("\\.(?:mp4|mov|webm|m4v|3gp)$", Pattern.CASE_INSENSITIVE)

    val menuViewId = View.generateViewId()

    enum class Kind { IMAGE, VIDEO }

    class Outgoing(val attachments: List<Attachment<*>>, val content: String)

    /** A decoded (possibly downscaled) image plus the size of the original. */
    class Loaded(val bitmap: Bitmap, val width: Int, val height: Int)

    // Decoded images, bounded by a fraction of the app heap. Memory only, nothing decrypted touches disk.
    private val cache = object : LruCache<String, Loaded>(
        Math.max(4 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 16).toInt()),
    ) {
        override fun sizeOf(key: String, value: Loaded): Int = value.bitmap.byteCount
    }
    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val waiting = HashMap<String, MutableList<(Loaded?) -> Unit>>()

    // Two workers at most, idle threads time out so nothing is held while the chat is quiet.
    private val pool = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
        .apply { allowCoreThreadTimeOut(true) }

    fun baseUrl(url: String): String {
        val query = url.indexOf('?')
        return if (query > 0) url.substring(0, query) else url
    }

    fun cached(base: String): Loaded? = cache.get(base)

    fun hasFailed(base: String): Boolean = failed.contains(base)

    /** Call whenever the key changes, so images are decrypted again with the new key. */
    fun onKeyChanged() {
        cache.evictAll()
        failed.clear()
    }

    /** Downloads and decrypts an image off the main thread. [done] runs on the main thread. */
    fun loadInline(url: String, base: String, done: (Loaded?) -> Unit) {
        val key = E2ee.key
        if (key == null) {
            done(null)
            return
        }
        synchronized(waiting) {
            val list = waiting[base]
            if (list != null) {
                list.add(done)
                return
            }
            waiting[base] = arrayListOf(done)
        }
        val queuedAt = System.nanoTime()
        pool.execute {
            val startedAt = System.nanoTime()
            val result = try {
                downloadImage(url, key)
            } catch (e: Throwable) {
                E2ee.onError(e)
                null
            }
            E2ee.log("inline wait=${ms(queuedAt, startedAt)}ms total=${ms(startedAt, System.nanoTime())}ms ok=${result != null}")
            if (result != null) cache.put(base, result) else failed.add(base)
            val callbacks = synchronized(waiting) { waiting.remove(base) }
            Utils.mainThread.post { callbacks?.forEach { it(result) } }
        }
    }

    fun encryptedKind(filename: String?): Kind? {
        if (filename == null || !ENC_MARKER.matcher(filename).find()) return null
        return when {
            IMAGE_NAME.matcher(filename).find() -> Kind.IMAGE
            VIDEO_NAME.matcher(filename).find() -> Kind.VIDEO
            else -> null
        }
    }

    // "photo.png" becomes "photo.enc.png"
    private fun encryptedName(original: String?): String {
        if (original == null || original.isEmpty()) return "file.enc"
        val dot = original.lastIndexOf('.')
        return if (dot > 0 && dot < original.length - 1) {
            original.substring(0, dot) + ".enc" + original.substring(dot)
        } else {
            original + ".enc"
        }
    }

    private fun sizeTag(context: Context, uri: Uri): String {
        var width = 0
        var height = 0
        val type = context.contentResolver.getType(uri)
        if (type != null && type.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (w != null && h != null) {
                    val swap = rotation == "90" || rotation == "270"
                    width = (if (swap) h else w).toInt()
                    height = (if (swap) w else h).toInt()
                }
            } catch (ignored: Exception) {
            } finally {
                retriever.release()
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            width = bounds.outWidth
            height = bounds.outHeight
        }
        return if (width > 0 && height > 0) "[sz:$width,$height]" else ""
    }

    private fun cacheDir(app: Context): File {
        val dir = File(app.cacheDir, "e2ee").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return dir
    }

    /** Encrypts every file and the caption. Throws on any failure so the caller can cancel the send. */
    fun encryptForSend(app: Context, key: SecretKey, originals: List<Attachment<*>>, caption: String): Outgoing {
        val plain = sizeTag(app, originals[0].uri) + caption
        if (plain.length > Crypto.MAX_PLAINTEXT_LEN) throw IOException("Caption too long")

        val dir = cacheDir(app)
        val created = ArrayList<File>()
        try {
            val result = ArrayList<Attachment<*>>()
            for (original in originals) {
                val file = File(dir, System.nanoTime().toString() + ".bin")
                created.add(file)
                val input = app.contentResolver.openInputStream(original.uri)
                    ?: throw IOException("Cannot read attachment")
                input.use { src ->
                    file.outputStream().use { dst ->
                        if (!Crypto.encryptStream(src, dst, key)) throw IOException("Encryption failed")
                    }
                }
                val local = LocalAttachment(
                    SnowflakeUtils.fromTimestamp(System.currentTimeMillis()),
                    Uri.fromFile(file).toString(),
                    encryptedName(original.displayName),
                )
                result.add(AttachmentUtilsKt.toAttachment(local))
            }
            val encrypted = Crypto.encrypt(plain, key) ?: throw IOException("Caption encryption failed")
            return Outgoing(result, encrypted)
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        }
    }

    fun menuEntry(ctx: Context, text: String, onClick: View.OnClickListener): View =
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            id = menuViewId
            setText(text)
            setOnClickListener(onClick)
            ContextCompat.getDrawable(ctx, R.e.ic_file_download_white_24dp)?.run {
                mutate()
                setTint(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
                setCompoundDrawablesRelativeWithIntrinsicBounds(this, null, null, null)
            }
        }

    fun showDecrypted(ctx: Context, url: String, kind: Kind, onError: (Throwable) -> Unit) {
        val key = E2ee.key
        if (key == null) {
            Utils.showToast("Set the password in plugin settings first.")
            return
        }
        Utils.showToast("Decrypting...")
        Utils.threadPool.execute {
            var bitmap: Bitmap? = null
            var video: File? = null
            try {
                if (kind == Kind.IMAGE) bitmap = downloadImage(url, key)?.bitmap else video = downloadVideo(ctx, url, key)
            } catch (e: Throwable) {
                onError(e)
            }
            val image = bitmap
            val file = video
            Utils.mainThread.post {
                when {
                    image != null -> showBitmap(ctx, image)
                    file != null -> playVideo(ctx, file)
                    else -> Utils.showToast("Could not decrypt this file.")
                }
            }
        }
    }

    private fun open(url: String): HttpURLConnection? {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        return conn
    }

    private fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000

    private fun downloadImage(url: String, key: SecretKey): Loaded? {
        val conn = open(url) ?: return null
        try {
            val t0 = System.nanoTime()
            if (conn.responseCode != 200) return null
            val raw = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raw.write(buf, 0, n)
                    if (raw.size() > MAX_IMAGE_BYTES + 28) return null
                }
            }
            val t1 = System.nanoTime()
            val plain = Crypto.decryptBytes(raw.toByteArray(), key) ?: return null
            val t2 = System.nanoTime()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(plain, 0, plain.size, options) ?: return null
            E2ee.log(
                "image ${raw.size() / 1024}KB ${bounds.outWidth}x${bounds.outHeight} sample=$sample " +
                    "net=${ms(t0, t1)}ms decrypt=${ms(t1, t2)}ms decode=${ms(t2, System.nanoTime())}ms",
            )
            return Loaded(bitmap, bounds.outWidth, bounds.outHeight)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadVideo(ctx: Context, url: String, key: SecretKey): File? {
        val base = baseUrl(url)
        val file = File(cacheDir(ctx), Math.abs(base.hashCode()).toString() + ".mp4")
        if (file.exists() && file.length() > 0) return file

        val conn = open(url) ?: return null
        try {
            if (conn.responseCode != 200) return null
            val ok = conn.inputStream.use { input ->
                file.outputStream().use { out -> Crypto.decryptStream(input, out, key) }
            }
            if (!ok || file.length() == 0L) {
                file.delete()
                return null
            }
            return file
        } finally {
            conn.disconnect()
        }
    }

    fun showBitmap(ctx: Context, bitmap: Bitmap) {
        val image = ImageView(ctx).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(image)
            image.setOnClickListener { dismiss() }
            show()
        }
    }

    private fun playVideo(ctx: Context, file: File) {
        val video = VideoView(ctx)
        video.setMediaController(MediaController(ctx).apply { setAnchorView(video) })
        video.setVideoURI(Uri.fromFile(file))
        video.setOnPreparedListener { it.start() }

        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(frame)
            setOnDismissListener { video.stopPlayback() }
            show()
        }
    }
}
