package com.davnozdu.autoresponder.rules

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactUtil {

    private data class Lookup(val known: Boolean, val starred: Boolean)

    private fun lookup(context: Context, number: String?): Lookup {
        if (number.isNullOrBlank()) return Lookup(false, false)
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.STARRED),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val starred = c.getInt(1) == 1
                    return Lookup(true, starred)
                }
            }
            Lookup(false, false)
        } catch (e: Exception) {
            Lookup(false, false)
        }
    }

    fun isKnownContact(context: Context, number: String?): Boolean = lookup(context, number).known

    /** Имя контакта по номеру или null. */
    fun nameFor(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            context.contentResolver.query(uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)?.ifBlank { null }
            }
            null
        } catch (e: Exception) { null }
    }
    fun isStarred(context: Context, number: String?): Boolean = lookup(context, number).starred
}
