package com.e2ee.plugin

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

/**
 * Shows encrypted images in place of the generic file card. Layout and the list item hook follow
 * the AudioPlayer plugin: an extra view is added to the attachment card and removed again when the
 * recycled item is bound to something else.
 */
object Inline {
    private class State(val base: String, val originalHeight: Int)

    private val overlayId = View.generateViewId()
    private val cardId by lazy { Utils.getResId("chat_list_item_attachment_card", "id") }
    private val background = Color.parseColor("#1E1F22")
    private val muted = Color.parseColor("#B5BAC1")

    fun bind(root: ConstraintLayout, attachment: MessageAttachment, enabled: Boolean) {
        val card = root.findViewById<MaterialCardView>(cardId) ?: return
        val existing = card.findViewById<View>(overlayId)
        val kind = Media.encryptedKind(attachment.filename)

        if (!enabled || kind == null || E2ee.key == null) {
            if (existing != null) remove(card, existing)
            return
        }

        val url = attachment.url
        val base = Media.baseUrl(url)
        if (existing != null) {
            if ((existing.tag as? State)?.base == base) return
            remove(card, existing)
        }
        add(card, kind, url, base)
    }

    private fun remove(card: MaterialCardView, overlay: View) {
        card.removeView(overlay)
        (overlay.tag as? State)?.let { setHeight(card, it.originalHeight) }
    }

    private fun add(card: MaterialCardView, kind: Media.Kind, url: String, base: String) {
        val ctx = card.context
        val params = card.layoutParams ?: return

        val overlay = FrameLayout(ctx).apply {
            id = overlayId
            tag = State(base, params.height)
            setBackgroundColor(background)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            // A clickable child swallows long presses, so hand them to the message so its menu still opens
            isLongClickable = true
            setOnLongClickListener { forwardLongPress(it) }
        }
        val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val status = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(muted)
            textSize = 13f
        }
        overlay.addView(image, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(status, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        card.addView(overlay)
        setHeight(card, defaultHeight(card))

        // Videos can be large, so they are only downloaded when tapped
        if (kind == Media.Kind.VIDEO) {
            status.text = "\uD83D\uDD12 Encrypted video, tap to play"
            overlay.setOnClickListener { Media.showDecrypted(ctx, url, kind) { E2ee.onError(it) } }
            return
        }

        val finish: (Media.Loaded?) -> Unit = { loaded ->
            // The item may have been recycled for another attachment while the image was loading
            if ((overlay.tag as? State)?.base == base && overlay.parent === card) {
                if (loaded == null) {
                    status.text = "\u26A0 Decrypt failed"
                } else {
                    image.setImageBitmap(loaded.bitmap)
                    image.visibility = View.VISIBLE
                    status.visibility = View.GONE
                    setHeight(card, heightFor(card, loaded.width, loaded.height))
                    overlay.setOnClickListener { Media.showBitmap(ctx, loaded.bitmap) }
                }
            }
        }

        val cached = Media.cached(base)
        when {
            cached != null -> finish(cached)
            Media.hasFailed(base) -> finish(null)
            else -> {
                status.text = "\uD83D\uDD12 Decrypting\u2026"
                Media.loadInline(url, base, finish)
            }
        }
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
