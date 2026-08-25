package com.davnozdu.autoresponder.rules

import android.content.Context
import com.davnozdu.autoresponder.data.Settings

/** Единая логика исключений: кому НЕ давать авто-ответ. Возвращает причину или null. */
object SkipPolicy {

    fun reason(context: Context, number: String?, s: Settings, isCall: Boolean): String? {
        // 1) Ручной список «Избранные»
        if (PhoneMask.isExcluded(number, s.excludedNumbers)) return "в Избранных"
        // 2) Звёздный контакт телефона
        if (s.excludeStarred && ContactUtil.isStarred(context, number)) return "звёздный контакт"
        // 3) Любой контакт из книги
        if (s.excludeContacts && ContactUtil.isKnownContact(context, number)) return "контакт из книги"
        // 4) Прошёл бы через режим «Не беспокоить» (система его и так доставит)
        if (s.respectDndPriority && DndPolicy.wouldPassThrough(context, number, isCall))
            return "приоритетный для DND"
        return null
    }
}
