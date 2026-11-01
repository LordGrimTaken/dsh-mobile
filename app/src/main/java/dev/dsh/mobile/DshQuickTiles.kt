package dev.dsh.mobile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles that reuse the app's deep links, so they never own a second navigation
 * stack or a host connection. Users add them from the Quick Settings edit screen.
 */
abstract class DshQuickTileService(
    private val link: String,
    private val tileLabel: String,
) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = tileLabel
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { startActivityAndCollapse(pending) }
        } else {
            runCatching { startActivityAndCollapse(intent) }
        }
    }
}

/** One-tap new session. */
class NewSessionTileService : DshQuickTileService("dshmobile://new", "New DSH session")

/** One-tap usage/balance dashboard. */
class UsageTileService : DshQuickTileService("dshmobile://usage", "DSH usage")
