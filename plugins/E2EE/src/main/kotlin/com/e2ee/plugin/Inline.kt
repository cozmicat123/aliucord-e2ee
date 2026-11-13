package com.e2ee.plugin

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.filename
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.url
import com.discord.api.message.attachment.MessageAttachment
import com.google.android.material.card.MaterialCardView
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import javax.crypto.SecretKey
import com.discord.models.message.Message as ModelMessage

/**
 * Shows encrypted images and videos in place of the generic file card. Layout and the list item hook follow
 * the AudioPlayer plugin: an extra view is added to the attachment card and removed again when the
 * recycled item is bound to something else. Loading, consent and failure handling follow Aegires'
 * EncryptedMediaSlot; the work itself is done by MediaCache.
 */
object Inline {
    private enum class Phase { LOADING, CONFIRM, FAILED, LOADED, TOO_LARGE }

    private class State(val base: String, val originalHeight: Int) {
        var phase = Phase.LOADING
        var load: MediaCache.Handle = MediaCache.NOOP
    }

    private val overlayId = View.generateViewId()
    private val cardId by lazy { Utils.getResId("chat_list_item_attachment_card", "id") }
    private val overlayColor = Color.parseColor("#1E1F22")
    private val muted = Color.parseColor("#B5BAC1")

    // Files the user tapped to download; bounded like Aegires' approvedMedia
    private val approved: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 500
            },
        ),
    )

    fun bind(root: ConstraintLayout, attachment: MessageAttachment, message: ModelMessage, enabled: Boolean) {
        val card = root.findViewById<MaterialCardView>(cardId) ?: return
        val existing = card.findViewById<View>(overlayId)
        val kind = Media.encryptedKind(attachment.filename)
        val key = if (kind != null) E2ee.keyFor(message.channelId) else null

        if (!enabled || kind == null || key == null) {
            if (existing != null) remove(card, existing)
            return
        }

        val url = attachment.url
        val base = Media.baseUrl(url)
        if (existing != null) {
            if ((existing.tag as? State)?.base == base) return
            remove(card, existing)
        }
        add(card, kind, url, base, key, message)
    }

    private fun remove(card: MaterialCardView, overlay: View) {
        card.removeView(overlay)
        (overlay.tag as? State)?.let {
            it.load.cancel()
            setHeight(card, it.originalHeight)
        }
    }

    private fun add(card: MaterialCardView, kind: Media.Kind, url: String, base: String, key: SecretKey, message: ModelMessage) {
        val ctx = card.context
        val params = card.layoutParams ?: return
        val state = State(base, params.height)
        val video = kind == Media.Kind.VIDEO
        val channelId = message.channelId
        val sender = message.author?.id?.toString()

        val overlay = FrameLayout(ctx).apply {
            id = overlayId
            tag = state
            setBackgroundColor(overlayColor)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            // A clickable child swallows long presses, so hand them to the message so its menu still opens
            isLongClickable = true
            setOnLongClickListener { forwardLongPress(it) }
        }
        val image = ImageView(ctx).apply {
            scaleType = if (video) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val status = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(muted)
            textSize = 13f
        }
        val play = TextView(ctx).apply {
            text = "\u25B6"
            setTextColor(Color.WHITE)
            textSize = 30f
            visibility = View.GONE
        }
        overlay.addView(image, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(status, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        overlay.addView(play, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        card.addView(overlay)

        // The size tag in the caption gives the ratio before anything is downloaded
        val dim = Media.dimensions(message.id)
        val height = if (dim != null) heightFor(card, dim[0], dim[1]) else defaultHeight(card)
        setHeight(card, height)

        // The item may have been recycled for another attachment while MediaCache was working
        fun live() = (overlay.tag as? State) === state && overlay.parent === card

        fun start(force: Boolean) {
            state.load.cancel()
            state.phase = Phase.LOADING
            status.visibility = View.VISIBLE
            status.text = if (force) "\uD83D\uDD12 Downloading\u2026" else "\uD83D\uDD12 Loading\u2026"
            val spec = Media.spec(url, kind, key, channelId, sender, Math.max(availableWidth(card), 120.dp), Math.max(height, 120.dp))
            spec.force = force
            val callback = object : MediaCache.Callback {
                override fun onProgress(done: Long, total: Long) {
                    if (!live() || state.phase != Phase.LOADING) return
                    status.text = "\uD83D\uDD12 Downloading\u2026 " + (if (total > 0) "${done * 100 / total}%" else fmtBytes(done))
                }

                override fun onNeedsConfirm(bytes: Long) {
                    if (!live()) return
                    state.phase = Phase.CONFIRM
                    status.text = "\uD83D\uDD12 " + (if (video) "Video" else "Image") +
                        (if (bytes > 0) " \u00B7 " + fmtBytes(bytes) else "") + "\nTap to download"
                }

                override fun onBitmap(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int) {
                    if (!live()) return
                    state.phase = Phase.LOADED
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    status.visibility = View.GONE
                    if (video) play.visibility = View.VISIBLE
                    setHeight(card, heightFor(card, sourceWidth, sourceHeight))
                }

                override fun onFailure(failure: MediaCache.Failure) {
                    if (live()) fail(state, status, failure, video)
                }
            }
            val cache = MediaCache.get(ctx)
            state.load = if (video) cache.loadVideoThumb(spec, callback) else cache.loadBitmap(spec, callback)
        }

        overlay.setOnClickListener {
            when (state.phase) {
                Phase.LOADED -> Media.showDecrypted(ctx, url, kind, message) { E2ee.onError(it) }
                Phase.CONFIRM, Phase.FAILED -> {
                    approved.add(base)
                    start(true)
                }
                else -> Unit
            }
        }
        start(approved.contains(base))
    }

    // Same messages as Aegires EncryptedMediaSlot.fail
    private fun fail(state: State, status: TextView, f: MediaCache.Failure, video: Boolean) {
        state.phase = Phase.FAILED
        status.visibility = View.VISIBLE
        status.text = when (f.kind) {
            MediaCache.Failure.Kind.DECRYPT -> "\u26A0 Can't decrypt (wrong key?)\nTap to retry"
            MediaCache.Failure.Kind.DECODE -> {
                state.phase = Phase.TOO_LARGE
                "\u26A0 Can't display this " + (if (video) "video" else "image")
            }
            MediaCache.Failure.Kind.TOO_LARGE -> {
                state.phase = Phase.TOO_LARGE
                "\u26A0 Too large (" + fmtBytes(f.bytes) + ")"
            }
            MediaCache.Failure.Kind.HTTP ->
                "\u26A0 " + (if (f.httpCode == 403 || f.httpCode == 404) "Expired or deleted" else "Download failed") + "\nTap to retry"
            else -> "\u26A0 Download failed\nTap to retry"
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
    }

    private fun setHeight(card: View, height: Int) {
        val params = card.layoutParams ?: return
        if (params.height == height) return
        params.height = height
        card.layoutParams = params
    }

    private fun availableWidth(card: View): Int {
        if (card.width > 0) return card.width
        return Math.max(card.resources.displayMetrics.widthPixels - 112.dp, 120.dp)
    }

    private fun defaultHeight(card: View): Int = Math.min(availableWidth(card) * 9 / 16, 400.dp)

    // Same sizing rule as Aegires: fit the width, cap the height at 400dp, never scale up past 2x
    private fun heightFor(card: View, w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return defaultHeight(card)
        val scale = Math.min(Math.min(availableWidth(card).toFloat() / w, 400.dp.toFloat() / h), 2f)
        return Math.max(1, Math.round(h * scale))
    }

    private fun forwardLongPress(view: View): Boolean {
        var parent = view.parent as? View
        while (parent != null) {
            if (parent.isLongClickable && parent.performLongClick()) return true
            parent = parent.parent as? View
        }
        return false
    }
}
