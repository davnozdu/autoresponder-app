package com.davnozdu.autoresponder.ui

import android.app.Activity
import android.os.Bundle
import com.davnozdu.autoresponder.data.AudioImport
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import java.io.File

/**
 * Импорт аудиофайла приветствия/hold-лупа в обход UI — тот же код, что и у пикера в
 * настройках ([AudioImport]), но без ручного клика в приложении. Файл сначала кладётся
 * adb push'ем в публичное место (видно приложению без разрешений — /sdcard/Download и
 * т.п.), эта Activity копирует его в приватную папку и штатно проставляет Settings.
 *
 * `am start`, а не `am broadcast` — тот же приём, что и в [com.davnozdu.autoresponder.call.AmTestActivity]:
 * broadcast к фоновому/замороженному приложению попадает под defer-политику OxygenOS и может
 * не дойти вовсе, запуск активности будит процесс мгновенно (как настоящий звонок).
 *
 *   adb shell am start -n com.davnozdu.autoresponder/.ui.ImportAudioActivity \
 *     --es kind screen_greeting --es lang cs --es src /sdcard/Download/greeting_cs.mp3
 *
 *   kind: greeting_general (обычное приветствие закрытых часов, Settings.amGreetingFile) |
 *         screen_greeting (приветствие скрининга, требует lang) |
 *         screen_hold (файл «после приветствия» скрининга по кругу, требует lang)
 *   lang: cs|ru|en (только для screen_*)
 *   src: абсолютный путь на устройстве (публичное хранилище, НЕ files/ приложения)
 */
class ImportAudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = EventLog(this)
        val kind = intent?.getStringExtra("kind") ?: ""
        val lang = intent?.getStringExtra("lang") ?: "cs"
        val srcPath = intent?.getStringExtra("src")
        val src = srcPath?.let { File(it) }
        if (src == null || !src.exists()) {
            log.add("IMPORT: файл не найден ($srcPath)")
            finish(); return
        }
        val s = Settings(this)
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
        log.add(if (ok != null) "IMPORT: $kind/$lang <- ${src.name} -> OK ($ok)"
                else "IMPORT: $kind/$lang — ошибка копирования ${src.name}")
        finish()
    }
}
