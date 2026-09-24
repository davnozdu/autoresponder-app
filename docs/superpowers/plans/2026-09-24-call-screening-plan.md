# Скрининг звонков — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** В настроенное «рабочее время» звонок не от избранного отвечается автоматически, абоненту играет приветствие на выбранном языке + повторяющийся зуммер, разговор пишется с самого начала, а владелец видит яркую интерактивную карточку с кнопками «Принять» (подключиться к уже идущему звонку без повторного набора) и «Отклонить».

**Architecture:** Новая независимая ветка маршрутизации в `CallScreeningServiceImpl` (между ЧС и гарнитурой — скрининг важнее гарнитуры). Новый чистый модуль `ScreeningPolicy` (расписание + skip, зеркалит `HeadsetPolicy`/`ClosedState`). Новая ветка потока `AnswerMachineService.runScreeningFlow` (параллельно существующему `runFlow`, переиспользует общие приватные хелперы `answerCall`/`endCall`/`waitIdleOr`). `CallerOverlay` (уже существующая видимая карточка над звонилкой) получает опциональные кнопки Принять/Отклонить, сигнализирующие в текущий экземпляр сервиса через простые `@Volatile`-флаги — тот же паттерн, что уже используют `idle`/`offhook`.

**Tech Stack:** Kotlin, Android (Jetpack Compose UI в `MainActivity`, классический `View`/`WindowManager` в `CallerOverlay`), JUnit4 для чистой логики (`app/src/test`).

**Spec:** `docs/superpowers/specs/2026-09-24-call-screening-design.md`

## Global Constraints

- Никаких новых Android-разрешений — всё строится на уже выданных (`SYSTEM_ALERT_WINDOW`, `ANSWER_PHONE_CALLS`, `MODIFY_AUDIO_SETTINGS`).
- Избранные (`SkipPolicy`) НИКОГДА не получают интерактивный скрининг — тот же `skip`, что уже вычисляется в `CallScreeningServiceImpl`, переиспользуется, не дублируется.
- Скрининг проверяется РАНЬШЕ гарнитуры в маршрутизации (подтверждено пользователем при ревью спеки) — порядок: ЧС → скрининг → гарнитура → закрыто → открыто.
- Мьют владельца — только `ADJUST_MUTE`/`ADJUST_UNMUTE` (флаг, не привязан к аудио-маршруту) — НИКОГДА не возвращаться к явному индексу громкости (`setStreamVolume` с числом) — это баг, пофикшенный сегодня для обычного автоответчика (см. `AnswerMachineService.kt`, коммит про bt_sco).
- Каждый новый чистый модуль (`ScreeningPolicy`) — без `Context` в тестируемой части, зеркалит существующий паттерн (`ClosedState.closedBySchedule`, `HeadsetPolicy.shouldForceAnswer`).
- Язык скрининга по умолчанию — **чешский** (`"cs"`) для ВСЕХ звонков, не автоопределение.

## Review Focus

- **Пустое окно расписания скрининга** (`screeningStartMin == screeningEndMin`) — по зеркалируемой логике (`nowMin in start until end`) даёт ПУСТОЙ диапазон → скрининг никогда не активен. Это тихий отказ фичи целиком, если пользователь случайно оставит время по умолчанию нетронутым не в том порядке — тест должен это зафиксировать явно, не как случайность.
- **Избранный номер даже при активном расписании скрининга не должен получать интерактивную карточку** — легко перепутать `skip`/`!skip` местами при добавлении булева И (как в `HeadsetPolicy`, но здесь новая функция — своя ошибка возможна независимо).
- **И «Принять», и «Отклонить» нажаты (или отклик задвоен)** — гонка при двойном тапе по кнопке: обработчик должен убирать карточку СРАЗУ по первому тапу, чтобы второй тап физически не по чему бить.
- **Нулевая/отрицательная максимальная длительность зуммера** (`amMaxMessageSec` испорчен пользователем через настройки, напр. 0) — генератор повторов не должен зависнуть или вернуть пустой/некорректный PCM — минимум один повтор всегда.
- **Владелец не нажал ничего, абонент сам положил трубку раньше `amMaxMessageSec`** — цикл ожидания скрининга должен выходить по `idle`, как и обычный поток, не ждать полный таймаут вслепую.

---

## Task 1: `ScreeningPolicy` — расписание и решение о скрининге

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt:85` (после `scheduleEndMin`, перед `// --- маска стран`)
- Create: `app/src/main/java/com/davnozdu/autoresponder/rules/ScreeningPolicy.kt`
- Modify: `app/src/test/java/com/davnozdu/autoresponder/RulesTest.kt:1-13` (импорты) и добавить новый класс теста после `ScheduleTest` (после строки 49)

**Interfaces:**
- Produces: `ScreeningPolicy.shouldScreen(enabled: Boolean, inWindow: Boolean, skip: Boolean): Boolean`, `ScreeningPolicy.activeBySchedule(nowMin: Int, dayOfWeek: Int, workDaysMask: Int, workStart: Int, workEnd: Int): Boolean`, `ScreeningPolicy.isInWindow(context: Context, s: Settings): Boolean`. Также `Settings.screeningEnabled/screeningWorkDaysMask/screeningStartMin/screeningEndMin`.
- Consumes: ничего нового (только существующий `android.content.Context`, `java.util.Calendar`).

- [ ] **Step 1: Добавить новые поля в `Settings.kt`**

В `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt` сразу после блока (строка 85, `scheduleEndMin`) вставить:

```kotlin
    // --- скрининг звонков: своё расписание, независимое от расписания «закрыто» ---
    /** Включён ли интерактивный скрининг (карточка «Принять»/«Отклонить» в рабочее время). */
    var screeningEnabled: Boolean
        get() = sp.getBoolean(K_SCREEN_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_SCREEN_ENABLED, v).apply()

    /** Битовая маска дней скрининга (Calendar.DAY_OF_WEEK 1..7), по умолчанию Пн-Пт = 124. */
    var screeningWorkDaysMask: Int
        get() = sp.getInt(K_SCREEN_DAYS, 124)
        set(v) = sp.edit().putInt(K_SCREEN_DAYS, v).apply()

    var screeningStartMin: Int
        get() = sp.getInt(K_SCREEN_START, 9 * 60)
        set(v) = sp.edit().putInt(K_SCREEN_START, v).apply()

    var screeningEndMin: Int
        get() = sp.getInt(K_SCREEN_END, 18 * 60)
        set(v) = sp.edit().putInt(K_SCREEN_END, v).apply()
```

И в блок `private const val K_*` (там же, рядом с `K_HEADSET_FORCE_ANSWER`, которую добавили сегодня) добавить:

```kotlin
        private const val K_SCREEN_ENABLED = "screen_enabled"
        private const val K_SCREEN_DAYS = "screen_days"
        private const val K_SCREEN_START = "screen_start"
        private const val K_SCREEN_END = "screen_end"
```

- [ ] **Step 2: Написать падающий тест**

Создать `app/src/test/java/com/davnozdu/autoresponder/RulesTest.kt` — добавить импорт `import com.davnozdu.autoresponder.rules.ScreeningPolicy` в блок импортов (после `import com.davnozdu.autoresponder.rules.LangDetect` на строке 6), и новый класс ПОСЛЕ `class ScheduleTest { ... }` (после строки 49, перед `class SegmentBudgetTest`):

```kotlin
class ScreeningPolicyTest {

    private fun active(now: Int, dow: Int, days: Int = 124, start: Int = 9 * 60, end: Int = 18 * 60) =
        ScreeningPolicy.activeBySchedule(now, dow, days, start, end)

    @Test fun `рабочее время активно, вне его — нет`() {
        assertTrue(active(10 * 60, 3))            // вторник 10:00 — внутри 9-18
        assertFalse(active(20 * 60, 3))           // вторник 20:00 — вне окна
        assertFalse(active(8 * 60, 3))            // до открытия
        assertTrue(active(9 * 60, 3))             // граница начала включительно
        assertFalse(active(18 * 60, 3))           // граница конца — уже не активно
    }

    @Test fun `нерабочий день не активен даже в рабочие часы`() {
        assertFalse(active(10 * 60, 1))           // воскресенье
        assertFalse(active(10 * 60, 7))           // суббота
    }

    @Test fun `совпадающие границы расписания — скрининг никогда не активен`() {
        // Пустое окно [X, X) — намеренная тихая деградация: фича молча не работает,
        // если пользователь не поменял значения по умолчанию местами. См. Review Focus.
        assertFalse(active(12 * 60, 3, start = 9 * 60, end = 9 * 60))
        assertFalse(active(0, 3, start = 9 * 60, end = 9 * 60))
    }

    @Test fun `shouldScreen требует все три условия`() {
        assertTrue(ScreeningPolicy.shouldScreen(enabled = true, inWindow = true, skip = false))
        assertFalse(ScreeningPolicy.shouldScreen(enabled = false, inWindow = true, skip = false))
        assertFalse(ScreeningPolicy.shouldScreen(enabled = true, inWindow = false, skip = false))
        assertFalse(ScreeningPolicy.shouldScreen(enabled = true, inWindow = true, skip = true))
    }
}
```

- [ ] **Step 3: Запустить тест и убедиться, что падает**

Run: `cd ~/Downloads/phonemodule/autoresponder-app && ./gradlew testDebugUnitTest --tests "*RulesTest*"` (если локального JDK нет — закоммитить и посмотреть на CI, см. Task 6 про сборку; предпочтительно проверить локально, если `java -version` работает)
Expected: FAIL — `Unresolved reference: ScreeningPolicy`

- [ ] **Step 4: Создать `ScreeningPolicy.kt`**

Создать `app/src/main/java/com/davnozdu/autoresponder/rules/ScreeningPolicy.kt`:

```kotlin
package com.davnozdu.autoresponder.rules

import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import java.util.Calendar

/** Решение «включать ли интерактивный скрининг для этого звонка» — своё расписание,
 *  независимое от расписания «закрыто» (см. [ClosedState]). Зеркалит структуру
 *  [ClosedState.closedBySchedule] (режим «рабочие часы/дни»), но с обратной полярностью:
 *  тут спрашиваем «активно ли», а не «закрыто ли». */
object ScreeningPolicy {

    fun shouldScreen(enabled: Boolean, inWindow: Boolean, skip: Boolean): Boolean =
        enabled && inWindow && !skip

    fun isInWindow(context: Context, s: Settings): Boolean {
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return activeBySchedule(cur, now.get(Calendar.DAY_OF_WEEK),
            s.screeningWorkDaysMask, s.screeningStartMin, s.screeningEndMin)
    }

    /** Чистая часть — ни Context, ни Settings, ни системных часов, только числа. Вынесено
     *  ради юнит-теста, как и у [ClosedState.closedBySchedule].
     *  @param dayOfWeek как в [Calendar.DAY_OF_WEEK] (вс = 1) */
    fun activeBySchedule(nowMin: Int, dayOfWeek: Int, workDaysMask: Int,
                          workStart: Int, workEnd: Int): Boolean {
        val isWorkDay = (workDaysMask and (1 shl dayOfWeek)) != 0
        val inHours = nowMin in workStart until workEnd
        return isWorkDay && inHours
    }
}
```

- [ ] **Step 5: Запустить тест и убедиться, что проходит**

Run: `./gradlew testDebugUnitTest --tests "*RulesTest*"`
Expected: PASS, все тесты `ScreeningPolicyTest` зелёные

- [ ] **Step 6: Commit**

```bash
cd ~/Downloads/phonemodule/autoresponder-app
git add app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt \
        app/src/main/java/com/davnozdu/autoresponder/rules/ScreeningPolicy.kt \
        app/src/test/java/com/davnozdu/autoresponder/RulesTest.kt
git commit -m "Скрининг звонков: расписание и ScreeningPolicy"
```

---

## Task 2: Библиотека приветствий на 3 языках + повторяющийся зуммер

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt:296` (после `amGreetingLang`, перед `amMaxMessageSec`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/Greeting.kt`
- Modify: `app/src/test/java/com/davnozdu/autoresponder/RulesTest.kt` (новый класс теста)

**Interfaces:**
- Consumes: `Settings` (Task 1, тот же класс, новые поля).
- Produces: `Greeting.prepareScreening(ctx: Context, lang: String, maxTotalMs: Int): String?`, `Greeting.repeatingBeepPcm(maxTotalMs: Int): ByteArray`. Также `Settings.screeningDefaultLang`, `screeningGreetingSource{Cs,Ru,En}: Int`, `screeningGreetingFile{Cs,Ru,En}: String`, `screeningGreetingText{Cs,Ru,En}: String`.

- [ ] **Step 1: Добавить настройки библиотеки приветствий**

В `Settings.kt` сразу после `amGreetingLang` (строка 296, перед `amMaxMessageSec`) вставить:

```kotlin
    /** Язык приветствия скрининга по умолчанию для ВСЕХ звонков (не автоопределение —
     *  пользователь явно хочет чешский по умолчанию, независимо от языка/страны абонента). */
    var screeningDefaultLang: String
        get() = sp.getString(K_SCREEN_LANG, "cs") ?: "cs"
        set(v) = sp.edit().putString(K_SCREEN_LANG, v).apply()

    // --- библиотека приветствий скрининга: свой слот на каждый из трёх языков ---
    var screeningGreetingSourceCs: Int
        get() = sp.getInt(K_SCREEN_SRC_CS, 0)
        set(v) = sp.edit().putInt(K_SCREEN_SRC_CS, v).apply()
    var screeningGreetingFileCs: String
        get() = sp.getString(K_SCREEN_FILE_CS, "") ?: ""
        set(v) = sp.edit().putString(K_SCREEN_FILE_CS, v).apply()
    var screeningGreetingTextCs: String
        get() = sp.getString(K_SCREEN_TEXT_CS, "") ?: ""
        set(v) = sp.edit().putString(K_SCREEN_TEXT_CS, v).apply()

    var screeningGreetingSourceRu: Int
        get() = sp.getInt(K_SCREEN_SRC_RU, 0)
        set(v) = sp.edit().putInt(K_SCREEN_SRC_RU, v).apply()
    var screeningGreetingFileRu: String
        get() = sp.getString(K_SCREEN_FILE_RU, "") ?: ""
        set(v) = sp.edit().putString(K_SCREEN_FILE_RU, v).apply()
    var screeningGreetingTextRu: String
        get() = sp.getString(K_SCREEN_TEXT_RU, "") ?: ""
        set(v) = sp.edit().putString(K_SCREEN_TEXT_RU, v).apply()

    var screeningGreetingSourceEn: Int
        get() = sp.getInt(K_SCREEN_SRC_EN, 0)
        set(v) = sp.edit().putInt(K_SCREEN_SRC_EN, v).apply()
    var screeningGreetingFileEn: String
        get() = sp.getString(K_SCREEN_FILE_EN, "") ?: ""
        set(v) = sp.edit().putString(K_SCREEN_FILE_EN, v).apply()
    var screeningGreetingTextEn: String
        get() = sp.getString(K_SCREEN_TEXT_EN, "") ?: ""
        set(v) = sp.edit().putString(K_SCREEN_TEXT_EN, v).apply()
```

И в блок `private const val K_*` рядом с остальными `K_SCREEN_*`:

```kotlin
        private const val K_SCREEN_LANG = "screen_lang"
        private const val K_SCREEN_SRC_CS = "screen_src_cs"
        private const val K_SCREEN_FILE_CS = "screen_file_cs"
        private const val K_SCREEN_TEXT_CS = "screen_text_cs"
        private const val K_SCREEN_SRC_RU = "screen_src_ru"
        private const val K_SCREEN_FILE_RU = "screen_file_ru"
        private const val K_SCREEN_TEXT_RU = "screen_text_ru"
        private const val K_SCREEN_SRC_EN = "screen_src_en"
        private const val K_SCREEN_FILE_EN = "screen_file_en"
        private const val K_SCREEN_TEXT_EN = "screen_text_en"
```

- [ ] **Step 2: Написать падающий тест на повтор зуммера**

Добавить в `RulesTest.kt` (после класса `ScreeningPolicyTest`) новый класс, и импорт `import com.davnozdu.autoresponder.call.Greeting`:

```kotlin
class ScreeningBeepTest {

    @Test fun `нулевая и отрицательная длительность дают минимум один повтор`() {
        val zero = Greeting.repeatingBeepPcm(0)
        val negative = Greeting.repeatingBeepPcm(-500)
        assertTrue(zero.isNotEmpty())
        assertEquals(zero.size, negative.size)
    }

    @Test fun `бОльшая длительность даёт больше повторов`() {
        assertTrue(Greeting.repeatingBeepPcm(10_000).size > Greeting.repeatingBeepPcm(1_000).size)
    }

    @Test fun `повтор длины кратен единичному биппер-блоку`() {
        val one = Greeting.repeatingBeepPcm(1)          // минимум 1 повтор
        val many = Greeting.repeatingBeepPcm(one.size * 5) // должно дать >= 5 повторов по размеру
        assertEquals(0, many.size % one.size)
    }
}
```

- [ ] **Step 3: Запустить тест, убедиться что падает**

Run: `./gradlew testDebugUnitTest --tests "*RulesTest*"`
Expected: FAIL — `Unresolved reference: repeatingBeepPcm`

- [ ] **Step 4: Добавить `repeatingBeepPcm` и `prepareScreening` в `Greeting.kt`**

В `Greeting.kt` добавить публичную функцию сразу после `private fun beepTailPcm(): ByteArray { ... }` (после строки 141):

```kotlin
    /** Один и тот же блок бипа, повторённый столько раз, чтобы покрыть [maxTotalMs] —
     *  для зуммера скрининга: абонент слышит повторяющийся сигнал, пока владелец не решит
     *  («Принять»/«Отклонить») или пока не истечёт [Settings.amMaxMessageSec]. Минимум один
     *  повтор всегда, даже при [maxTotalMs] <= 0 (испорченная настройка не должна давать
     *  пустой/битый PCM). */
    fun repeatingBeepPcm(maxTotalMs: Int): ByteArray {
        val unit = beepTailPcm()
        val unitMs = BEEP_GAP_MS + BEEP_MS
        val repeats = (maxTotalMs / unitMs).coerceAtLeast(1)
        val out = ByteArray(unit.size * repeats)
        for (i in 0 until repeats) unit.copyInto(out, i * unit.size)
        return out
    }

    /** Приветствие скрининга: как [prepare], но источник — своя тройка настроек на [lang]
     *  ("cs"/"ru"/"en") вместо общих `amGreeting*`, и после речи повторяющийся зуммер
     *  ([repeatingBeepPcm]) вместо одного бипа — абонент ждёт под сигнал, пока владелец не
     *  решит через карточку. Обрывается штатным [AmBridge.stop], отдельного протокола не
     *  требуется — файл просто длинный (речь + зуммер на всю [maxTotalMs]). */
    suspend fun prepareScreening(ctx: Context, lang: String, maxTotalMs: Int): String? {
        val app = ctx.applicationContext
        val s = Settings(app)
        val source: Int; val file: String; val text: String
        when (lang) {
            "ru" -> { source = s.screeningGreetingSourceRu; file = s.screeningGreetingFileRu; text = s.screeningGreetingTextRu }
            "en" -> { source = s.screeningGreetingSourceEn; file = s.screeningGreetingFileEn; text = s.screeningGreetingTextEn }
            else -> { source = s.screeningGreetingSourceCs; file = s.screeningGreetingFileCs; text = s.screeningGreetingTextCs }
        }
        val useFile = source == 1 && file.isNotBlank()
        val src: File?
        val key: String
        if (useFile) {
            src = File(file)
            if (!src.exists()) { EventLog(app).add("AM скрининг: файла нет — $file"); return null }
            key = "$SYNTH_VER:screen:file:$lang:${src.absolutePath}:${src.lastModified()}:$maxTotalMs"
        } else {
            src = null
            key = "$SYNTH_VER:screen:tts:$lang:${text.hashCode()}:$maxTotalMs"
        }
        val out = cacheFile(app, key)
        if (out.exists() && out.length() > 0) return out.absolutePath

        if (useFile) {
            if (!AudioConvert.toRawPcm48kStereo(src!!.absolutePath, out.absolutePath)) {
                EventLog(app).add("AM скрининг: не сконвертировал файл ${src.name}"); return null
            }
        } else {
            val wav = synthTts(app, text.ifBlank { Settings.DEF_AM_GREETING }, lang) ?: run {
                EventLog(app).add("AM скрининг: TTS недоступен/не ответил вовремя"); return null
            }
            val ok = AudioConvert.toRawPcm48kStereo(wav.absolutePath, out.absolutePath)
            wav.delete()
            if (!ok) { EventLog(app).add("AM скрининг: не сконвертировал TTS"); return null }
        }
        try { FileOutputStream(out, true).use { it.write(repeatingBeepPcm(maxTotalMs)) } }
        catch (e: Exception) { EventLog(app).add("AM скрининг: не добавил зуммер (${e.message})") }
        cleanupOldCaches(app, out)
        return out.absolutePath
    }
```

- [ ] **Step 5: Запустить тест, убедиться что проходит**

Run: `./gradlew testDebugUnitTest --tests "*RulesTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt \
        app/src/main/java/com/davnozdu/autoresponder/call/Greeting.kt \
        app/src/test/java/com/davnozdu/autoresponder/RulesTest.kt
git commit -m "Скрининг звонков: библиотека приветствий на 3 языках + повторяющийся зуммер"
```

---

## Task 3: `CallerOverlay` — кнопки «Принять»/«Отклонить»

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/notif/CallerOverlay.kt`

**Interfaces:**
- Consumes: `com.davnozdu.autoresponder.crm.CrmLookup` (уже импортирован).
- Produces: `CallerOverlay.showScreening(context: Context, number: String, lookup: CrmLookup?, onAccept: () -> Unit, onDecline: () -> Unit)`. Существующие `show`/`hide` сохраняют сигнатуру.

Нет автотестов — в проекте нет инструментированных/Compose-UI тестов вообще (проверено: `find app/src/test` не находит ни одного теста на UI-классы), только чистая логика. Проверка — вручную, через `AmTestActivity`-подобный сценарий или живой звонок в Task 5.

- [ ] **Step 1: Разделить `hideNow` на удаление вида и полный dismiss**

В `CallerOverlay.kt` заменить текущий блок (строки 33-65):

```kotlin
object CallerOverlay {
    private const val TIMEOUT_MS = 2 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null
    private var shownFor: String? = null

    fun show(context: Context, number: String, lookup: CrmLookup?) {
        val app = context.applicationContext
        if (!AndroidSettings.canDrawOverlays(app)) return   // модуль ещё не выдал — молча живём уведомлением
        main.post {
            runCatching {
                hideNow(app)
                val card = CallerCard.render(lookup?.name?.ifBlank { null }, number, lookup)
                val view = build(app, number, card, lookup)
                wm(app).addView(view, params())
                shown = view
                shownFor = number
                main.postDelayed({ hide(app) }, TIMEOUT_MS)
            }.onFailure { EventLog(app).add("ОКНО: не показать — ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        main.post { runCatching { hideNow(app) } }
    }

    private fun hideNow(app: Context) {
        shown?.let { runCatching { wm(app).removeView(it) } }
        shown = null
        shownFor = null
        main.removeCallbacksAndMessages(null)
    }
```

на:

```kotlin
object CallerOverlay {
    private const val TIMEOUT_MS = 2 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null
    private var shownFor: String? = null
    private data class AcceptDecline(val onAccept: () -> Unit, val onDecline: () -> Unit)
    private var acceptDecline: AcceptDecline? = null

    fun show(context: Context, number: String, lookup: CrmLookup?) {
        val app = context.applicationContext
        if (!AndroidSettings.canDrawOverlays(app)) return   // модуль ещё не выдал — молча живём уведомлением
        main.post {
            runCatching {
                // Разные звонки — старые кнопки Принять/Отклонить не имеют смысла для НОВОГО
                // номера. Тот же номер (перерисовка карточки после прихода данных CRM из
                // CallerCardNotifier.onIncoming) — кнопки сохраняются, см. showScreening.
                if (shownFor != number) acceptDecline = null
                removeCurrentView(app)
                val card = CallerCard.render(lookup?.name?.ifBlank { null }, number, lookup)
                val view = build(app, number, card, lookup)
                wm(app).addView(view, params())
                shown = view
                shownFor = number
                main.postDelayed({ hide(app) }, TIMEOUT_MS)
            }.onFailure { EventLog(app).add("ОКНО: не показать — ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Как [show], но с кнопками «Принять»/«Отклонить» — для интерактивного скрининга
     *  (см. AnswerMachineService.runScreeningFlow). [onAccept]/[onDecline] вызываются на
     *  главном потоке при нажатии; карточка убирается сама сразу по первому тапу — второй
     *  тап (двойное нажатие) бьёт по уже отсутствующей вью. */
    fun showScreening(context: Context, number: String, lookup: CrmLookup?,
                       onAccept: () -> Unit, onDecline: () -> Unit) {
        acceptDecline = AcceptDecline(onAccept, onDecline)
        show(context, number, lookup)
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        main.post { runCatching { removeCurrentView(app); acceptDecline = null } }
    }

    private fun removeCurrentView(app: Context) {
        shown?.let { runCatching { wm(app).removeView(it) } }
        shown = null
        shownFor = null
        main.removeCallbacksAndMessages(null)
    }
```

- [ ] **Step 2: Показывать кнопки в `build()` независимо от CRM**

Найти строку (текущая строка 119):

```kotlin
        if (lookup != null && lookup.records.isNotEmpty()) root.addView(actions(app, number, px(8)))
        return root
    }
```

Заменить на:

```kotlin
        if (lookup != null && lookup.records.isNotEmpty()) root.addView(actions(app, number, px(8)))
        acceptDecline?.let { ad -> root.addView(acceptDeclineRow(app, px(8), ad)) }
        return root
    }

    private fun acceptDeclineRow(app: Context, gap: Int, ad: AcceptDecline): View {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        fun button(label: String, color: String, onClick: () -> Unit) = Button(app).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color)); cornerRadius = gap.toFloat() * 1.5f
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap }
            // hide() СРАЗУ по тапу — второй тап (двойное нажатие) бьёт по пустому месту,
            // не по кнопке: гонка «оба нажаты» физически исключена.
            setOnClickListener { hide(app); onClick() }
        }
        row.addView(button("Принять", "#0A6E2E") { ad.onAccept() })
        row.addView(button("Отклонить", "#8E1B1B") { ad.onDecline() })
        return row
    }
```

- [ ] **Step 3: Собрать проект и убедиться, что компилируется**

Run: `cd ~/Downloads/phonemodule/autoresponder-app && ./gradlew compileDebugKotlin` (или дождаться CI на пуше, если локального JDK нет)
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/notif/CallerOverlay.kt
git commit -m "Скрининг звонков: кнопки Принять/Отклонить в CallerOverlay"
```

---

## Task 4: `AnswerMachineService.runScreeningFlow` — новый поток обработки

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt`

**Interfaces:**
- Consumes: `Greeting.prepareScreening` (Task 2), `CallerOverlay.showScreening/hide` (Task 3), существующие `AmBridge.play/stop/recStart/recStop/recSave/recDiscard`, `RecordingLinker.linkLatest/durationMs`, `HistoryDb.amRecInsert/amRecSetFile`, приватные `answerCall`/`endCall`/`waitIdleOr`/`registerWatcher`/`unregisterWatcher`/`contactName` (уже существуют в этом файле).
- Produces: `AnswerMachineService.start(...)` теперь обрабатывает `reason = "screening"` отдельным потоком. Компаньон-функции `AnswerMachineService.screeningAccept()`/`screeningDecline()` — вызываются из UI-колбэков `CallerOverlay`.

Нет автотестов (Android `Service`, `TelecomManager`, корутины, root-демон) — проверяется живым звонком в Task 5 после того, как вся цепочка собрана.

- [ ] **Step 1: Добавить отслеживание активного экземпляра и флаги решения**

В `AnswerMachineService.kt` после строки 37 (`@Volatile private var offhook = false`) добавить:

```kotlin
    @Volatile private var accepted = false
    @Volatile private var declined = false
```

В `companion object` (после строки 368, внутри существующего `companion object { ... }`) добавить:

```kotlin
        @Volatile private var active: AnswerMachineService? = null

        /** Вызывается из CallerOverlay по тапу «Принять» — сигнализирует в текущий
         *  экземпляр сервиса, тот же паттерн, что уже используют idle/offhook. */
        fun screeningAccept() { active?.let { it.accepted = true } }
        fun screeningDecline() { active?.let { it.declined = true } }
```

- [ ] **Step 2: Устанавливать/снимать `active` в жизненном цикле сервиса**

В начале `onStartCommand` (строка 42-43, сразу после `startForegroundCompat()`) добавить:

```kotlin
        active = this
```

Добавить override `onDestroy` (после `onBind`, перед `onStartCommand`, т.е. после строки 40):

```kotlin
    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
```

- [ ] **Step 3: Маршрутизировать `reason == "screening"` на новый поток**

Заменить блок (текущие строки 50-57):

```kotlin
        scope.launch {
            try {
                if (reason == "test") runTestFlow(testSec.coerceIn(3, 120))
                else runFlow(number, name, reason, lang, text)
            }
            catch (e: Exception) { EventLog(applicationContext).add("AM: сбой (${e.message})") }
            finally { stopSelfSafe() }
        }
```

на:

```kotlin
        scope.launch {
            try {
                when (reason) {
                    "test" -> runTestFlow(testSec.coerceIn(3, 120))
                    "screening" -> runScreeningFlow(number, name)
                    else -> runFlow(number, name, reason, lang, text)
                }
            }
            catch (e: Exception) { EventLog(applicationContext).add("AM: сбой (${e.message})") }
            finally { stopSelfSafe() }
        }
```

- [ ] **Step 4: Написать `runScreeningFlow`**

Добавить новую функцию сразу после `runFlow` (после закрывающей `}` на строке 225, перед `private fun respondAllow`... — нет, `respondAllow` в другом файле; вставить перед `@android.annotation.SuppressLint("MissingPermission") private suspend fun answerCall`, т.е. после строки 225):

```kotlin
    /** Интерактивный скрининг: отвечаем, играем приветствие+зуммер, абонент ждёт, владелец
     *  решает через карточку CallerOverlay. В отличие от [runFlow] — экран НЕ гасится и НЕ
     *  блокируется (никакого AmBlockOverlay/AmBridge.blockOn): это видимый, а не тихий режим.
     *  «Принять» — снять мьют и отдать линию владельцу (звонок не пересоединяется, он был
     *  активен всё это время); «Отклонить» — обычный отбой. */
    private suspend fun runScreeningFlow(number: String?, nameIn: String?) {
        val app = applicationContext
        val s = Settings(app)
        val db = HistoryDb.get(app)
        val start = System.currentTimeMillis()
        val name = nameIn ?: contactName(app, number)
        EventLog(app).add("AM: старт ${number ?: "?"} (screening)")
        accepted = false; declined = false

        registerWatcher(app)
        try {
            answerCall(app)
            if (!offhook) {
                EventLog(app).add("AM: не ответили вовремя ${number ?: "?"} — пропуск")
                return
            }
            val recId = db.amRecInsert(number, name, start, 0, null, "screening")
            val safeNum = (number ?: "unknown").replace(Regex("[^+0-9]"), "")
            val ownRecPath = "/sdcard/AutoResponder/recordings/" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(start)) +
                "_${safeNum}_own.wav"
            val maxSec = s.amMaxMessageSec.coerceIn(5, 300)
            AmBridge.recStart(app, maxSec)

            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.isMicrophoneMute = true }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }

            val lookup = runCatching {
                com.davnozdu.autoresponder.crm.CrmFlow.lookup(app, listOfNotNull(number))
            }.getOrNull()
            com.davnozdu.autoresponder.notif.CallerOverlay.showScreening(app, number ?: "", lookup,
                onAccept = { screeningAccept() }, onDecline = { screeningDecline() })

            val greet = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                Greeting.prepareScreening(app, s.screeningDefaultLang, maxSec * 1000)
            }
            if (greet != null) AmBridge.play(app, greet, 1)
            else EventLog(app).add("AM: приветствие для скрининга не готово — молчим")

            waitScreeningDecision(maxSec * 1000L, am)

            if (accepted) {
                AmBridge.stop(app)
                runCatching { am.isMicrophoneMute = false }
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
                EventLog(app).add("AM: скрининг — владелец принял ${number ?: "?"}")
                com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)
                AmBridge.recStop(app)
                // Дальше это обычный разговор — штатная запись звонилки (если включена)
                // продолжает сама, как у любого вручную принятого звонка; свою запись
                // не связываем, строку журнала просто закрываем.
                db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
                return
            }

            if (declined || !idle) endCall(app)
            AmBridge.stop(app)
            AmBridge.recStop(app)
            delay(500)
            com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)

            waitIdleOr(5_000L)
            val link = RecordingLinker.linkLatest(app, number, start)
            if (link != null) {
                db.amRecSetFile(recId, link.first, link.second)
                AmBridge.recDiscard(app)
            } else {
                AmBridge.recSave(app, ownRecPath)
                val ownFile = java.io.File(ownRecPath)
                if (ownFile.exists() && ownFile.length() > 44) {
                    val dur = RecordingLinker.durationMs(ownFile.absolutePath)
                    db.amRecSetFile(recId, ownFile.absolutePath, dur)
                    EventLog(app).add("AM запись: штатный рекордер не сработал — оставил свою (${dur/1000}s)")
                } else {
                    db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
                }
            }
            EventLog(app).add("AM: завершено ${number ?: "?"}")
        } finally {
            // Как и в runFlow — гарантированно снимаем мьют/запись даже при исключении
            // между recStart и explicit recStop в обеих ветках выше (accept/decline/idle).
            unregisterWatcher(app)
            com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.isMicrophoneMute = false }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            AmBridge.stop(app)
            AmBridge.recStop(app)
        }
    }

    private suspend fun waitScreeningDecision(budgetMs: Long, am: AudioManager) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !idle && !accepted && !declined) {
            runCatching { am.isMicrophoneMute = true }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
            delay(300)
        }
    }
```

- [ ] **Step 5: Собрать проект**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt
git commit -m "Скрининг звонков: runScreeningFlow, Принять/Отклонить без повторного набора"
```

---

## Task 5: Маршрутизация в `CallScreeningServiceImpl`

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/CallScreeningServiceImpl.kt`

**Interfaces:**
- Consumes: `ScreeningPolicy.shouldScreen`/`isInWindow` (Task 1), `AnswerMachineService.start` (существует, теперь понимает `reason = "screening"` из Task 4).

Нет автотестов (сама ветка — одна строка условия поверх уже протестированной `ScreeningPolicy`) — проверяется живым звонком.

- [ ] **Step 1: Добавить импорт**

Добавить в блок импортов (после `import com.davnozdu.autoresponder.rules.PhoneMask`, строка 13):

```kotlin
import com.davnozdu.autoresponder.rules.ScreeningPolicy
```

- [ ] **Step 2: Вставить ветку скрининга перед проверкой гарнитуры**

Заменить текущий блок:

```kotlin
        // Bluetooth-гарнитура подключена и звонящий не избранный → всегда голосовой
        // автоответчик, независимо от открытых/закрытых часов и режима SMS/голос (проверяется
        // раньше «закрытых часов» — при совпадении обоих условий выигрывает гарнитура).
        val headsetTrigger = HeadsetPolicy.shouldForceAnswer(
            s.headsetForceAnswer, AudioRouteUtil.isBluetoothHeadsetActive(this), skip)

        if (headsetTrigger) {
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — гарнитура → автоответчик")
            AnswerMachineService.start(this, number, null, "headset", s.amGreetingLang.ifBlank { null }, null)
        } else if (closedReason != null && matches && !skip) {
```

на:

```kotlin
        // Скрининг: в настроенное рабочее время звонок от НЕ избранного получает видимую
        // интерактивную карточку (Принять/Отклонить), а не тихий автоответчик — проверяется
        // РАНЬШЕ гарнитуры: даже с подключённой гарнитурой (например, за рулём) владелец
        // хочет видеть карточку и мочь ответить с телефона (решение пользователя при ревью).
        val screeningTrigger = ScreeningPolicy.shouldScreen(
            s.screeningEnabled, ScreeningPolicy.isInWindow(this, s), skip)

        // Bluetooth-гарнитура подключена и звонящий не избранный → всегда голосовой
        // автоответчик, независимо от открытых/закрытых часов и режима SMS/голос (проверяется
        // раньше «закрытых часов» — при совпадении обоих условий выигрывает гарнитура; но
        // ПОЗЖЕ скрининга — см. выше).
        val headsetTrigger = HeadsetPolicy.shouldForceAnswer(
            s.headsetForceAnswer, AudioRouteUtil.isBluetoothHeadsetActive(this), skip)

        if (screeningTrigger) {
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — скрининг → экран")
            AnswerMachineService.start(this, number, null, "screening", null, null)
        } else if (headsetTrigger) {
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — гарнитура → автоответчик")
            AnswerMachineService.start(this, number, null, "headset", s.amGreetingLang.ifBlank { null }, null)
        } else if (closedReason != null && matches && !skip) {
```

- [ ] **Step 3: Собрать проект и прогнать весь юнит-тест-сьют**

Run: `./gradlew testDebugUnitTest compileDebugKotlin`
Expected: BUILD SUCCESSFUL, все тесты зелёные (включая `HeadsetPolicyTest`, `ScreeningPolicyTest`, `ScreeningBeepTest` из Task 1-2)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/call/CallScreeningServiceImpl.kt
git commit -m "Скрининг звонков: маршрутизация (скрининг раньше гарнитуры)"
```

---

## Task 6: UI в `MainActivity` — планировщик и библиотека приветствий

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `Settings.screening*` (Task 1-2), существующие composable-хелперы `ExpandableSection`, `SwitchRow`, `LangChip`, `pickTime`, `fmtMin`, функцию `importGreetingFile` (расширяется параметром слота).

Нет автотестов (Compose UI) — проверка вручную: собрать, открыть приложение, развернуть обе новые секции, потрогать тумблеры/загрузку файла.

- [ ] **Step 1: Расширить `importGreetingFile` под именованный слот**

Заменить (строка 1164-1170):

```kotlin
private fun importGreetingFile(ctx: Context, uri: android.net.Uri): String? = try {
    val dir = java.io.File(ctx.filesDir, "am").apply { mkdirs() }
    val ext = ctx.contentResolver.getType(uri)?.substringAfterLast('/')?.take(4) ?: "dat"
    val dst = java.io.File(dir, "greeting_src.$ext")
    ctx.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
    if (dst.length() > 0) dst.absolutePath else null
} catch (e: Exception) { null }
```

на:

```kotlin
private fun importGreetingFile(ctx: Context, uri: android.net.Uri, slot: String = "greeting_src"): String? = try {
    val dir = java.io.File(ctx.filesDir, "am").apply { mkdirs() }
    val ext = ctx.contentResolver.getType(uri)?.substringAfterLast('/')?.take(4) ?: "dat"
    val dst = java.io.File(dir, "$slot.$ext")
    ctx.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
    if (dst.length() > 0) dst.absolutePath else null
} catch (e: Exception) { null }
```

(Существующий вызов на строке 378 — `importGreetingFile(ctx, uri)` — не трогаем, слот по умолчанию `"greeting_src"` сохраняет прежнее поведение.)

- [ ] **Step 2: Добавить секцию «Скрининг звонков»**

В файле найти конец секции `ExpandableSection("Когда «закрыто» (расписание)")` (строка 536, закрывающая `}` этой секции) и сразу после неё, перед `ExpandableSection("Управление по SMS")` (строка 538), вставить:

```kotlin
            ExpandableSection("Скрининг звонков") {
                var screenOn by remember { mutableStateOf(s.screeningEnabled) }
                SwitchRow("Интерактивный скрининг (Принять/Отклонить)", screenOn) {
                    screenOn = it; s.screeningEnabled = it
                }
                Text("В настроенное время звонок не от избранного отвечается сам, абоненту "
                    + "играет приветствие и зуммер, а на экране появляется карточка с кнопками "
                    + "«Принять»/«Отклонить» — экран при этом яркий и видимый, не тихий режим. "
                    + "Проверяется раньше гарнитуры: даже с подключёнными наушниками владелец "
                    + "увидит карточку и сможет ответить с телефона.",
                    style = MaterialTheme.typography.bodySmall)
                var screenStart by remember { mutableStateOf(s.screeningStartMin) }
                var screenEnd by remember { mutableStateOf(s.screeningEndMin) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Активно с")
                    OutlinedButton(onClick = { pickTime(ctx, screenStart) { screenStart = it; s.screeningStartMin = it } }) { Text(fmtMin(screenStart)) }
                    Text("до")
                    OutlinedButton(onClick = { pickTime(ctx, screenEnd) { screenEnd = it; s.screeningEndMin = it } }) { Text(fmtMin(screenEnd)) }
                }
                var screenDays by remember { mutableStateOf(s.screeningWorkDaysMask) }
                Text("Дни:", style = MaterialTheme.typography.labelMedium)
                val screenDaysList = listOf(2 to "Пн", 3 to "Вт", 4 to "Ср", 5 to "Чт", 6 to "Пт", 7 to "Сб", 1 to "Вс")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    screenDaysList.forEach { (d, lbl) ->
                        FilterChip(selected = (screenDays and (1 shl d)) != 0, onClick = {
                            screenDays = screenDays xor (1 shl d); s.screeningWorkDaysMask = screenDays
                        }, label = { Text(lbl) })
                    }
                }
            }

```

- [ ] **Step 3: Добавить секцию «Приветствия»**

Сразу после только что добавленной секции «Скрининг звонков» (перед `ExpandableSection("Управление по SMS")`) вставить:

```kotlin
            ExpandableSection("Приветствия") {
                Text("Библиотека приветствий скрининга на трёх языках — свой аудиофайл или "
                    + "текст для синтеза речи на каждый. Язык по умолчанию для всех звонков:",
                    style = MaterialTheme.typography.bodySmall)
                var screenLang by remember { mutableStateOf(s.screeningDefaultLang) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LangChip("CS", "cs", screenLang) { screenLang = it; s.screeningDefaultLang = it }
                    LangChip("RU", "ru", screenLang) { screenLang = it; s.screeningDefaultLang = it }
                    LangChip("EN", "en", screenLang) { screenLang = it; s.screeningDefaultLang = it }
                }
                Spacer(Modifier.height(8.dp))
                GreetingLangSlot(ctx, scope, "Чешский", "cs",
                    s.screeningGreetingSourceCs, { s.screeningGreetingSourceCs = it },
                    s.screeningGreetingFileCs, { s.screeningGreetingFileCs = it },
                    s.screeningGreetingTextCs, { s.screeningGreetingTextCs = it })
                Spacer(Modifier.height(12.dp))
                GreetingLangSlot(ctx, scope, "Русский", "ru",
                    s.screeningGreetingSourceRu, { s.screeningGreetingSourceRu = it },
                    s.screeningGreetingFileRu, { s.screeningGreetingFileRu = it },
                    s.screeningGreetingTextRu, { s.screeningGreetingTextRu = it })
                Spacer(Modifier.height(12.dp))
                GreetingLangSlot(ctx, scope, "English", "en",
                    s.screeningGreetingSourceEn, { s.screeningGreetingSourceEn = it },
                    s.screeningGreetingFileEn, { s.screeningGreetingFileEn = it },
                    s.screeningGreetingTextEn, { s.screeningGreetingTextEn = it })
            }

```

- [ ] **Step 4: Добавить composable `GreetingLangSlot`**

Добавить новую приватную composable-функцию сразу после `LangChip` (после строки 1299, перед `SwitchRow`):

```kotlin
@Composable
private fun GreetingLangSlot(
    ctx: Context, scope: kotlinx.coroutines.CoroutineScope,
    title: String, slot: String,
    source: Int, onSource: (Int) -> Unit,
    file: String, onFile: (String) -> Unit,
    text: String, onText: (String) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    var src by remember(slot) { mutableStateOf(source) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(src == 0, { src = 0; onSource(0) }, { Text("Синтез (TTS)") })
        FilterChip(src == 1, { src = 1; onSource(1) }, { Text("Свой файл") })
    }
    if (src == 0) {
        var gText by remember(slot) { mutableStateOf(text) }
        OutlinedTextField(gText, { gText = it; onText(it) },
            label = { Text("Текст приветствия ($title)") }, modifier = Modifier.fillMaxWidth())
    } else {
        var gFile by remember(slot) { mutableStateOf(file) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                val p = withContext(Dispatchers.IO) { importGreetingFile(ctx, uri, "screen_greeting_$slot") }
                if (p != null) { onFile(p); gFile = p }
            }
        }
        Button(onClick = { picker.launch("audio/*") }) { Text("Выбрать аудиофайл") }
        Text(if (gFile.isNotBlank()) "Файл: ${java.io.File(gFile).name}" else "Файл не выбран",
            style = MaterialTheme.typography.bodySmall)
    }
}
```

- [ ] **Step 5: Собрать проект**

Run: `./gradlew compileDebugKotlin` (или CI на пуше)
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/ui/MainActivity.kt
git commit -m "Скрининг звонков: UI — планировщик и библиотека приветствий"
```

---

## После плана

Версия НЕ бампается и релиз НЕ выпускается автоматически внутри этого плана — по заведённому в проекте циклу: собрать debug/release APK через CI, поставить на телефон, проверить живым звонком (не-избранный номер, рабочее время скрининга, нажать «Принять» и отдельно «Отклонить»), и только после подтверждения — версия/тег/релиз по явной команде пользователя.
