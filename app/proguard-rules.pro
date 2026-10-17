# Захист коду: під час складання R8 стискає застосунок і перейменовує класи, методи й поля
# на короткі безглузді імена — декомпільований застосунок стає майже нечитабельним.
# Нижче — єдині місця, які чіпати НЕ можна, інакше застосунок зламається.

# Міст між сторінкою і застосунком: сторінка (JavaScript) викликає ці методи за їхніми іменами
# через window.Android.*, тож R8 не має їх перейменовувати.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ua.school.rozklad.MainActivity$Bridge { *; }

# Компоненти з AndroidManifest.xml (активність, три віджети, приймач зміни часу, провайдер файлів):
# система Android знаходить їх за іменем класу, тож імена лишаємо (решту всередині R8 ще обфускує).
-keep class ua.school.rozklad.MainActivity
-keep class ua.school.rozklad.BellsWidget
-keep class ua.school.rozklad.LessonsWidget
-keep class ua.school.rozklad.BothWidget
-keep class ua.school.rozklad.WidgetTickReceiver
-keep class ua.school.rozklad.ShareProvider

# Увесь код — свій, бібліотек немає, тож попереджень про відсутні класи не буде.
