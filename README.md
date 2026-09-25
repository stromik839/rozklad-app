# Розклад — Android-приложение

Звонки и уроки 5–11 классов на 2026–2027 н. р. Интерфейс на украинском.
Разрешений не нужно: время берётся из телефона, фото для обоев выбирается
через системный выбор фото.

## Настройки (шестерёнка)

- **Клас** — свой класс.
- **Тема** — как в системе / белая / чёрная.
- **Шпалери** — фото из галереи. По умолчанию без затемнения; ползунки
  «Затемнення фото» и «Прозорість таблиць».
- **Кольори** — отдельно для белой и чёрной темы: фон, текст, рамки,
  подсветка «зараз» и «далі». 20 готовых цветов + «Інший колір», есть сброс.

## Первый раз: залить на GitHub (Arch)

```bash
sudo pacman -S --needed git github-cli
gh auth login                      # один раз: GitHub.com → HTTPS → Login with a web browser
cd ~/Downloads && unzip Rozklad-android.zip && cd Rozklad-android
git init && git add . && git commit -m "Розклад"
gh repo create rozklad --public --source=. --push
```

Если git спросит «Please tell me who you are»:
```bash
git config --global user.name "Имя"
git config --global user.email "почта@example.com"
```

Без `gh` (репозиторий `rozklad` создан на сайте пустым, есть SSH-ключ):
```bash
git branch -M main
git remote add origin git@github.com:НИК/rozklad.git
git push -u origin main
```

Дальше: вкладка **Actions** (сборка 3–5 минут) → **Releases** → `Rozklad.apk`.

## Обновить (пришёл новый архив)

```bash
cd ~/Downloads && unzip -o Rozklad-android.zip
cd Rozklad-android
git add -A && git commit -m "Оновлення" && git push
```

Новая сборка появится в Releases и встанет поверх старой, настройки сохранятся.

## Где менять расписание

`app/src/main/assets/index.html`, блок «ДАНІ» в начале скрипта:
звонки (`BELLS`), семестры и каникулы (`TERMS`, `HOLIDAYS`), уроки (`SCHEDULE`).
Проверить в браузере: `index.html?now=2026-09-30T10:42&cls=7-А`.

Время 8-го урока (14:55–15:40) на листке не указано — поставлено по аналогии.

## Ключ подписи

`app/rozklad.p12`, пароль `rozklad2026`. Один на все сборки, чтобы обновления
ставились поверх. В публичном репозитории ключ виден всем — для школьного
расписания это не страшно, но можно сделать репозиторий приватным.

## Шрифт

Liberation Serif Bold (аналог Times New Roman), SIL OFL 1.1 —
`app/src/main/assets/fonts/LICENSE-LiberationSerif.txt`.
