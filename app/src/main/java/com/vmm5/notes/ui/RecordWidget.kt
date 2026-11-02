package com.vmm5.notes.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.vmm5.notes.R

/** One-tap recording from the home screen: the widget opens the app on the Record screen and starts at once. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            views.setOnClickPendingIntent(R.id.widget_button, recordIntent(context))
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        const val ACTION_RECORD = "com.vmm5.notes.RECORD"
        fun recordIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 11,
            Intent(context, MainActivity::class.java).setAction(ACTION_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** The same from the Quick Settings shade. */
class RecordTile : TileService() {
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).setAction(RecordWidget.ACTION_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 12, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
