package ua.school.rozklad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Будильник на дзвоник, а також зміна часу чи часового поясу на телефоні:
 * перемальовує віджети (лише ті, де щось змінилось) і ставить наступний будильник.
 */
public class WidgetTickReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        WidgetUpdater.runInBackground(() -> WidgetUpdater.updateAll(app), goAsync());
    }
}
