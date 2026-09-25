# Скрининг звонков: кнопка «Перебросить на автоответчик» + рабочие часы — дизайн

## Цель

Два независимых дополнения к уже работающему скринингу звонков (карточка
[Ответить]/[Отклонить] поверх звонилки):

1. Та же карточка должна появляться не только в своё отдельное расписание
   скрининга, но и в обычные «рабочие» (открытые) часы — новый тумблер.
2. На карточке — третья кнопка «Перебросить на автоответчик»: владелец жмёт
   её вручную, ИЛИ она срабатывает сама, если карточка провисела 5 минут без
   решения. В обоих случаях: играется отдельное «voicemail»-приветствие,
   запись перезапускается с нуля (то, что писалось во время ожидания —
   стирается), и в файл попадает только то, что клиент говорит после этого
   момента. «Отклонить» не меняется — как и сейчас, сразу вешает трубку без
   записи.

## Не меняется

- «Ответить» — без изменений.
- «Отклонить» — без изменений (мгновенный `endCall()`, без записи).
- Существующее расписание скрининга и вся текущая логика ожидания/приветствия
  во время карточки — без изменений, кроме двух точек ниже.

## 1. Новый триггер: скрининг в рабочие часы (`CallScreeningServiceImpl`)

Между существующей веткой «закрыто» и финальным `else { respondAllow(...) }`
— новая ветка. `closedReason == null` — это и есть «открыто» (тот же признак,
что уже используется в текущем коде для finalного `else`):

```kotlin
val openHoursTrigger = s.openHoursScreeningEnabled && closedReason == null &&
    matches && !skip && overlayOk && !alreadyInCall
```

`reason` для `AnswerMachineService.start(...)` остаётся `"screening"` — это
буквально тот же флоу, что и у обычного скрининга, просто с другим условием
входа. Отдельного `reason` заводить не нужно.

## 2. Третья кнопка (`CallerOverlay`)

`AcceptDecline` получает третий колбэк:

```kotlin
private data class AcceptDecline(
    val onAccept: () -> Unit, val onDecline: () -> Unit, val onTransfer: () -> Unit)
```

`showScreening(...)` получает новый параметр `onTransfer: () -> Unit`.

Вёрстка: текущий `acceptDeclineRow` строит `LinearLayout.HORIZONTAL` с двумя
кнопками по 50% ширины. Добавляется третья кнопка — **под** этим рядом, во
всю ширину (`MATCH_PARENT`), та же высота (10% экрана), синий фон (Material
Blue 800, `#1565C0`), текст «Перебросить на автоответчик». Как и у остальных
двух кнопок — `hide()` сразу по тапу, затем колбэк (без гонки двойного тапа).

## 3. Новое состояние и флоу переброса (`AnswerMachineService.runScreeningFlow`)

Третий волатильный флаг рядом с `accepted`/`declined`:

```kotlin
@Volatile private var transferred = false
```

Companion-функция рядом с `screeningAccept()`/`screeningDecline()`:

```kotlin
fun screeningTransfer() { active?.let { it.transferred = true } }
```

`waitScreeningDecision` — условие цикла дополняется `&& !transferred`, и это
же 5-минутное ожидание становится ОБЩИМ таймаутом принятия решения (см.
настройку `screeningWaitSec` ниже) — по истечении, если ничего не нажато,
`transferred` выставляется автоматически (равнозначно нажатию кнопки).

После цикла, между веткой `if (accepted)` и текущей `if (declined || !idle)
endCall(app)`:

```kotlin
if (transferred) {
    // Буфер, который писался во время ожидания карточки — не нужен, стираем.
    AmBridge.recStop(app); AmBridge.recDiscard(app)
    AmBridge.stop(app) // оборвать зуммер/музыку скрининга
    CallerOverlay.hide(app)

    val vmGreet = withTimeoutOrNull(15_000L) {
        Greeting.prepareVoicemail(app, s.screeningDefaultLang)
    }
    if (vmGreet != null) AmBridge.play(app, vmGreet, 1)

    val vmMaxSec = s.voicemailMaxSec.coerceIn(5, 300)  // новая настройка, default 60
    AmBridge.recStart(app, vmMaxSec)          // запись С НУЛЯ, новый файл
    waitIdleOr(vmMaxSec * 1000L)              // ждём: клиент договорил и положил трубку, либо истёк лимит
    if (!idle) endCall(app)
    AmBridge.stop(app); AmBridge.recStop(app)
    delay(500)                                 // тот же запас, что у recStop в runFlow — дать демону дописать WAV-заголовок

    // Тут НЕ проверяем OEM-запись (RecordingLinker) — та писала НЕПРЕРЫВНО с начала
    // звонка и не разделяет «ожидание» и «сообщение». Только свой pal_record-буфер,
    // который был перезапущен именно в этот момент.
    val recId = db.amRecInsert(number, name, start, 0, null, "voicemail")
    AmBridge.recSave(app, ownRecPath)
    val ownFile = File(ownRecPath)
    if (ownFile.exists() && ownFile.length() > 44) {
        db.amRecSetFile(recId, ownFile.absolutePath, RecordingLinker.durationMs(ownFile.absolutePath))
    } else {
        db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
    }
    EventLog(app).add("AM: скрининг — переброшено на автоответчик ${number ?: "?"}")
    return
}
```

`ownRecPath` — тот же паттерн формирования пути, что уже используется в
`runFlow`/`runScreeningFlow` (`.../recordings/<timestamp-ms>_<номер>_own.wav`).

## 4. Настройки (`Settings.kt`)

```kotlin
var openHoursScreeningEnabled: Boolean   // default false

// Раньше карточка скрининга ждала решения по amMaxMessageSec (тот же параметр,
// что и длительность записи автоответчика везде) — теперь это отдельное поле,
// чтобы 5 минут ожидания карточки не утягивали за собой длительность обычных
// сообщений автоответчика (ЧС/закрыто/гарнитура).
var screeningWaitSec: Int                // default 300

var voicemailMaxSec: Int                 // default 60 — длительность записи ПОСЛЕ переброса

// Приветствие voicemail-фазы, по одному слоту на язык — зеркалит существующий
// screeningGreetingSource/File/Text/Lang<Cs|Ru|En>, но для НОВОГО сценария:
var voicemailGreetingSourceCs: Int       // 0 = TTS, 1 = файл; default 0
var voicemailGreetingFileCs: String
var voicemailGreetingTextCs: String      // default: что-то вроде «Мы не можем сейчас связаться. Пожалуйста, оставьте сообщение.»
var voicemailGreetingLangCs: String      // default "cs"
// + Ru, En — по аналогии
```

`waitScreeningDecision` использует `s.screeningWaitSec` вместо текущего
`s.amMaxMessageSec` для длительности ожидания карточки. `amMaxMessageSec`
дальше используется только там же, где и сейчас (длительность записи в
ЧС/закрыто/гарнитура-флоу) — семантика не меняется, просто перестаёт
неявно управлять ещё и ожиданием карточки.

## 5. Приветствие voicemail-фазы (`Greeting.kt`)

Новая функция `prepareVoicemail(ctx, langSlot): String?` — по структуре как
первая часть `prepareScreening` (выбор источника TTS/файл по слоту языка,
конвертация, кэш `greeting-<hash>.pcm`), но БЕЗ хвоста (ни бип-петля, ни
тайлинг hold-файла) — voicemail-приветствие проигрывается один раз, следом
сразу стартует запись. Конкретное разделение общего кода с `prepareScreening`
(общая приватная функция выбора источника + отдельные обёртки) — на
усмотрение реализации, требование только к внешнему поведению.

## 6. Запись и прослушивание

Ничего нового строить не нужно — `am_rec`/`AmRecordingsActivity` (список +
встроенный плеер) уже существуют и используются текущим автоответчиком.
Новая запись просто попадает туда же, с `reason="voicemail"` в `amRecInsert`
для отличия от обычных сообщений автоответчика в истории/диагностике.

## 7. UI в `MainActivity`

- Тумблер «Скрининг в рабочие часы» — рядом с существующим тумблером
  расписания скрининга.
- Поле «Ждать решения, сек» (`screeningWaitSec`, default 300) и «Длительность
  записи после переброса, сек» (`voicemailMaxSec`, default 60) — как обычные
  `OutlinedTextField` с числовой клавиатурой, тот же паттерн, что у
  `replyDelay`/`batchWait`.
- Новая секция «Голосовая почта» — три блока (Чешский/Русский/English), по
  структуре как существующие блоки приветствий скрининга (источник TTS/файл,
  текст или загрузка файла) — БЕЗ поля «После приветствия по кругу» (там оно
  не нужно, в отличие от скрининга).

## Тесты

Весь call-flow (Telecom/аудио/root-демон) не тестируется юнитами в этом
проекте в принципе — проверяется живыми звонками, как и весь остальной
скрининг (см. `docs/superpowers/specs/2026-09-24-call-screening-design.md`).
Новая ветка маршрутизации (`openHoursTrigger`) — inline-условие в
`onScreenCall`, тот же паттерн, что уже у `headsetTrigger`/`closedReason`
веток — тоже не выносится в отдельно тестируемую чистую функцию, живёт по
тому же прецеденту.

## Открытые вопросы / решения по ревью (2026-09-25)

1. **Третья кнопка не убирает существующие** — «Ответить»/«Отклонить» без
   изменений в размере/поведении, третья — под ними, во всю ширину.
2. **«Отклонить» ≠ переброс** — сознательное решение пользователя: остаются
   двумя разными действиями с разным результатом (отклонить = тишина без
   записи; перебросить = voicemail-приветствие + запись).
3. **Ручная кнопка срабатывает мгновенно**, без каких-либо задержек —
   5-минутный таймаут относится только к автоматическому переходу, когда
   владелец вообще не отреагировал.
4. **OEM-запись не используется для voicemail-фазы** — только свой
   `pal_record`-буфер, перезапущенный в момент переброса, потому что OEM
   пишет непрерывно с начала звонка и не даёт чистого среза «только
   сообщение».
