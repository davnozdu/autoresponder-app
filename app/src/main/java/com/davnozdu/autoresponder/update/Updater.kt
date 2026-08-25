package com.davnozdu.autoresponder.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.davnozdu.autoresponder.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

object Updater {
    private const val REPO = "davnozdu/autoresponder-app"
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Возвращает UpdateInfo, если на GitHub есть версия новее текущей. */
    fun check(): UpdateInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string() ?: return null)
            val tag = o.optString("tag_name").trimStart('v', 'V')
            if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            apk ?: return null
            return UpdateInfo(tag, apk, o.optString("body").take(400))
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split(".", "-").mapNotNull { it.trim().toIntOrNull() }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }; val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** Скачивает APK в кэш. */
    fun download(context: Context, url: String): File? {
        return try {
            val file = File(context.cacheDir, "update.apk")
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) return null
                file.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
            }
            file
        } catch (e: Exception) { null }
    }

    /** Установка: сначала молча через root, иначе системный установщик. */
    fun install(context: Context, file: File): Boolean {
        if (rootInstall(file)) return true
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) { false }
    }

    private fun rootInstall(file: File): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r -d \"${file.absolutePath}\""))
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }
}
