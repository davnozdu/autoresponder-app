package com.davnozdu.autoresponder.msgrec

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.os.Build
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
            val activityProbe = args.contentEquals(arrayOf("--activity-probe"))
            require(probe || activityProbe || args.size == 4)
            val packages = when {
                activityProbe -> setOf("com.android.shell", "android")
                probe -> IncomingCallNotification.packages
                else -> setOf(args[0])
            }
            require(activityProbe || packages.all { it in IncomingCallNotification.packages })
            // Targeted system API avoids ACCESS_NOTIFICATIONS AppOps attribution under UID 0.
            val notifications = packages.flatMap { pkg ->
                val slice = Class.forName("android.app.INotificationManager")
                    .getMethod("getAppActiveNotifications", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(service, pkg, 0)
                @Suppress("UNCHECKED_CAST")
                (slice.javaClass.getMethod("getList").invoke(slice) as List<StatusBarNotification>)
            }
            if (activityProbe) {
                val current = notifications.singleOrNull { it.tag == "autoresp-msgr-activity-probe" }
                    ?: error("activity probe notification missing")
                val action = current.notification.contentIntent ?: error("activity probe token missing")
                check(action.isActivity && action.creatorUid in setOf(0, 1000, 2000))
                val target = PendingIntent::class.java.getDeclaredMethod("getTarget")
                    .apply { isAccessible = true }.invoke(action)
                val original = Class.forName("android.app.IActivityManager").methods.single {
                    it.name == "getIntentForIntentSender" && it.parameterCount == 1
                }.invoke(activityManager(), target) as Intent
                check(original.component?.packageName == "com.davnozdu.autoresponder" &&
                    original.component?.className == "com.davnozdu.autoresponder.ui.SetFlagActivity" &&
                    original.getStringExtra("key") == "__probe_noop__")
                // This protected activity logs an unknown key and finishes; no setting changes.
                println("ANSWER_DISPATCH ACTIVITY PROBE sendResult=${send(action)} creatorUid=${action.creatorUid}")
            } else if (probe) {
                // A one-shot broadcast with no receiver. Exercise the same send path without
                // taking a call, launching UI, changing settings, or sending a message.
                val result = send(probeToken())
                println("ANSWER_DISPATCH PROBE PASS notifications=${notifications.size} sendResult=$result")
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
                val result = send(action)
                println("ANSWER_DISPATCH SENT package=$pkg result=$result")
            }
            exitProcess(0)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            System.err.println("ANSWER_DISPATCH ERROR ${cause.javaClass.simpleName}: ${cause.message}")
            exitProcess(1)
        }
    }

    private fun activityManager(): Any = Class.forName("android.app.ActivityManager")
        .getDeclaredMethod("getService").apply { isAccessible = true }.invoke(null)

    /** Android 16 PendingIntent.send() unconditionally dereferences ActivityThread, which a
     * standalone app_process does not have. Call the same Binder method with a null app thread;
     * the kernel still supplies the actual root sender UID/PID. Preserve the target and token.
     */
    private fun send(action: PendingIntent): Int {
        val target = PendingIntent::class.java.getDeclaredMethod("getTarget")
            .apply { isAccessible = true }.invoke(action)
        val token = PendingIntent::class.java.getDeclaredField("mWhitelistToken")
            .apply { isAccessible = true }.get(action)
        // Android 16 only considers sender BAL/SAW permissions in ALLOW_ALWAYS mode (3).
        // ALLOWED is deprecated and was BAL_BLOCK even for our root sender on this phone.
        // compileSdk 35 does not expose the new constant; gate its pinned AOSP value by API.
        val mode = if (Build.VERSION.SDK_INT >= 36) 3
            else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
        val method = Class.forName("android.app.IActivityManager").methods.single {
            it.name == "sendIntentSender" && it.parameterCount == 9
        }
        val result = method.invoke(activityManager(), null, target, token, 0,
            null, null, null, null, options) as Int
        check(result >= 0) { "answer token rejected: $result" }
        return result
    }

    private fun probeToken(): PendingIntent {
        val method = Class.forName("android.app.IActivityManager").methods.single {
            it.name == "getIntentSenderWithFeature" && it.parameterCount == 11
        }
        val intent = Intent("com.davnozdu.autoresponder.msgrec.ANSWER_DISPATCH_PROBE_NOOP")
            .setPackage("com.davnozdu.autoresponder")
        val target = method.invoke(activityManager(), 1 /* INTENT_SENDER_BROADCAST */, "android",
            null, null, null, 0, arrayOf(intent), arrayOfNulls<String>(1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT, null, 0)
        return PendingIntent::class.java.getDeclaredConstructor(Class.forName("android.content.IIntentSender"))
            .apply { isAccessible = true }.newInstance(target)
    }
}
