package com.davnozdu.autoresponder.crm

import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.LangDetect
import com.davnozdu.autoresponder.rules.PhoneMask

/**
 * Единая точка входа в CRM-функцию для обоих путей: звонки/SMS и мессенджеры.
 *
 * Логика в [CrmFlow], здесь только сведение к общему виду — номера человека и
 * правило «когда вообще можно отвечать».
 */
object CrmGate {

    /**
     * Телефоны, под которыми человек может быть записан в CRM.
     *
     * Для SMS и звонков номер известен сразу; у мессенджеров его нет вовсе — там
     * приходит имя из телефонной книги, и номер берётся оттуда же. Контакта в книге
     * нет — номера нет, в CRM не идём.
     */
    fun phonesFor(context: Context, number: String?, sender: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (!number.isNullOrBlank()) {
            out.add(number)
            out.addAll(ContactUtil.numbersForNumber(context, number))
        }
        val name = sender?.trim().orEmpty()
        if (name.isNotEmpty()) {
            if (PhoneMask.looksLikeNumber(name)) {
                out.add(name)
                out.addAll(ContactUtil.numbersForNumber(context, name))
            } else {
                out.addAll(ContactUtil.numbersForName(context, name))
            }
        }
        return out.filter { it.count { c -> c.isDigit() } >= 6 }
    }

    /** Клиент отвечает «ДА» на предложение позвать мастера. */
    fun isEscalation(context: Context, key: String, text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (!Settings(context).crmReady) return false
        val st = CrmState.get(context, key) ?: return false
        return st.kind == CrmState.Kind.ASK && CrmFlow.isYes(text)
    }

    /**
     * Ответ по CRM или null.
     *
     * @param closed сейчас «закрыто» (DND или расписание). В рабочее время функция
     *   работает, только если это разрешено отдельным тумблером: статус заказа — не
     *   «мы закрыты», и спрашивают о нём чаще всего как раз днём. Но и тогда отвечаем
     *   лишь известному клиенту и лишь на вопрос о заказе, чтобы бот не начал отвечать
     *   вместо человека.
     */
    fun reply(context: Context, s: Settings, key: String, phones: List<String>,
              text: String?, channel: String, closed: Boolean): String? {
        if (!s.crmReady || text.isNullOrBlank() || phones.isEmpty()) return null
        if (!closed && !s.crmAlwaysAnswer) return null
        // Реестр обновляем здесь же: событий немного, а ETag делает неизменившийся
        // ответ почти бесплатным.
        CrmRoster.sync(context)
        val lang = LangDetect.detect(text, s.defaultLang)
        return CrmFlow.answer(context, key, phones, text, channel, lang)
    }
}
