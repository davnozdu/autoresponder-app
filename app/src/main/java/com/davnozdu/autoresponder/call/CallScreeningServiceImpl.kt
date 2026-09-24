package com.davnozdu.autoresponder.call

import android.telecom.Call
import android.telecom.CallScreeningService
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.Kind
import com.davnozdu.autoresponder.respond.Responder
import com.davnozdu.autoresponder.rules.AudioRouteUtil
import com.davnozdu.autoresponder.rules.AutoReplyState
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.HeadsetPolicy
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.ScreeningPolicy
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.rules.SkipPolicy
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.HistoryLogger
import kotlinx.coroutines.launch

/**
 * Screening всех входящих звонков (держим роль CALL_SCREENING).
 * Когда «закрыто» и номер подходит под маску — отклоняем и шлём авто-SMS.
 */
class CallScreeningServiceImpl : CallScreeningService() {

    private val bg = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondAllow(callDetails); return
        }

        val number = callDetails.handle?.schemeSpecificPart // tel:+420... -> +420...
        val callSubId = SimUtil.subIdFromCall(this, callDetails)  // SIM, на которую пришёл звонок
        val app = applicationContext
        val handleId = callDetails.accountHandle?.id
        val s = Settings(this)
        // Заранее — нужно решить, звать ли CallerCardNotifier ниже (до её bg.launch), не
        // дублируя вычисление skip дважды. Условие СОВПАДАЕТ с финальным screeningTrigger
        // ниже (кроме проверки ЧС — она в редком краевом случае могла бы разойтись, но ЧС
        // и так подавляет собственное уведомление отдельно, не через этот путь).
        val skipEarly = SkipPolicy.reason(this, number, s, isCall = true) != null
        val overlayOkEarly = android.provider.Settings.canDrawOverlays(this)
        val alreadyInCallEarly = getSystemService(android.telephony.TelephonyManager::class.java)
            ?.callState == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
        val likelyScreening = s.screeningEnabled && ScreeningPolicy.isInWindow(this, s) &&
            !skipEarly && overlayOkEarly && !alreadyInCallEarly
        // История и диагностика — в фон: onScreenCall выполняется на главном потоке и должен
        // ответить системе быстро, а запись в SQLite + поиск имени в книге контактов небыстрые.
        bg.launch {
            if (number != null) HistoryLogger.record(app, number, "call", "in", "входящий звонок")
            // Кто звонит и что у него в работе — из памяти, кеш CRM прогрет при запуске. НЕ
            // зовём для скрининга: CallerCardNotifier.show() — это отдельное Android-
            // уведомление (своя поверхность, свой z-order), а не наш CallerOverlay — на экране
            // оно накладывалось на карточку скрининга с кнопками Ответить/Отклонить вместо
            // того, чтобы встать под ней (живой тест, две карточки одна поверх другой). Для
            // скрининга CRM-данные подтягивает сам AnswerMachineService.runScreeningFlow в тот
            // же CallerOverlay — единственное окно, весь порядок под нашим контролем.
            if (!likelyScreening) com.davnozdu.autoresponder.notif.CallerCardNotifier.onIncoming(app, number)
            EventLog(app).add("CALL вход: handle=$handleId -> subId=$callSubId | ${SimUtil.describe(app)}")
        }
        // Главный тумблер и «отвечать на звонки» гейтят ВСЮ работу со звонками, включая чёрный
        // список. Иначе при выключенном автоответчике звонок из ЧС всё равно отклонялся, а SMS
        // не уходила (Responder.process выходит на !s.enabled) — звонки пропадали молча.
        if (!s.enabled || !s.respondCalls) { respondAllow(callDetails); return }
        if (AutoReplyState.isPaused(this)) { respondAllow(callDetails); return }
        // Чёрный список: онCalls=да -> пропускаем; нет -> отклоняем + SMS.
        val bl = HistoryDb.get(this).blacklistMatch(number, null)
        if (bl != null) {
            if (bl.onCalls) { respondAllow(callDetails); return }
            // ЧС и «звонки: отклонять» → голосовой автоответчик (в любом режиме, приоритет №1).
            // Рингтон глушим (setSilenceCall) и отвечаем сами; callPrompt — приветствие клиента.
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — ЧС → автоответчик")
            AnswerMachineService.start(this, number, bl.name, "blacklist",
                bl.callPromptLang.ifBlank { null }, bl.callPrompt)
            return
        }

        val closedReason = ClosedState.reason(this, s)
        val matches = PhoneMask.matches(number, s.allowedPrefixes)
        val skip = skipEarly

        // Без разрешения на оверлей карточка физически не нарисуется (CallerOverlay.show
        // тихо выходит) — тогда скрининг молча авто-отвечал бы и играл зуммер БЕЗ единого
        // способа его принять. Без разрешения — падаем на обычную маршрутизацию (гарнитура/
        // закрыто/звонит нормально), а не остаёмся в тупике. Найдено ревью ветки.
        val overlayOk = overlayOkEarly
        // Уже идёт звонок (владелец разговаривает — со скринингом, гарнитурой или обычный) →
        // новый non-favorite звонок НЕ должен его перехватывать: acceptRingingCall() поставил
        // бы текущий разговор на удержание. Пусть звонит как обычный call waiting. Найдено
        // ревью ветки.
        val alreadyInCall = alreadyInCallEarly

        // Скрининг: в настроенное рабочее время звонок от НЕ избранного получает видимую
        // интерактивную карточку (Принять/Отклонить), а не тихий автоответчик — проверяется
        // РАНЬШЕ гарнитуры: даже с подключённой гарнитурой (например, за рулём) владелец
        // хочет видеть карточку и мочь ответить с телефона (решение пользователя при ревью).
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
            respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
            EventLog(this).add("CALL ${number ?: "?"} — гарнитура → автоответчик")
            AnswerMachineService.start(this, number, null, "headset", s.amGreetingLang.ifBlank { null }, null)
        } else if (closedReason != null && matches && !skip) {
            if (s.callClosedMode == 1) {
                // Тумблер = голосовой автоответчик: глушим рингтон, отвечаем и обрабатываем сами.
                respondToCall(callDetails, CallResponse.Builder().setSilenceCall(true).build())
                EventLog(this).add("CALL ${number ?: "?"} — закрыто → автоответчик")
                AnswerMachineService.start(this, number, null, "closed", s.amGreetingLang.ifBlank { null }, null)
            } else {
                // Тумблер = SMS (как раньше): отклоняем без записи в пропущенные + авто-SMS.
                val response = CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build()
                respondToCall(callDetails, response)
                EventLog(this).add("CALL ${number ?: "?"} — отклонён (закрыто), SMS через ${s.replyDelayMs} мс")
                Responder.handle(this, number, null, Kind.CALL, callSubId)
            }
        } else {
            respondAllow(callDetails)
        }
    }

    private fun respondAllow(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
