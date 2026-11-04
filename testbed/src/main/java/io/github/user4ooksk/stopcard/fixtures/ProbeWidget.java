package io.github.user4ooksk.stopcard.fixtures;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

/** A disposable widget for native role, broadcast and launcher PendingIntent checks. */
public class ProbeWidget extends AppWidgetProvider {
    @Override public void onReceive(Context context, Intent intent) {
        Log.i("StopCardFixture", context.getPackageName() + " Widget " + intent.getAction() + " @" + SystemClock.elapsedRealtime());
        super.onReceive(context, intent);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids, "initial");
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            int[] current = manager.getAppWidgetIds(new ComponentName(app, ProbeWidget.class));
            update(app, manager, current, "automatic");
        }, 5000);
    }

    private void update(Context context, AppWidgetManager manager, int[] ids, String source) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.probe_widget);
            views.setTextViewText(R.id.widget_title, "Probe widget " + source + " " + SystemClock.elapsedRealtime());
            views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, id,
                new Intent(context, ProbeActivity.class).setAction(Intent.ACTION_VIEW),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(id, views);
            Log.i("StopCardFixture", context.getPackageName() + " Widget refreshed " + source + " id=" + id + " @" + SystemClock.elapsedRealtime());
        }
    }
}
