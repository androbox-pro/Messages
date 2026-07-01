package org.fossify.messages.androbox

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoClicker : AccessibilityService() {

    companion object {
        private var instance: AutoClicker? = null
        private var pendingText: String? = null
        private var pendingIndex: Int = 1
        private var dumpCallback: ((String) -> Unit)? = null

        fun isEnabled(): Boolean = instance != null

        fun performClick(textWithIndex: String) {
            var text = textWithIndex
            var index = 1
            if (textWithIndex.contains(":")) {
                val parts = textWithIndex.split(":", limit = 2)
                if (parts.size == 2) {
                    try {
                        index = parts[0].toInt()
                        text = parts[1]
                    } catch (e: NumberFormatException) { }
                }
            }
            pendingIndex = index
            pendingText = text
            instance?.startSearching()
        }

        fun dumpScreen(callback: (String) -> Unit) {
            dumpCallback = callback
            instance?.performDump()
        }

        private fun clearPending() {
            pendingText = null
            pendingIndex = 1
            dumpCallback = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AutoClicker", "✅ Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun performDump() {
        val root = rootInActiveWindow
        if (root == null) {
            dumpCallback?.invoke("❌ No active window")
            dumpCallback = null
            return
        }
        val allTexts = StringBuilder()
        collectAllTexts(root, allTexts)
        root.recycle()
        dumpCallback?.invoke(allTexts.toString())
        dumpCallback = null
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        if (!text.isNullOrBlank()) sb.appendLine("TEXT: $text")
        if (!contentDesc.isNullOrBlank()) sb.appendLine("CONTENT_DESC: $contentDesc")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()
            val tooltip = node.tooltipText?.toString()
            if (!hint.isNullOrBlank()) sb.appendLine("HINT: $hint")
            if (!tooltip.isNullOrBlank()) sb.appendLine("TOOLTIP: $tooltip")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, sb)
            child.recycle()
        }
    }

    private fun startSearching() {
        val text = pendingText ?: return
        val targetIndex = pendingIndex
        val root = rootInActiveWindow
        if (root == null) {
            ToastHelper.showToast(applicationContext, "❌ No active window")
            clearPending()
            return
        }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(root, text, matches)
        if (matches.isEmpty()) {
            ToastHelper.showToast(applicationContext, "❌ Text '$text' not found")
            clearPending()
            root.recycle()
            return
        }

        val idx = if (targetIndex in 1..matches.size) targetIndex - 1 else 0
        val targetNode = matches[idx]
        performClick(targetNode)

        matches.forEach { it.recycle() }
        root.recycle()
        clearPending()
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, target: String, outList: MutableList<AccessibilityNodeInfo>) {
        val textsToCheck = mutableListOf<String?>(
            node.text?.toString(),
            node.contentDescription?.toString()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            textsToCheck.add(node.hintText?.toString())
            textsToCheck.add(node.tooltipText?.toString())
        }
        if (textsToCheck.any { it.equals(target, ignoreCase = true) }) {
            outList.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByText(child, target, outList)
            child.recycle()
        }
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val clickedText = node.text?.toString() ?: node.contentDescription?.toString() ?: "element"
            ToastHelper.showToast(applicationContext, "✅ Clicked on '$clickedText'")
            Log.d("AutoClicker", "Clicked on: $clickedText")
        } else {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ToastHelper.showToast(applicationContext, "✅ Clicked on parent")
                    parent.recycle()
                    return
                }
                val nextParent = parent.parent
                parent.recycle()
                parent = nextParent
            }
            ToastHelper.showToast(applicationContext, "⚠️ No clickable element for '${node.text}'")
        }
        node.recycle()
    }
}