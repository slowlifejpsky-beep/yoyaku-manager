package app.kikigaki.memo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * ホーム画面のウィジェット。待機中は押すと録音開始、録音中は押すと停止して保存。
 * 録音開始だけはアプリの画面を経由する(Androidの決まりで、マイクを使う常駐は
 * 画面が表に出ている状態からしか始められないため)。
 */
class RecordWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(context: Context): RemoteViews {
            val state = Live.state.value
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            when (state.phase) {
                Phase.IDLE -> {
                    views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg_idle)
                    views.setImageViewResource(R.id.widget_icon, R.drawable.ic_stat_mic)
                    views.setViewVisibility(R.id.widget_time, View.GONE)
                    views.setChronometer(R.id.widget_time, SystemClock.elapsedRealtime(), null, false)
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(
                            context, 10,
                            Intent(context, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_QUICK_RECORD)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            flags,
                        ),
                    )
                }
                Phase.RECORDING -> {
                    views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg_active)
                    views.setImageViewResource(R.id.widget_icon, R.drawable.ic_stat_stop)
                    views.setViewVisibility(R.id.widget_time, View.VISIBLE)
                    views.setChronometer(
                        R.id.widget_time, SystemClock.elapsedRealtime() - state.elapsedMs, null, true
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getService(
                            context, 11,
                            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
                            flags,
                        ),
                    )
                }
                Phase.FINISHING -> {
                    views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg_active)
                    views.setImageViewResource(R.id.widget_icon, R.drawable.ic_stat_stop)
                    views.setViewVisibility(R.id.widget_time, View.GONE)
                    views.setChronometer(R.id.widget_time, SystemClock.elapsedRealtime(), null, false)
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(
                            context, 12,
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            flags,
                        ),
                    )
                }
            }
            return views
        }
    }
}
