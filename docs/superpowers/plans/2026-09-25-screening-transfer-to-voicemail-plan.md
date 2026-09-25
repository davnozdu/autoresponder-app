# Скрининг звонков: «Перебросить на автоответчик» — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** На карточке скрининга — третья синяя кнопка «Перебросить на автоответчик»
(во всю ширину, под «Ответить»/«Отклонить»). По нажатию, или по истечении
настраиваемого времени ожидания решения (по умолчанию 5 мин) без реакции
владельца: текущий буфер записи (то, что шло во время ожидания) стирается,
играет отдельное voicemail-приветствие, запись стартует заново, клиент
говорит до 1 минуты (тоже настраиваемо) или пока сам не положит трубку,
запись сохраняется в уже существующий список `AmRecordingsActivity`. Плюс:
та же карточка скрининга появляется теперь и в обычные рабочие (открытые)
часы, не только в своё отдельное расписание.

**Architecture:** Третье волатильное состояние (`transferred`) в
`AnswerMachineService.runScreeningFlow`, параллельно уже существующим
`accepted`/`declined` — тот же паттерн сигнализации из `CallerOverlay` через
`@Volatile`-флаги. Третья кнопка в уже существующем ряду кнопок
`CallerOverlay.acceptDeclineRow`. Новая ветка маршрутизации в
`CallScreeningServiceImpl.onScreenCall` (симметрична существующей ветке
«закрыто», но по условию «открыто»), ведёт в ТОТ ЖЕ `runScreeningFlow`
(`reason="screening"` не меняется). Новый слот приветствия в `Greeting.kt`
зеркалит структуру уже существующего `prepareScreening`, но без
hold-хвоста (проигрывается один раз).

**Tech Stack:** Kotlin, Android (Jetpack Compose UI в `MainActivity`,
классический `View`/`WindowManager` в `CallerOverlay`). Юнит-тестов на этот
код в проекте нет и не заводится — весь call-flow (Telecom/аудио/root-демон)
проверяется живыми звонками (см. Tech Stack существующего плана
`2026-09-24-call-screening-plan.md`, тот же прецедент).

**Spec:** `docs/superpowers/specs/2026-09-25-screening-transfer-to-voicemail-design.md`

## Global Constraints

- «Отклонить» НЕ меняется — как и сейчас, мгновенный `endCall()` без записи.
  Новая ветка «переброс» — строго отдельная от «отклонить».
- Ручное нажатие кнопки (любой из трёх) всегда прерывает ожидание МГНОВЕННО
  — тот же `hide()` СРАЗУ по тапу, что уже есть у «Ответить»/«Отклонить»
  (защита от гонки двойного тапа).
- Своя (передёрнутая в момент переброса) запись — ВСЕГДА главная запись в
  `am_rec` для этого события. OEM-запись, если найдётся, сохраняется ОТДЕЛЬНОЙ
  записью (не главной), не отбрасывается.
- Новые числовые настройки (`screeningWaitSec`, `voicemailMaxSec`) хранятся в
  секундах в `Settings` (как все существующие `*Sec`-поля), но в UI
  `MainActivity` вводятся и отображаются в МИНУТАХ — конвертация на границе
  поля, тот же приём, что уже есть у `batchWait` в `MainActivity.kt`.
- Никаких новых Android-разрешений.

## Review Focus

- **Ручная кнопка и автотаймаут не должны задваиваться** — `transferred`
  устанавливается один раз (флаг), цикл ожидания выходит по первому
  сработавшему условию и не возвращается в него повторно; после выхода из
  цикла дальнейший код должен ветвиться по `transferred`, а не пытаться
  определить «кто именно сработал».
- **«Отклонить» не должен попадать в новую ветку** — порядок проверок после
  `waitScreeningDecision` важен: `accepted` → `transferred` → существующий
  `declined || !idle`. Перепутанный порядок означал бы, что отклонённый
  звонок тоже получает voicemail-приветствие.
- **`screeningWaitSec`/`voicemailMaxSec` испорчены пользователем через
  настройки** (0, отрицательное, гигантское) — `coerceIn(5, 300)`, тот же
  приём, что уже есть у `amMaxMessageSec` в текущем коде — обязателен в
  обеих новых точках использования, не только в одной.
- **Voicemail-приветствие не готово** (TTS не ответил / файла нет) — не
  должно блокировать переход к записи: тот же паттерн, что уже есть у
  `greet` в `runScreeningFlow` (`if (greet != null) play(...) else
  EventLog(...)`), запись стартует в любом случае.
- **OEM-запись (после переброса) не должна попасть в ГЛАВНУЮ запись** —
  второй `db.amRecInsert(...)` для OEM-находки обязан вернуть НОВЫЙ
  `fullId`, а не переиспользовать `recId` из своей записи — иначе
  `amRecSetFile(recId, ...)` вызовется дважды и последний вызов (OEM)
  молча станет главным, нарушив явное требование спеки.

---

## Task 1: Настройки (`Settings.kt`)

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt:376-381` (после блока `screeningHoldFileEn`/перед `amMaxMessageSec`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt:793` (после `K_SCREEN_HOLD_EN`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt:871` (после `DEF_SCREEN_GREETING_EN`)

**Interfaces:**
- Consumes: ничего нового.
- Produces: `Settings.openHoursScreeningEnabled: Boolean`, `Settings.screeningWaitSec: Int`,
  `Settings.voicemailMaxSec: Int`, `Settings.voicemailGreetingSource{Cs,Ru,En}: Int`,
  `Settings.voicemailGreetingFile{Cs,Ru,En}: String`, `Settings.voicemailGreetingText{Cs,Ru,En}: String`,
  `Settings.voicemailGreetingLang{Cs,Ru,En}: String`, `Settings.DEF_VOICEMAIL_GREETING_{CS,RU,EN}: String`.

Нет автотестов — простые `SharedPreferences`-обёртки, зеркалят уже существующий
паттерн `screeningGreeting*`, проверяются компиляцией и живым использованием
в Task 3/6.

- [ ] **Step 1: Добавить новые поля-настройки**

В `app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt` сразу
после блока `screeningHoldFileEn` (строка 376) и ПЕРЕД комментарием
`/** Сколько секунд держим линию под сообщение клиента, затем отбой. */`
(строка 378) вставить:

```kotlin
    // --- переброс на автоответчик (кнопка на карточке скрининга) ---
    /** Та же карточка скрининга (Ответить/Отклонить/Перебросить) появляется не только в
     *  своё расписание, но и в обычные открытые (не закрытые) часы. */
    var openHoursScreeningEnabled: Boolean
        get() = sp.getBoolean(K_OPEN_SCREEN, false)
        set(v) = sp.edit().putBoolean(K_OPEN_SCREEN, v).apply()

    /** Сколько секунд карточка ждёт решения владельца, прежде чем автоматически
     *  переброситься на автоответчик — ОТДЕЛЬНО от amMaxMessageSec (тот отвечает только за
     *  длительность записи сообщения, не за ожидание карточки). */
    var screeningWaitSec: Int
        get() = sp.getInt(K_SCREEN_WAIT, 300)
        set(v) = sp.edit().putInt(K_SCREEN_WAIT, v).apply()

    /** Длительность записи клиента ПОСЛЕ переброса на автоответчик. */
    var voicemailMaxSec: Int
        get() = sp.getInt(K_VM_MAX_SEC, 60)
        set(v) = sp.edit().putInt(K_VM_MAX_SEC, v).apply()

    // --- voicemail-приветствие («мы не можем сейчас связаться, оставьте сообщение»),
    // свой слот на каждый из трёх языков — зеркалит screeningGreeting*, но БЕЗ hold-файла
    // (эта фраза проигрывается один раз, следом сразу стартует запись). ---
    var voicemailGreetingSourceCs: Int
        get() = sp.getInt(K_VM_SRC_CS, 0)
        set(v) = sp.edit().putInt(K_VM_SRC_CS, v).apply()
    var voicemailGreetingFileCs: String
        get() = sp.getString(K_VM_FILE_CS, "") ?: ""
        set(v) = sp.edit().putString(K_VM_FILE_CS, v).apply()
    var voicemailGreetingTextCs: String
        get() = sp.getString(K_VM_TEXT_CS, DEF_VOICEMAIL_GREETING_CS) ?: DEF_VOICEMAIL_GREETING_CS
        set(v) = sp.edit().putString(K_VM_TEXT_CS, v).apply()
    var voicemailGreetingLangCs: String
        get() = sp.getString(K_VM_LANG_CS, "cs") ?: "cs"
        set(v) = sp.edit().putString(K_VM_LANG_CS, v).apply()

    var voicemailGreetingSourceRu: Int
        get() = sp.getInt(K_VM_SRC_RU, 0)
        set(v) = sp.edit().putInt(K_VM_SRC_RU, v).apply()
    var voicemailGreetingFileRu: String
        get() = sp.getString(K_VM_FILE_RU, "") ?: ""
        set(v) = sp.edit().putString(K_VM_FILE_RU, v).apply()
    var voicemailGreetingTextRu: String
        get() = sp.getString(K_VM_TEXT_RU, DEF_VOICEMAIL_GREETING_RU) ?: DEF_VOICEMAIL_GREETING_RU
        set(v) = sp.edit().putString(K_VM_TEXT_RU, v).apply()
    var voicemailGreetingLangRu: String
        get() = sp.getString(K_VM_LANG_RU, "ru") ?: "ru"
        set(v) = sp.edit().putString(K_VM_LANG_RU, v).apply()

    var voicemailGreetingSourceEn: Int
        get() = sp.getInt(K_VM_SRC_EN, 0)
        set(v) = sp.edit().putInt(K_VM_SRC_EN, v).apply()
    var voicemailGreetingFileEn: String
        get() = sp.getString(K_VM_FILE_EN, "") ?: ""
        set(v) = sp.edit().putString(K_VM_FILE_EN, v).apply()
    var voicemailGreetingTextEn: String
        get() = sp.getString(K_VM_TEXT_EN, DEF_VOICEMAIL_GREETING_EN) ?: DEF_VOICEMAIL_GREETING_EN
        set(v) = sp.edit().putString(K_VM_TEXT_EN, v).apply()
    var voicemailGreetingLangEn: String
        get() = sp.getString(K_VM_LANG_EN, "en") ?: "en"
        set(v) = sp.edit().putString(K_VM_LANG_EN, v).apply()

```

- [ ] **Step 2: Добавить ключи `SharedPreferences`**

В блоке `private const val K_*` сразу после `K_SCREEN_HOLD_EN` (строка 793) вставить:

```kotlin
        private const val K_OPEN_SCREEN = "open_hours_screening"
        private const val K_SCREEN_WAIT = "screen_wait_sec"
        private const val K_VM_MAX_SEC = "vm_max_sec"
        private const val K_VM_SRC_CS = "vm_src_cs"
        private const val K_VM_FILE_CS = "vm_file_cs"
        private const val K_VM_TEXT_CS = "vm_text_cs"
        private const val K_VM_LANG_CS = "vm_lang_cs"
        private const val K_VM_SRC_RU = "vm_src_ru"
        private const val K_VM_FILE_RU = "vm_file_ru"
        private const val K_VM_TEXT_RU = "vm_text_ru"
        private const val K_VM_LANG_RU = "vm_lang_ru"
        private const val K_VM_SRC_EN = "vm_src_en"
        private const val K_VM_FILE_EN = "vm_file_en"
        private const val K_VM_TEXT_EN = "vm_text_en"
        private const val K_VM_LANG_EN = "vm_lang_en"
```

- [ ] **Step 3: Добавить тексты приветствия по умолчанию**

В блоке `companion object` сразу после `DEF_SCREEN_GREETING_EN` (строка 871) вставить:

```kotlin
        const val DEF_VOICEMAIL_GREETING_CS = "Momentálně se s vámi nemůžeme spojit. Prosím, zanechte zprávu."
        const val DEF_VOICEMAIL_GREETING_RU = "Сейчас мы не можем с вами связаться. Пожалуйста, оставьте сообщение."
        const val DEF_VOICEMAIL_GREETING_EN = "We're unable to take your call right now. Please leave a message."
```

- [ ] **Step 4: Собрать проект**

Run: `cd ~/Downloads/phonemodule/autoresponder-app && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/data/Settings.kt
git commit -m "Переброс на автоответчик: настройки (тумблер, тайминги, voicemail-приветствие x3 языка)"
```

---

## Task 2: `Greeting.prepareVoicemail` — приветствие voicemail-фазы

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/Greeting.kt:269` (сразу после закрывающей `}` функции `prepareScreening`)

**Interfaces:**
- Consumes: `Settings.voicemailGreeting*` (Task 1), уже существующие приватные
  `cacheFile`, `keyDigest`, `synthTts`, `cleanupOldCaches`, `AudioConvert.toRawPcm48kStereo`, `SYNTH_VER`.
- Produces: `Greeting.prepareVoicemail(ctx: Context, slot: String): String?`.

Нет автотестов — тот же прецедент, что и `prepareScreening` (Android TTS/
файловая система), проверяется живым звонком после Task 3.

- [ ] **Step 1: Добавить функцию**

В `app/src/main/java/com/davnozdu/autoresponder/call/Greeting.kt` сразу
после закрывающей `}` функции `prepareScreening` (строка 269) вставить:

```kotlin

    /** Voicemail-приветствие («мы не можем сейчас связаться, оставьте сообщение») — как
     *  [prepareScreening], но БЕЗ hold-хвоста: эта фраза проигрывается ОДИН раз, следом сразу
     *  стартует запись (см. AnswerMachineService.runScreeningFlow, ветка «Перебросить на
     *  автоответчик»), поэтому зуммер/музыка тут не нужны в принципе. [slot] — "cs"/"ru"/"en". */
    suspend fun prepareVoicemail(ctx: Context, slot: String): String? {
        val app = ctx.applicationContext
        val s = Settings(app)
        val source: Int; val file: String; val text: String; val ttsLang: String; val defText: String
        when (slot) {
            "ru" -> { source = s.voicemailGreetingSourceRu; file = s.voicemailGreetingFileRu; text = s.voicemailGreetingTextRu
                      ttsLang = s.voicemailGreetingLangRu; defText = Settings.DEF_VOICEMAIL_GREETING_RU }
            "en" -> { source = s.voicemailGreetingSourceEn; file = s.voicemailGreetingFileEn; text = s.voicemailGreetingTextEn
                      ttsLang = s.voicemailGreetingLangEn; defText = Settings.DEF_VOICEMAIL_GREETING_EN }
            else -> { source = s.voicemailGreetingSourceCs; file = s.voicemailGreetingFileCs; text = s.voicemailGreetingTextCs
                      ttsLang = s.voicemailGreetingLangCs; defText = Settings.DEF_VOICEMAIL_GREETING_CS }
        }
        val useFile = source == 1 && file.isNotBlank()
        val src: File?
        val key: String
        if (useFile) {
            src = File(file)
            if (!src.exists()) { EventLog(app).add("AM voicemail: файла нет — $file"); return null }
            key = "$SYNTH_VER:voicemail:file:$slot:${src.absolutePath}:${src.lastModified()}"
        } else {
            src = null
            key = "$SYNTH_VER:voicemail:tts:$slot:$ttsLang:${text.hashCode()}"
        }
        val out = cacheFile(app, key)
        if (out.exists() && out.length() > 0) return out.absolutePath

        if (useFile) {
            if (!AudioConvert.toRawPcm48kStereo(src!!.absolutePath, out.absolutePath)) {
                EventLog(app).add("AM voicemail: не сконвертировал файл ${src.name}"); return null
            }
        } else {
            val wav = synthTts(app, text.ifBlank { defText }, ttsLang) ?: run {
                EventLog(app).add("AM voicemail: TTS недоступен/не ответил вовремя"); return null
            }
            val ok = AudioConvert.toRawPcm48kStereo(wav.absolutePath, out.absolutePath)
            wav.delete()
            if (!ok) { EventLog(app).add("AM voicemail: не сконвертировал TTS"); return null }
        }
        cleanupOldCaches(app, out)
        return out.absolutePath
    }
```

- [ ] **Step 2: Собрать проект**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/call/Greeting.kt
git commit -m "Переброс на автоответчик: Greeting.prepareVoicemail"
```

---

## Task 3: `CallerOverlay` третья кнопка + `AnswerMachineService` флоу переброса

Одна задача, не две: `CallerOverlay.showScreening` меняет сигнатуру, и
единственный вызывающий код (`AnswerMachineService.runScreeningFlow`) правится
в этом же шаге — по отдельности ни один из двух файлов не собирается.

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/notif/CallerOverlay.kt:38` (`AcceptDecline`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/notif/CallerOverlay.kt:89-94` (`showScreening`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/notif/CallerOverlay.kt:200-221` (`acceptDeclineRow`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt:39` (после `declined`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt:256` (`accepted = false; declined = false`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt:278-279` (`showScreening` вызов)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt:306-322` (после `waitScreeningDecision`, перед веткой `declined || !idle`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt:364-371` (`waitScreeningDecision`)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt:537-538` (companion, после `screeningDecline`)

**Interfaces:**
- Consumes: `Greeting.prepareVoicemail` (Task 2), `Settings.screeningWaitSec`/`voicemailMaxSec`
  (Task 1), уже существующие `AmBridge.*`, `RecordingLinker.linkLatest/durationMs`,
  `HistoryDb.amRecInsert/amRecSetFile`, `waitIdleOr`, `endCall`.
- Produces: `CallerOverlay.showScreening(context, number, lookup, onAccept, onDecline, onTransfer)`
  (новый обязательный параметр `onTransfer: () -> Unit`). `AnswerMachineService.screeningTransfer()`
  (companion-функция, тот же паттерн, что уже есть у `screeningAccept`/`screeningDecline`).

Нет автотестов (native View/WindowManager, Android `Service`, корутины,
root-демон) — проверяется живым звонком после Task 4.

- [ ] **Step 1: Добавить `onTransfer` в `AcceptDecline`**

Заменить (строка 38):

```kotlin
    private data class AcceptDecline(val onAccept: () -> Unit, val onDecline: () -> Unit)
```

на:

```kotlin
    private data class AcceptDecline(
        val onAccept: () -> Unit, val onDecline: () -> Unit, val onTransfer: () -> Unit)
```

- [ ] **Step 2: Обновить `showScreening`**

Заменить (строки 89-93):

```kotlin
    fun showScreening(context: Context, number: String, lookup: CrmLookup?,
                       onAccept: () -> Unit, onDecline: () -> Unit) {
        acceptDecline = AcceptDecline(onAccept, onDecline)
        acceptDeclineFor = number
        show(context, number, lookup)
    }
```

на:

```kotlin
    fun showScreening(context: Context, number: String, lookup: CrmLookup?,
                       onAccept: () -> Unit, onDecline: () -> Unit, onTransfer: () -> Unit) {
        acceptDecline = AcceptDecline(onAccept, onDecline, onTransfer)
        acceptDeclineFor = number
        show(context, number, lookup)
    }
```

- [ ] **Step 3: Третья кнопка в `acceptDeclineRow`**

Заменить весь блок (строки 200-221):

```kotlin
    private fun acceptDeclineRow(app: Context, gap: Int, ad: AcceptDecline): View {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        // Крупные кнопки, чтобы не промахнуться: высота — ещё +10% высоты экрана поверх
        // тех ~30%, что уже занимает карточка (details у CallerCard ограничены 30%, см. build()).
        val btnHeight = (app.resources.displayMetrics.heightPixels * 0.10).toInt()
        fun button(label: String, color: String, onClick: () -> Unit) = Button(app).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color)); cornerRadius = gap.toFloat() * 1.5f
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply { marginEnd = gap }
            // hide() СРАЗУ по тапу — второй тап (двойное нажатие) бьёт по пустому месту,
            // не по кнопке: гонка «оба нажаты» физически исключена.
            setOnClickListener { hide(app); onClick() }
        }
        row.addView(button("Ответить", "#0A6E2E") { ad.onAccept() })
        row.addView(button("Отклонить", "#8E1B1B") { ad.onDecline() })
        return row
    }
```

на:

```kotlin
    private fun acceptDeclineRow(app: Context, gap: Int, ad: AcceptDecline): View {
        val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        // Крупные кнопки, чтобы не промахнуться: высота — ещё +10% высоты экрана поверх
        // тех ~30%, что уже занимает карточка (details у CallerCard ограничены 30%, см. build()).
        val btnHeight = (app.resources.displayMetrics.heightPixels * 0.10).toInt()
        fun button(label: String, color: String, weight: Float, onClick: () -> Unit) = Button(app).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color)); cornerRadius = gap.toFloat() * 1.5f
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, weight).apply { marginEnd = gap }
            // hide() СРАЗУ по тапу — второй тап (двойное нажатие) бьёт по пустому месту,
            // не по кнопке: гонка «оба нажаты» физически исключена.
            setOnClickListener { hide(app); onClick() }
        }
        row.addView(button("Ответить", "#0A6E2E", 1f) { ad.onAccept() })
        row.addView(button("Отклонить", "#8E1B1B", 1f) { ad.onDecline() })
        col.addView(row)
        // Во всю ширину (как обе кнопки выше вместе) — та же высота, синий фон.
        col.addView(Button(app).apply {
            text = "Перебросить на автоответчик"; textSize = 18f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0")); cornerRadius = gap.toFloat() * 1.5f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, btnHeight).apply {
                topMargin = gap
            }
            setOnClickListener { hide(app); ad.onTransfer() }
        })
        return col
    }
```

- [ ] **Step 4: Новый волатильный флаг**

Заменить (строка 38-39):

```kotlin
    @Volatile private var accepted = false
    @Volatile private var declined = false
```

на:

```kotlin
    @Volatile private var accepted = false
    @Volatile private var declined = false
    @Volatile private var transferred = false
```

- [ ] **Step 5: Companion-функция `screeningTransfer`**

Заменить (строки 537-538):

```kotlin
        fun screeningAccept() { active?.let { it.accepted = true } }
        fun screeningDecline() { active?.let { it.declined = true } }
```

на:

```kotlin
        fun screeningAccept() { active?.let { it.accepted = true } }
        fun screeningDecline() { active?.let { it.declined = true } }
        fun screeningTransfer() { active?.let { it.transferred = true } }
```

- [ ] **Step 6: Сбросить флаг в начале `runScreeningFlow`**

Заменить (строка 256):

```kotlin
        accepted = false; declined = false
```

на:

```kotlin
        accepted = false; declined = false; transferred = false
```

- [ ] **Step 7: Прокинуть `onTransfer` в вызов `showScreening`**

Заменить (строки 278-279):

```kotlin
            com.davnozdu.autoresponder.notif.CallerOverlay.showScreening(app, normNumber, null,
                onAccept = { screeningAccept() }, onDecline = { screeningDecline() })
```

на:

```kotlin
            com.davnozdu.autoresponder.notif.CallerOverlay.showScreening(app, normNumber, null,
                onAccept = { screeningAccept() }, onDecline = { screeningDecline() },
                onTransfer = { screeningTransfer() })
```

- [ ] **Step 8: `waitScreeningDecision` — свой таймаут, выход по `transferred`**

Заменить (строки 364-371):

```kotlin
    private suspend fun waitScreeningDecision(budgetMs: Long, am: AudioManager) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !idle && !accepted && !declined) {
            runCatching { am.isMicrophoneMute = true }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
            delay(300)
        }
    }
```

на:

```kotlin
    private suspend fun waitScreeningDecision(budgetMs: Long, am: AudioManager) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !idle && !accepted && !declined && !transferred) {
            runCatching { am.isMicrophoneMute = true }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
            delay(300)
        }
        // Время вышло, а владелец так и не отреагировал — автоматический переброс на
        // автоответчик, равнозначно нажатию кнопки (см. спеку: "5 минут" — это именно этот
        // таймаут). !idle: абонент ещё на линии, есть кого перебрасывать.
        if (System.currentTimeMillis() >= deadline && !idle && !accepted && !declined) transferred = true
    }
```

- [ ] **Step 9: Вызов `waitScreeningDecision` с новым таймаутом настройки**

Найти строку (текущая строка 306):

```kotlin
            waitScreeningDecision(maxSec * 1000L, am)
```

Заменить на:

```kotlin
            waitScreeningDecision(s.screeningWaitSec.coerceIn(5, 300) * 1000L, am)
```

- [ ] **Step 10: Ветка переброса — между `accepted` и `declined`**

Найти блок (текущие строки 308-324):

```kotlin
            if (accepted) {
                AmBridge.stop(app)
                runCatching { am.isMicrophoneMute = false }
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
                EventLog(app).add("AM: скрининг — владелец принял ${number ?: "?"}")
                com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)
                // Дальше это обычный разговор — штатная запись звонилки (если включена)
                // продолжает сама, как у любого вручную принятого звонка. Свой буфер
                // (pal_record) тут не нужен — отбрасываем, а не сохраняем: сохранённый файл
                // содержал бы только приветствие+зуммер до момента ответа, не сам разговор, и
                // создавать под него запись в журнале (которую нечем будет проиграть) незачем.
                AmBridge.recStop(app)
                AmBridge.recDiscard(app)
                return
            }

            if (declined || !idle) endCall(app)
```

Заменить на (добавлена ветка `transferred` между `accepted` и `declined`):

```kotlin
            if (accepted) {
                AmBridge.stop(app)
                runCatching { am.isMicrophoneMute = false }
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
                EventLog(app).add("AM: скрининг — владелец принял ${number ?: "?"}")
                com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)
                // Дальше это обычный разговор — штатная запись звонилки (если включена)
                // продолжает сама, как у любого вручную принятого звонка. Свой буфер
                // (pal_record) тут не нужен — отбрасываем, а не сохраняем: сохранённый файл
                // содержал бы только приветствие+зуммер до момента ответа, не сам разговор, и
                // создавать под него запись в журнале (которую нечем будет проиграть) незачем.
                AmBridge.recStop(app)
                AmBridge.recDiscard(app)
                return
            }

            if (transferred) {
                // Буфер, который писался во время ожидания карточки, — не нужен, стираем:
                // клиент должен услышать voicemail-приветствие и знать, что теперь пишется
                // именно его сообщение, а не молчаливое продолжение прежней записи.
                AmBridge.recStop(app)
                AmBridge.recDiscard(app)
                AmBridge.stop(app)
                com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)

                val vmGreet = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    Greeting.prepareVoicemail(app, s.screeningDefaultLang)
                }
                if (vmGreet != null) AmBridge.play(app, vmGreet, 1)
                else EventLog(app).add("AM: voicemail-приветствие не готово — молчим")

                val vmMaxSec = s.voicemailMaxSec.coerceIn(5, 300)
                AmBridge.recStart(app, vmMaxSec)          // запись С НУЛЯ, новый файл
                waitIdleOr(vmMaxSec * 1000L)
                if (!idle) endCall(app)
                AmBridge.stop(app)
                AmBridge.recStop(app)
                delay(500)                                 // дать демону дописать WAV-заголовок

                // Своя (передёрнутая) запись — ВСЕГДА главная для этого события.
                val recId = db.amRecInsert(number, name, start, 0, null, "voicemail")
                AmBridge.recSave(app, ownRecPath)
                val ownFile = java.io.File(ownRecPath)
                if (ownFile.exists() && ownFile.length() > 44) {
                    db.amRecSetFile(recId, ownFile.absolutePath, RecordingLinker.durationMs(ownFile.absolutePath))
                } else {
                    db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
                }
                // OEM-запись (если штатный рекордер поймал звонок с начала) сохраняем ОТДЕЛЬНОЙ,
                // НЕ главной записью — своим recId, не переиспользуя recId выше.
                val oemLink = RecordingLinker.linkLatest(app, number, start)
                if (oemLink != null) {
                    val fullId = db.amRecInsert(number, name, start, 0, null, "voicemail_full")
                    db.amRecSetFile(fullId, oemLink.first, oemLink.second)
                }
                EventLog(app).add("AM: скрининг — переброшено на автоответчик ${number ?: "?"}")
                return
            }

            if (declined || !idle) endCall(app)
```

- [ ] **Step 11: Собрать проект**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (`CallerOverlay.kt` и `AnswerMachineService.kt`
согласованы по сигнатуре `showScreening`)

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/notif/CallerOverlay.kt \
        app/src/main/java/com/davnozdu/autoresponder/call/AnswerMachineService.kt
git commit -m "Переброс на автоответчик: третья кнопка + флоу в runScreeningFlow"
```

---

## Task 4: Маршрутизация — скрининг в рабочие часы (`CallScreeningServiceImpl`)

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/call/CallScreeningServiceImpl.kt:97-114`

**Interfaces:**
- Consumes: `Settings.openHoursScreeningEnabled` (Task 1), уже существующие `closedReason`,
  `matches`, `skip`, `overlayOk`, `alreadyInCall`, `AnswerMachineService.start`.

Нет автотестов (inline-условие, тот же паттерн, что у соседних веток) —
проверяется живым звонком.

- [ ] **Step 1: Добавить `openHoursTrigger` и новую ветку**

Найти текущий блок (строки 97-114 в актуальном файле — с комментарием
«Скрининг: в настроенное рабочее время...» и до `} else if (headsetTrigger) {`):

```kotlin
        val screeningTrigger = ScreeningPolicy.shouldScreen(
            s.screeningEnabled, ScreeningPolicy.isInWindow(this, s), skip) && overlayOk && !alreadyInCall

        // Bluetooth-гарнитура подключена и звонящий не избранный → всегда голосовой
        // автоответчик, независимо от открытых/закрытых часов и режима SMS/голос (проверяется
        // раньше «закрытых часов» — при совпадении обоих условий выигрывает гарнитура; но
        // ПОЗЖЕ скрининга — см. выше).
        val headsetTrigger = HeadsetPolicy.shouldForceAnswer(
            s.headsetForceAnswer, AudioRouteUtil.isBluetoothHeadsetActive(this), skip) && !alreadyInCall

        if (screeningTrigger) {
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — скрининг → экран")
            AnswerMachineService.start(this, number, null, "screening", null, null)
        } else if (headsetTrigger) {
```

Заменить на (добавлены `openHoursTrigger` и новая ветка перед финальным `else`):

```kotlin
        val screeningTrigger = ScreeningPolicy.shouldScreen(
            s.screeningEnabled, ScreeningPolicy.isInWindow(this, s), skip) && overlayOk && !alreadyInCall

        // Та же карточка скрининга — и в обычные ОТКРЫТЫЕ часы (не только в своё отдельное
        // расписание выше): closedReason == null — это и есть «открыто», тот же признак, что
        // уже определяет финальную ветку "иначе — звонит нормально" ниже.
        val openHoursTrigger = s.openHoursScreeningEnabled && closedReason == null &&
            matches && !skip && overlayOk && !alreadyInCall

        // Bluetooth-гарнитура подключена и звонящий не избранный → всегда голосовой
        // автоответчик, независимо от открытых/закрытых часов и режима SMS/голос (проверяется
        // раньше «закрытых часов» — при совпадении обоих условий выигрывает гарнитура; но
        // ПОЗЖЕ скрининга — см. выше).
        val headsetTrigger = HeadsetPolicy.shouldForceAnswer(
            s.headsetForceAnswer, AudioRouteUtil.isBluetoothHeadsetActive(this), skip) && !alreadyInCall

        if (screeningTrigger || openHoursTrigger) {
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — скрининг → экран")
            AnswerMachineService.start(this, number, null, "screening", null, null)
        } else if (headsetTrigger) {
```

- [ ] **Step 2: Собрать проект**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/call/CallScreeningServiceImpl.kt
git commit -m "Переброс на автоответчик: скрининг также в рабочие (открытые) часы"
```

---

## Task 5: `ImportAudioActivity` — adb-импорт voicemail-приветствия

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/ui/ImportAudioActivity.kt`

**Interfaces:**
- Consumes: `AudioImport.importFromFile` (существует), `Settings.voicemailGreeting*` (Task 1).
- Produces: новый `kind=voicemail_greeting` в уже существующем протоколе `am start`.

Нет автотестов (та же Activity-триггерная схема, что и `screen_greeting`/
`screen_hold`) — проверяется вручную через `adb shell am start`.

- [ ] **Step 1: Добавить новый `kind`**

Заменить блок `when (kind) { ... }` (строки 42-60):

```kotlin
        val ok = when (kind) {
            "greeting_general" -> AudioImport.importFromFile(this, src, "greeting_src")?.also {
                s.amGreetingFile = it; s.amGreetingSource = 1
            }
            "screen_greeting" -> AudioImport.importFromFile(this, src, "screen_greeting_$lang")?.also {
                when (lang) {
                    "cs" -> { s.screeningGreetingFileCs = it; s.screeningGreetingSourceCs = 1 }
                    "ru" -> { s.screeningGreetingFileRu = it; s.screeningGreetingSourceRu = 1 }
                    "en" -> { s.screeningGreetingFileEn = it; s.screeningGreetingSourceEn = 1 }
                }
            }
            "screen_hold" -> AudioImport.importFromFile(this, src, "screen_hold_$lang")?.also {
                when (lang) {
                    "cs" -> s.screeningHoldFileCs = it
                    "ru" -> s.screeningHoldFileRu = it
                    "en" -> s.screeningHoldFileEn = it
                }
            }
            else -> { log.add("IMPORT: неизвестный kind=$kind"); null }
        }
```

на:

```kotlin
        val ok = when (kind) {
            "greeting_general" -> AudioImport.importFromFile(this, src, "greeting_src")?.also {
                s.amGreetingFile = it; s.amGreetingSource = 1
            }
            "screen_greeting" -> AudioImport.importFromFile(this, src, "screen_greeting_$lang")?.also {
                when (lang) {
                    "cs" -> { s.screeningGreetingFileCs = it; s.screeningGreetingSourceCs = 1 }
                    "ru" -> { s.screeningGreetingFileRu = it; s.screeningGreetingSourceRu = 1 }
                    "en" -> { s.screeningGreetingFileEn = it; s.screeningGreetingSourceEn = 1 }
                }
            }
            "screen_hold" -> AudioImport.importFromFile(this, src, "screen_hold_$lang")?.also {
                when (lang) {
                    "cs" -> s.screeningHoldFileCs = it
                    "ru" -> s.screeningHoldFileRu = it
                    "en" -> s.screeningHoldFileEn = it
                }
            }
            "voicemail_greeting" -> AudioImport.importFromFile(this, src, "voicemail_greeting_$lang")?.also {
                when (lang) {
                    "cs" -> { s.voicemailGreetingFileCs = it; s.voicemailGreetingSourceCs = 1 }
                    "ru" -> { s.voicemailGreetingFileRu = it; s.voicemailGreetingSourceRu = 1 }
                    "en" -> { s.voicemailGreetingFileEn = it; s.voicemailGreetingSourceEn = 1 }
                }
            }
            else -> { log.add("IMPORT: неизвестный kind=$kind"); null }
        }
```

- [ ] **Step 2: Обновить doc-комментарий со списком `kind`**

Заменить (строки 23-26):

```kotlin
 *   kind: greeting_general (обычное приветствие закрытых часов, Settings.amGreetingFile) |
 *         screen_greeting (приветствие скрининга, требует lang) |
 *         screen_hold (файл «после приветствия» скрининга по кругу, требует lang)
 *   lang: cs|ru|en (только для screen_*)
```

на:

```kotlin
 *   kind: greeting_general (обычное приветствие закрытых часов, Settings.amGreetingFile) |
 *         screen_greeting (приветствие скрининга, требует lang) |
 *         screen_hold (файл «после приветствия» скрининга по кругу, требует lang) |
 *         voicemail_greeting (приветствие после переброса на автоответчик, требует lang)
 *   lang: cs|ru|en (только для screen_*/voicemail_greeting)
```

- [ ] **Step 3: Собрать проект**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/ui/ImportAudioActivity.kt
git commit -m "Переброс на автоответчик: adb-импорт voicemail-приветствия"
```

---

## Task 6: UI в `MainActivity` — настройки и библиотека voicemail-приветствий

**Files:**
- Modify: `app/src/main/java/com/davnozdu/autoresponder/ui/MainActivity.kt:539-569` (секция «Скрининг звонков»)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/ui/MainActivity.kt:602` (после секции «Приветствия», перед следующей секцией)
- Modify: `app/src/main/java/com/davnozdu/autoresponder/ui/MainActivity.kt:1367-1424` (`GreetingLangSlot`)

**Interfaces:**
- Consumes: `Settings.openHoursScreeningEnabled/screeningWaitSec/voicemailMaxSec/voicemailGreeting*`
  (Task 1), уже существующие `ExpandableSection`, `SwitchRow`, `LangChip`, `importGreetingFile`.
- Produces: `GreetingLangSlot(...)` получает два новых опциональных параметра в конце
  списка (`holdFile`/`onHoldFile` становятся nullable с default `null`, плюс новый
  `slotPrefix: String = "screen"`) — существующие 3 вызова (Чешский/Русский/English для
  скрининга) НЕ меняются, продолжают работать как раньше.

Нет автотестов (Compose UI) — собрать, открыть приложение, развернуть секции,
потрогать тумблер/поля/загрузку файла.

- [ ] **Step 1: Добавить тумблер «в рабочие часы» и минутные поля в секцию «Скрининг звонков»**

Найти конец секции (текущие строки 558-569, блок с `screenDays`/`FlowRow`
и закрывающую `}` секции):

```kotlin
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

Заменить на (добавлен блок после `FlowRow`, перед закрывающей `}` секции):

```kotlin
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
                Spacer(Modifier.height(8.dp))
                var openScreenOn by remember { mutableStateOf(s.openHoursScreeningEnabled) }
                SwitchRow("Та же карточка и в обычные рабочие (открытые) часы", openScreenOn) {
                    openScreenOn = it; s.openHoursScreeningEnabled = it
                }
                Spacer(Modifier.height(8.dp))
                Text("Кнопка «Перебросить на автоответчик» на карточке — тексты и звук ниже, "
                    + "в секции «Голосовая почта».", style = MaterialTheme.typography.bodySmall)
                var waitMin by remember { mutableStateOf((s.screeningWaitSec / 60).toString()) }
                var vmMaxMin by remember { mutableStateOf((s.voicemailMaxSec / 60).toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(waitMin, { waitMin = it; it.toIntOrNull()?.let { v -> s.screeningWaitSec = (v * 60).coerceIn(5, 300) } },
                        label = { Text("Ждать решения, мин") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(vmMaxMin, { vmMaxMin = it; it.toIntOrNull()?.let { v -> s.voicemailMaxSec = (v * 60).coerceIn(5, 300) } },
                        label = { Text("Запись после переброса, мин") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
```

- [ ] **Step 2: Сделать `holdFile`/`onHoldFile` опциональными в `GreetingLangSlot` + `slotPrefix`**

Заменить сигнатуру и тело функции (текущие строки 1367-1424):

```kotlin
private fun GreetingLangSlot(
    ctx: Context, scope: kotlinx.coroutines.CoroutineScope,
    title: String, slot: String,
    source: Int, onSource: (Int) -> Unit,
    file: String, onFile: (String) -> Unit,
    text: String, onText: (String) -> Unit,
    lang: String, onLang: (String) -> Unit,
    holdFile: String, onHoldFile: (String) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    var src by remember(slot) { mutableStateOf(source) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(src == 0, { src = 0; onSource(0) }, { Text("Синтез (TTS)") })
        FilterChip(src == 1, { src = 1; onSource(1) }, { Text("Свой файл") })
    }
    if (src == 0) {
        var gLang by remember(slot) { mutableStateOf(lang) }
        Text("Язык голоса TTS (явно, независимо от того, в какой это коробке):",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LangChip("CS", "cs", gLang) { gLang = it; onLang(it) }
            LangChip("RU", "ru", gLang) { gLang = it; onLang(it) }
            LangChip("EN", "en", gLang) { gLang = it; onLang(it) }
        }
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
    Spacer(Modifier.height(6.dp))
    Text("После приветствия (по кругу, пока абонент ждёт) — необязательно:",
        style = MaterialTheme.typography.bodySmall)
    var gHold by remember(slot) { mutableStateOf(holdFile) }
    val holdPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val p = withContext(Dispatchers.IO) { importGreetingFile(ctx, uri, "screen_hold_$slot") }
            if (p != null) { onHoldFile(p); gHold = p }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { holdPicker.launch("audio/*") }) { Text("Выбрать музыку/сообщение") }
        if (gHold.isNotBlank()) {
            TextButton(onClick = { onHoldFile(""); gHold = "" }) { Text("Убрать") }
        }
    }
    Text(if (gHold.isNotBlank()) "Файл: ${java.io.File(gHold).name} (по кругу)" else "Не задано — играет обычный зуммер",
        style = MaterialTheme.typography.bodySmall)
}
```

на (сигнатура: `holdFile`/`onHoldFile` — nullable с default `null` в тех же
позициях, что и раньше — существующие 3 позиционных вызова для скрининга
остаются валидны без изменений; новый `slotPrefix` — в самом конце, тоже
с default, тоже не требует правки существующих вызовов):

```kotlin
private fun GreetingLangSlot(
    ctx: Context, scope: kotlinx.coroutines.CoroutineScope,
    title: String, slot: String,
    source: Int, onSource: (Int) -> Unit,
    file: String, onFile: (String) -> Unit,
    text: String, onText: (String) -> Unit,
    lang: String, onLang: (String) -> Unit,
    holdFile: String? = null, onHoldFile: ((String) -> Unit)? = null,
    slotPrefix: String = "screen"
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    var src by remember(slot) { mutableStateOf(source) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(src == 0, { src = 0; onSource(0) }, { Text("Синтез (TTS)") })
        FilterChip(src == 1, { src = 1; onSource(1) }, { Text("Свой файл") })
    }
    if (src == 0) {
        var gLang by remember(slot) { mutableStateOf(lang) }
        Text("Язык голоса TTS (явно, независимо от того, в какой это коробке):",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LangChip("CS", "cs", gLang) { gLang = it; onLang(it) }
            LangChip("RU", "ru", gLang) { gLang = it; onLang(it) }
            LangChip("EN", "en", gLang) { gLang = it; onLang(it) }
        }
        var gText by remember(slot) { mutableStateOf(text) }
        OutlinedTextField(gText, { gText = it; onText(it) },
            label = { Text("Текст приветствия ($title)") }, modifier = Modifier.fillMaxWidth())
    } else {
        var gFile by remember(slot) { mutableStateOf(file) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                val p = withContext(Dispatchers.IO) { importGreetingFile(ctx, uri, "${slotPrefix}_greeting_$slot") }
                if (p != null) { onFile(p); gFile = p }
            }
        }
        Button(onClick = { picker.launch("audio/*") }) { Text("Выбрать аудиофайл") }
        Text(if (gFile.isNotBlank()) "Файл: ${java.io.File(gFile).name}" else "Файл не выбран",
            style = MaterialTheme.typography.bodySmall)
    }
    // Hold-файл («по кругу, пока абонент ждёт») — только у скрининга, не у voicemail-фазы
    // (та проигрывается один раз, дальше сразу идёт запись — нечему тут крутиться по кругу).
    if (holdFile != null && onHoldFile != null) {
        Spacer(Modifier.height(6.dp))
        Text("После приветствия (по кругу, пока абонент ждёт) — необязательно:",
            style = MaterialTheme.typography.bodySmall)
        var gHold by remember(slot) { mutableStateOf(holdFile) }
        val holdPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                val p = withContext(Dispatchers.IO) { importGreetingFile(ctx, uri, "${slotPrefix}_hold_$slot") }
                if (p != null) { onHoldFile(p); gHold = p }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { holdPicker.launch("audio/*") }) { Text("Выбрать музыку/сообщение") }
            if (gHold.isNotBlank()) {
                TextButton(onClick = { onHoldFile(""); gHold = "" }) { Text("Убрать") }
            }
        }
        Text(if (gHold.isNotBlank()) "Файл: ${java.io.File(gHold).name} (по кругу)" else "Не задано — играет обычный зуммер",
            style = MaterialTheme.typography.bodySmall)
    }
}
```

- [ ] **Step 3: Добавить секцию «Голосовая почта»**

Сразу после закрывающей `}` секции `ExpandableSection("Приветствия")`
(текущая строка 602), перед следующей секцией, вставить:

```kotlin

            ExpandableSection("Голосовая почта (после переброса)") {
                Text("Приветствие, которое слышит клиент СРАЗУ после того, как вы нажали "
                    + "«Перебросить на автоответчик» на карточке скрининга (или сработал "
                    + "автоматический таймаут) — своё на каждый язык, без варианта «по кругу»: "
                    + "сразу после этой фразы стартует запись.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                GreetingLangSlot(ctx, scope, "Чешский", "cs",
                    s.voicemailGreetingSourceCs, { s.voicemailGreetingSourceCs = it },
                    s.voicemailGreetingFileCs, { s.voicemailGreetingFileCs = it },
                    s.voicemailGreetingTextCs, { s.voicemailGreetingTextCs = it },
                    s.voicemailGreetingLangCs, { s.voicemailGreetingLangCs = it },
                    slotPrefix = "voicemail")
                Spacer(Modifier.height(12.dp))
                GreetingLangSlot(ctx, scope, "Русский", "ru",
                    s.voicemailGreetingSourceRu, { s.voicemailGreetingSourceRu = it },
                    s.voicemailGreetingFileRu, { s.voicemailGreetingFileRu = it },
                    s.voicemailGreetingTextRu, { s.voicemailGreetingTextRu = it },
                    s.voicemailGreetingLangRu, { s.voicemailGreetingLangRu = it },
                    slotPrefix = "voicemail")
                Spacer(Modifier.height(12.dp))
                GreetingLangSlot(ctx, scope, "English", "en",
                    s.voicemailGreetingSourceEn, { s.voicemailGreetingSourceEn = it },
                    s.voicemailGreetingFileEn, { s.voicemailGreetingFileEn = it },
                    s.voicemailGreetingTextEn, { s.voicemailGreetingTextEn = it },
                    s.voicemailGreetingLangEn, { s.voicemailGreetingLangEn = it },
                    slotPrefix = "voicemail")
            }
```

- [ ] **Step 4: Собрать проект**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/davnozdu/autoresponder/ui/MainActivity.kt
git commit -m "Переброс на автоответчик: UI — тумблер/тайминги + секция Голосовая почта"
```

---

## После плана

Версия НЕ бампается и релиз НЕ выпускается автоматически внутри этого плана
— по заведённому в проекте циклу: собрать debug/release APK через CI,
поставить на телефон, проверить живым звонком (не-избранный номер,
рабочее время — как минимум сценарий с новым тумблером «в рабочие часы»,
нажать «Перебросить на автоответчик» вручную, отдельно проверить
автоматический таймаут не дожидаясь полных 5 минут — временно понизить
`screeningWaitSec` через настройки на пару звонков, отдельно убедиться, что
«Отклонить» по-прежнему просто вешает трубку без записи), и только после
подтверждения — версия/тег/релиз по явной команде пользователя.
