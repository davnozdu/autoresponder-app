package com.davnozdu.autoresponder.msgrec

import android.app.ActivityOptions
import android.os.IBinder
import android.os.Process
import android.service.notification.StatusBarNotification
import kotlin.system.exitProcess

/** One-shot root helper, separate from the shell UID 2000 audio host.
 * Android 16 can BAL_BLOCK a valid activity PendingIntent even after sender opt-in.
 * Send the original, freshly validated notification token from a privileged caller;
 * retain the messenger's call_id/extras and do not construct an internal call intent.
 */
object MsgrAnswerDispatch {
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 0) { "root dispatcher required" }
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
            val service = Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val probe = args.contentEquals(arrayOf("--probe"))
            require(probe || args.size == 4)
            val packages = if (probe) IncomingCallNotification.packages else setOf(args[0])
            require(packages.all { it in IncomingCallNotification.packages })
            // Targeted system API avoids ACCESS_NOTIFICATIONS AppOps attribution under UID 0.
            val notifications = packages.flatMap { pkg ->
                val slice = Class.forName("android.app.INotificationManager")
                    .getMethod("getAppActiveNotifications", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(service, pkg, 0)
                @Suppress("UNCHECKED_CAST")
                (slice.javaClass.getMethod("getList").invoke(slice) as List<StatusBarNotification>)
            }
            if (probe) {
                // Read-only hardware check; no names, texts, or tokens are printed.
                println("ANSWER_DISPATCH PROBE PASS notifications=${notifications.size}")
            } else {
                require(args.size == 4)
                val pkg = args[0]
                require(pkg in IncomingCallNotification.packages)
                val uid = args[2].toInt()
                val whenMs = args[3].toLong()
                val current = notifications.singleOrNull { it.key == args[1] && it.packageName == pkg &&
                    it.uid == uid && it.notification.`when` == whenMs }
                    ?: error("incoming notification changed or removed")
                check(System.currentTimeMillis() - current.postTime in 0..30_000) { "stale incoming call" }
                val action = IncomingCallNotification.answer(current.notification)
                    ?: error("notification is no longer an incoming voice call")
                check(action.creatorPackage == pkg && action.creatorUid == uid) { "unexpected token creator" }
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                action.send(null, 0, null, null, null, null, options.toBundle())
                println("ANSWER_DISPATCH SENT package=$pkg")
            }
            exitProcess(0)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            System.err.println("ANSWER_DISPATCH ERROR ${cause.javaClass.simpleName}: ${cause.message}")
            exitProcess(1)
        }
    }
}
