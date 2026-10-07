package ua.school.rozklad;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Спільна основа трьох віджетів (дзвінки, уроки, разом). Малювання йде у фоновому потоці. */
public class BaseWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, final int[] ids) {
        final Context app = context.getApplicationContext();
        final PendingResult done = goAsync();
        new Thread(() -> {
            try {
                WidgetUpdater.update(app, ids);
                WidgetUpdater.scheduleNext(app);
            } finally {
                done.finish();
            }
        }).start();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, final int id, Bundle newOptions) {
        // змінили розмір — перемальовуємо під новий
        final Context app = context.getApplicationContext();
        final PendingResult done = goAsync();
        new Thread(() -> {
            try {
                WidgetUpdater.update(app, new int[] {id});
            } finally {
                done.finish();
            }
        }).start();
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        WidgetConfig.delete(context, ids);
        Store.cleanupWidgetPhotos(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetUpdater.scheduleNext(context);   // якщо віджетів не лишилось — будильник знімається
    }
}
