package dev.dsh.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.dsh.mobile.MainActivity
import dev.dsh.mobile.R

/**
 * Small launcher-style widget: open DSH, start a session, or jump straight to usage.
 *
 * All actions reuse the activity's dshmobile:// deep links, so there is no second navigation
 * stack to keep in sync and no background host connection to own (widgets must not keep SSE alive).
 */
class DshWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val status = readStatus(context)
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildViews(context, status))
        }
    }

    private fun buildViews(context: Context, status: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.dsh_widget)
        views.setTextViewText(R.id.widget_status, status)
        views.setOnClickPendingIntent(R.id.widget_root, deepLink(context, "dshmobile://new", 0))
        views.setOnClickPendingIntent(R.id.widget_new, deepLink(context, "dshmobile://new", 1))
        views.setOnClickPendingIntent(R.id.widget_usage, deepLink(context, "dshmobile://usage", 2))
        views.setOnClickPendingIntent(R.id.widget_inbox, deepLink(context, "dshmobile://inbox", 3))
        return views
    }

    companion object {
        private const val PREFS = "dsh-widget"
        private const val KEY_STATUS = "status"

        private fun deepLink(context: Context, uri: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(uri)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun readStatus(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATUS, null)
                ?: "Open the app to sync"

        /** Called by the app while it is alive so the widget reflects the latest session. */
        fun refresh(context: Context, title: String, status: String) {
            val label = if (title.isBlank()) "No recent session" else "$title · $status"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATUS, label)
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, DshWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.dsh_widget).apply {
                setTextViewText(R.id.widget_status, label)
                setOnClickPendingIntent(R.id.widget_root, deepLink(context, "dshmobile://new", 0))
                setOnClickPendingIntent(R.id.widget_new, deepLink(context, "dshmobile://new", 1))
                setOnClickPendingIntent(R.id.widget_usage, deepLink(context, "dshmobile://usage", 2))
                setOnClickPendingIntent(R.id.widget_inbox, deepLink(context, "dshmobile://inbox", 3))
            }
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }
    }
}
