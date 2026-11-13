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
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.crypto.SecretKey
import com.discord.models.message.Message as ModelMessage

/**
 * Glue between Discord and the vendored Aegires MediaCache. Files are named "<random>.enc.<ext>" (the Aegires
 * rule) and the caption starts with a "[sz:W,H]" tag. Fetching, caching and decrypting is done by MediaCache.
 */
object Media {
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val ENC_MARKER = Pattern.compile("\\.enc(?=\\.|$)", Pattern.CASE_INSENSITIVE)
    private val VIDEO_NAME = Pattern.compile("\\.(?:mp4|mov|webm|m4v|3gp)$", Pattern.CASE_INSENSITIVE)

    // Same pattern as Aegires MessageCryptoHelper
    private val SIZE_TAG = Pattern.compile("^\\[sz:(\\d+),(\\d+)\\]")

    val menuViewId = View.generateViewId()

    enum class Kind { IMAGE, VIDEO }

    class Outgoing(val attachments: List<Attachment<*>>, val content: String)

    // Message id -> size from the tag, so the placeholder has the right ratio before the image arrives
    private val dims = LruCache<Long, IntArray>(500)

    // Files currently being decrypted for viewing, so repeated taps do not open the viewer several times
    private val opening: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun baseUrl(url: String): String {
        val query = url.indexOf('?')
        return if (query > 0) url.substring(0, query) else url
    }

    fun dimensions(messageId: Long): IntArray? = dims.get(messageId)

    // Aegires MessageCryptoHelper.extractAndCleanDimensions: remember the size, drop the tag from the text
    fun stripSizeTag(text: String, messageId: Long? = null): String {
        val m = SIZE_TAG.matcher(text)
        if (!m.find()) return text
        val w = m.group(1)?.toIntOrNull()
        val h = m.group(2)?.toIntOrNull()
        if (messageId != null && w != null && h != null) dims.put(messageId, intArrayOf(w, h))
        return text.substring(m.end())
    }

    fun spec(url: String, kind: Kind, key: SecretKey, channelId: Long, senderId: String?, w: Int, h: Int): MediaCache.Spec {
        val channel = channelId.toString()
        return if (kind == Kind.VIDEO) MediaCache.Spec.encryptedVideo(url, key, channel, senderId, -1L, w)
        else MediaCache.Spec.encryptedImage(url, key, channel, senderId, -1L, w, h)
    }

    /** Call whenever the key changes: MediaCache drops decrypted images, failures and plaintext temp files. */
    fun onKeyChanged() {
        val ctx = appContext ?: return
        MediaCache.get(ctx).clearSensitive()
    }

    // Same rule as Aegires AttachmentViewFactory: the marker decides, videos by extension, everything else is an image
    fun encryptedKind(filename: String?): Kind? {
        if (filename == null || !ENC_MARKER.matcher(filename).find()) return null
        return if (VIDEO_NAME.matcher(filename).find()) Kind.VIDEO else Kind.IMAGE
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

    /** Null means nothing is sent and the user was told why. Throws on I/O errors so the caller can cancel the send. */
    fun encryptForSend(app: Context, channelId: Long, originals: List<Attachment<*>>, caption: String): Outgoing? {
        // Caption first: same checks and toasts as text messages, before any file is processed
        val content = E2ee.encryptText(channelId, sizeTag(app, originals[0].uri) + caption) ?: return null
        val key = E2ee.keyFor(channelId) ?: return null
        val sender = E2ee.selfId() ?: return null
        val preset = E2ee.presetFor(channelId)
        val channel = channelId.toString()

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
                        if (!Crypto.encryptStream(src, dst, key, preset, channel, sender)) throw IOException("Encryption failed")
                    }
                }
                val name = encryptedName(FileNames.randomFor(original.displayName, app.contentResolver.getType(original.uri)))
                val local = LocalAttachment(
                    SnowflakeUtils.fromTimestamp(System.currentTimeMillis()),
                    Uri.fromFile(file).toString(),
                    name,
                )
                result.add(AttachmentUtilsKt.toAttachment(local))
            }
            return Outgoing(result, content)
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

    fun showDecrypted(ctx: Context, url: String, kind: Kind, message: ModelMessage, onError: (Throwable) -> Unit) {
        val channelId = message.channelId
        val key = E2ee.keyFor(channelId)
        if (key == null) {
            Utils.showToast(if (E2ee.isForced(channelId)) "Encryption key not ready yet" else "Set the password in plugin settings first.")
            return
        }
        val base = baseUrl(url)
        if (!opening.add(base)) return
        Utils.showToast("Decrypting...")
        val metrics = ctx.resources.displayMetrics
        val spec = spec(url, kind, key, channelId, message.author?.id?.toString(), metrics.widthPixels, metrics.heightPixels)
        spec.force = true
        val cache = MediaCache.get(ctx.applicationContext)
        if (kind == Kind.IMAGE) {
            cache.loadBitmap(spec, object : MediaCache.SimpleCallback() {
                override fun onBitmap(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int) {
                    opening.remove(base)
                    showBitmap(ctx, bitmap)
                }

                override fun onFailure(failure: MediaCache.Failure) {
                    opening.remove(base)
                    Utils.showToast("Could not decrypt this file.")
                    onError(IOException("Image decrypt failed: ${failure.kind}"))
                }
            })
        } else {
            cache.decryptToTemp(spec, object : MediaCache.TempCallback {
                override fun onProgress(done: Long, total: Long) {}

                override fun onNeedsConfirm(bytes: Long) {}

                override fun onFile(plaintext: File) {
                    opening.remove(base)
                    playVideo(ctx, plaintext)
                }

                override fun onFailure(failure: MediaCache.Failure) {
                    opening.remove(base)
                    Utils.showToast("Could not decrypt this file.")
                    onError(IOException("Video decrypt failed: ${failure.kind}"))
                }
            })
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

    // The plaintext temp file is deleted by MediaCache when the player closes, as in Aegires
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
            setOnDismissListener {
                video.stopPlayback()
                MediaCache.deleteTemp(file)
            }
            show()
        }
    }
}
