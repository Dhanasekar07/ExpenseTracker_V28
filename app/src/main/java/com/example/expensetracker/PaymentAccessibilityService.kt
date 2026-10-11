package com.example.expensetracker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional, opt-in service limited (see accessibility_service_config.xml) to the GPay and PhonePe
 * apps. It only reads the payment-success screen; texts are never stored or sent anywhere
 * (except the user-enabled, local-only capture mode).
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ExpenseTracker"
        private const val THROTTLE_MS = 400L
        private const val MAX_NODES = 200
        private const val MAX_DEPTH = 40
        private const val DEDUP_MS = 10 * 60 * 1000L

        private val WATCHED = setOf(PaymentScreenParser.PKG_GPAY, PaymentScreenParser.PKG_PHONEPE)
    }

    private val lastEventAt = HashMap<String, Long>()
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 32
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "PaymentAccessibilityService: connected")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val pkg = event?.packageName?.toString() ?: return
            if (pkg !in WATCHED) return
            val type = event.eventType
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

            val now = System.currentTimeMillis()
            val last = lastEventAt[pkg] ?: 0L
            if (now - last < THROTTLE_MS) return
            lastEventAt[pkg] = now

            val texts = readScreen(pkg)
            val capturing = PaymentCapture.isOn(this)
            if (texts.isEmpty()) {
                if (capturing) PaymentCapture.note(this, pkg, "event received but no readable text on screen")
                return
            }

            // GPay is logged on every screen (not only those with a rupee sign) while capture is on
            if (capturing) PaymentCapture.append(this, pkg, texts, always = pkg == PaymentScreenParser.PKG_GPAY)

            val parsed = PaymentScreenParser.parse(texts, now, pkg) ?: return
            handlePayment(parsed)
        } catch (t: Throwable) {
            Log.w(TAG, "PaymentAccessibilityService: event failed", t)
        }
    }

    private fun readScreen(pkg: String): List<String> {
        val out = ArrayList<String>()
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return out
            if (root.packageName?.toString() != pkg) return out
            val counter = intArrayOf(0)
            walk(root, 0, counter, out)
        } catch (t: Throwable) {
            // fall through with whatever was collected
        } finally {
            recycleSafely(root)
        }
        return out
    }

    /** Depth-first in visual child order. The caller owns (and recycles) [node]. */
    private fun walk(node: AccessibilityNodeInfo, depth: Int, counter: IntArray, out: MutableList<String>) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return
        counter[0]++
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out.add(text)
        if (desc.isNotEmpty() && desc != text) out.add(desc)

        val n = node.childCount
        for (i in 0 until n) {
            if (counter[0] >= MAX_NODES) break
            var child: AccessibilityNodeInfo? = null
            try {
                child = node.getChild(i)
                if (child != null) walk(child, depth + 1, counter, out)
            } catch (t: Throwable) {
                // skip broken child
            } finally {
                recycleSafely(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        try { node?.recycle() } catch (t: Throwable) { }
    }

    private fun handlePayment(p: ParsedPayment) {
        val now = System.currentTimeMillis()
        val key = "${p.app}|${p.amount}|${p.payee.lowercase()}|${p.screenTimeMillis}"
        val prev = seen[key]
        if (prev != null && now - prev < DEDUP_MS) return
        seen[key] = now

        val minAmount = AppPreferences.getMinAmount(this)
        val maxAmount = AppPreferences.getMaxAmount(this)
        if (p.amount < minAmount) return
        if (maxAmount > 0 && p.amount > maxAmount) return

        if (!PaymentPairing.onScreenSignal(this, p.amount, p.payee)) {
            Log.d(TAG, "PaymentAccessibilityService: already shown from SMS — skipping")
            return
        }

        val label = "${p.app} · ${p.payee}"
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra("amount", p.amount)
            putExtra("source", label)
            putExtra("snippet", label)
            putExtra("channel", "accessibility")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Throwable) {
            try { startService(intent) } catch (e2: Throwable) {
                Log.w(TAG, "PaymentAccessibilityService: could not start overlay", e2)
            }
        }
    }
}
