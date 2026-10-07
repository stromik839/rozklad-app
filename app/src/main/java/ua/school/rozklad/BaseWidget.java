package ua.school.rozklad;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/**
 * Спільна основа трьох віджетів (дзвінки, уроки, разом).
 * Уся робота — у спільному фоновому потоці по черзі: так нове оновлення (наприклад, після зміни
 * розміру) ніколи не перекриється старішим, що домалювалось пізніше.
 */
public class BaseWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, final int[] ids) {
        final Context app = context.getApplicationContext();
        WidgetUpdater.runInBackground(() -> {
            WidgetUpdater.update(app, ids);
            WidgetUpdater.scheduleNext(app);
        }, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, final int id, Bundle newOptions) {
        // змінили розмір — перемальовуємо під новий
        final Context app = context.getApplicationContext();
        WidgetUpdater.runInBackground(() -> WidgetUpdater.update(app, new int[] {id}), goAsync());
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        final Context app = context.getApplicationContext();
        WidgetConfig.delete(app, ids);
        WidgetUpdater.forget(ids);
        WidgetUpdater.runInBackground(() -> Store.cleanupWidgetPhotos(app), goAsync());
    }

    @Override
    public void onDisabled(Context context) {
        // віджетів не лишилось — будильник знімається
        final Context app = context.getApplicationContext();
        WidgetUpdater.runInBackground(() -> WidgetUpdater.scheduleNext(app), goAsync());
    }
}
