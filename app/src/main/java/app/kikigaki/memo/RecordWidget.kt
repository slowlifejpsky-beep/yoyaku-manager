package app.kikigaki.memo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews

/**
 * ホーム画面のウィジェット(1マスの丸いボタン)。
 * 待機中は押すと録音開始、録音中は押すと停止して保存。
 * 録音開始だけはアプリの画面を経由する(Androidの決まりで、マイクを使う常駐は
 * 画面が表に出ている状態からしか始められないため)。
 * 状態ごとに別のレイアウトを丸ごと差し替えるので、ホーム画面側に古い表示指示が残っても崩れない。
 */
class RecordWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // アプリを更新したら、置いてあるウィジェットを新しい作りで描き直す
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
                if (ids.isNotEmpty()) push(context, manager, ids)
            }
        }

        private fun push(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = runCatching { build(context) }.getOrElse { idle(context) }
            ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
        }

        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private fun openApp(context: Context, action: String?, code: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action != null) intent.action = action
            return PendingIntent.getActivity(context, code, intent, FLAGS)
        }

        private fun idle(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_idle).apply {
                setOnClickPendingIntent(R.id.widget_root, openApp(context, MainActivity.ACTION_QUICK_RECORD, 10))
            }

        private fun build(context: Context): RemoteViews {
            val state = Live.state.value
            return when (state.phase) {
                Phase.IDLE -> idle(context)
                Phase.RECORDING -> RemoteViews(context.packageName, R.layout.widget_recording).apply {
                    setChronometer(R.id.widget_time, SystemClock.elapsedRealtime() - state.elapsedMs, null, true)
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getService(
                            context, 11,
                            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
                            FLAGS,
                        ),
                    )
                }
                Phase.FINISHING -> RemoteViews(context.packageName, R.layout.widget_finishing).apply {
                    setOnClickPendingIntent(R.id.widget_root, openApp(context, null, 12))
                }
            }
        }
    }
}
