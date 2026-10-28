package com.e2ee.plugin

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.CommandContext
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import com.lytefast.flexinput.model.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import javax.crypto.SecretKey

/**
 * Encrypted file support. Wire format: the file is `IV(12) || AES-GCM`, the name carries
 * a ".enc" marker before the extension and the caption starts with a "[sz:W,H]" tag.
 */
object Images {
    private const val MAX_IMAGE_BYTES = 50 * 1024 * 1024
    private const val MAX_SIDE = 2048
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    private val IMAGE_NAME = Pattern.compile("\\.(?:png|jpe?g|gif|webp|bmp)$", Pattern.CASE_INSENSITIVE)
    private val ENC_MARKER = Pattern.compile("\\.enc(?=\\.|$)", Pattern.CASE_INSENSITIVE)

    val menuViewId = View.generateViewId()

    fun isEncryptedImage(filename: String?): Boolean =
        filename != null && ENC_MARKER.matcher(filename).find() && IMAGE_NAME.matcher(filename).find()

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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) "[sz:${bounds.outWidth},${bounds.outHeight}]" else ""
    }

    private fun pruneCache(dir: File) {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Replaces the attached files with encrypted copies and returns the encrypted caption. Fails closed. */
    fun encryptAttachments(ctx: CommandContext, app: Context, onError: (Throwable) -> Unit): CommandsAPI.CommandResult {
        fun fail(message: String) = CommandsAPI.CommandResult(message, null, false)

        val key = E2ee.key ?: return fail("Set the password in plugin settings first.")
        val originals = ArrayList<Attachment<*>>(ctx.attachments)
        if (originals.isEmpty()) return fail("Attach a file first, then run /e2eeimage.")

        val caption = ctx.getStringOrDefault("message", "").trim()
        val dir = File(app.cacheDir, "e2ee").apply { mkdirs() }
        pruneCache(dir)
        val created = ArrayList<File>()
        try {
            val plain = sizeTag(app, originals[0].uri) + caption
            if (plain.length > Crypto.MAX_PLAINTEXT_LEN) return fail("Caption too long.")

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
                ctx.addAttachment(Uri.fromFile(file).toString(), encryptedName(original.displayName))
            }

            ctx.attachments.removeAll(originals)
            if (ctx.attachments.any { originals.contains(it) }) throw IOException("Original attachment still present")

            val encrypted = Crypto.encrypt(plain, key) ?: throw IOException("Caption encryption failed")
            return CommandsAPI.CommandResult(encrypted, null, true)
        } catch (e: Throwable) {
            onError(e)
            created.forEach { it.delete() }
            return fail("Encryption failed, nothing was sent.")
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

    fun showDecrypted(ctx: Context, url: String, onError: (Throwable) -> Unit) {
        val key = E2ee.key
        if (key == null) {
            Utils.showToast("Set the password in plugin settings first.")
            return
        }
        Utils.showToast("Decrypting...")
        Utils.threadPool.execute {
            val bitmap = try {
                download(url, key)
            } catch (e: Throwable) {
                onError(e)
                null
            }
            Utils.mainThread.post {
                if (bitmap == null) Utils.showToast("Could not decrypt this image.") else show(ctx, bitmap)
            }
        }
    }

    private fun download(url: String, key: SecretKey): Bitmap? {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
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

            val plain = Crypto.decryptBytes(raw.toByteArray(), key) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(plain, 0, plain.size, options)
        } finally {
            conn.disconnect()
        }
    }

    private fun show(ctx: Context, bitmap: Bitmap) {
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
}
