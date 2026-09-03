package com.davnozdu.autoresponder.rules

import android.content.Context
import com.davnozdu.autoresponder.data.Settings

/** Единая логика исключений: кому НЕ давать авто-ответ. Возвращает причину или null. */
object SkipPolicy {

    fun reason(context: Context, number: String?, s: Settings, isCall: Boolean): String? {
        // 1) Ручной список «Избранные» — общий для всех каналов.
        //    Номер и маска сверяются с самим номером, запись-имя — с именем контакта из книги:
        //    список один, и человек, вписанный как «Мама Прага», должен молчать и в звонке.
        if (PhoneMask.isExcluded(number, s.favorites)) return "в Избранных"
        if (s.favoritesHaveNames()) {
            val name = ContactUtil.nameFor(context, number)
            if (!name.isNullOrBlank() && s.isFavorite(name)) return "в Избранных «$name»"
        }
        // 2) Звёздный контакт телефона
        if (s.excludeStarred && ContactUtil.isStarred(context, number)) return "звёздный контакт"
        // 3) Любой контакт из книги
        if (s.excludeContacts && ContactUtil.isKnownContact(context, number)) return "контакт из книги"
        // 4) Прошёл бы через режим «Не беспокоить» (система его и так доставит)
        if (s.respectDndPriority && DndPolicy.wouldPassThrough(context, number, isCall))
            return "приоритетный для DND"
        return null
    }

    /**
     * То же для мессенджеров (WhatsApp/Telegram), где номера нет.
     *
     * В уведомлении приходит имя из телефонной книги, а для незнакомцев — номер в формате
     * самого мессенджера. Список избранных тот же самый: [Settings.isFavorite] сравнивает
     * запись и как имя, и как номер, и как маску. Дальше — либо правила по номеру, либо
     * поиск контакта по имени, чтобы переключатели «не отвечать звёздным / всем контактам»
     * действовали и здесь.
     */
    fun reasonForSender(context: Context, sender: String?, s: Settings): String? {
        val raw = sender?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (s.isFavorite(raw)) return "в Избранных"

        if (PhoneMask.looksLikeNumber(raw)) {
            if (s.excludeStarred && ContactUtil.isStarred(context, raw)) return "звёздный контакт"
            if (s.excludeContacts && ContactUtil.isKnownContact(context, raw)) return "контакт из книги"
            return null
        }
        if (s.excludeStarred && ContactUtil.isStarredName(context, raw)) return "звёздный контакт «$raw»"
        if (s.excludeContacts && ContactUtil.isKnownName(context, raw)) return "контакт из книги «$raw»"
        return null
    }
}
