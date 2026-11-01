package dev.dsh.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import dev.dsh.mobile.data.AppState

/** Publishes pinned sessions as dynamic launcher shortcuts (long-press the app icon). */
object SessionShortcuts {
    private const val MAX_SHORTCUTS = 4

    fun sync(context: Context, state: AppState) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val pinned = state.sessions
            .filter { it.id in state.pinnedSessionIds }
            .take(MAX_SHORTCUTS)
        val shortcuts = pinned.map { session ->
            val label = state.displayTitle(session).trim().take(40).ifBlank { "DSH session" }
            ShortcutInfo.Builder(context, "pinned:${session.id}")
                .setShortLabel(label)
                .setLongLabel("Open $label")
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse("dshmobile://session/${session.id}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                )
                .build()
        }
        runCatching { manager.dynamicShortcuts = shortcuts }
    }
}
