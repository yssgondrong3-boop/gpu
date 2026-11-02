package com.gpudash.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale
import kotlin.math.roundToInt

class DashWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val last = History.list(ctx).lastOrNull()
            rv.setTextViewText(R.id.w_label, "Skor rata-rata terakhir")
            rv.setTextViewText(R.id.w_score, last?.let { String.format(Locale.US, "%,d", it.avg) } ?: "--")
            rv.setTextViewText(R.id.w_info,
                "${Sensors.cpuTemp(ctx).roundToInt()}°C" + (last?.let { " · ${it.fAvg} FPS" } ?: ""))
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            return rv
        }

        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            for (id in m.getAppWidgetIds(ComponentName(ctx, DashWidget::class.java))) m.updateAppWidget(id, build(ctx))
        }
    }
}
