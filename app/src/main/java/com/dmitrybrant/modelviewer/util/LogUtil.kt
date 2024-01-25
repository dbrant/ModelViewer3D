package com.dmitrybrant.modelviewer.util

import android.text.TextUtils
import android.util.Log

/**
 * LogUtils
 */
object LogUtil {
    var TAG = "LogUtils"
    private val DEBUG: Boolean = true
    const val APP_NAME = "opengl"
    fun v(msg: String): Int {
        return v("", msg)
    }

    fun d(msg: String): Int {
        return d("", msg)
    }

    fun i(msg: String): Int {
        return i("", msg)
    }

    fun w(msg: String): Int {
        return w("", msg)
    }

    fun e(msg: String): Int {
        return e("", msg)
    }

    fun e(ex: Throwable?): Int {
        if (!DEBUG) return -1
        if (ex == null) {
            return e("", "un know error")
        }
        val ste = ex.stackTrace
        val sb = StringBuilder()
        try {
            val name = ex.javaClass.name
            val message = ex.message
            val content = if (message != null) "$name:$message" else name
            sb.append(content).append("\n")
            for (s in ste) {
                sb.append(s.toString()).append("\n")
            }
        } catch (ignore: Exception) {
        }
        return e("", sb.toString())
    }

    fun v(tag: String?, msg: String): Int {
        return if (!DEBUG) -1 else Log.v(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            getTracePrefix("v") + msg
        )
    }

    fun d(tag: String?, msg: String): Int {
        return if (!DEBUG) -1 else Log.d(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            getTracePrefix("d") + msg
        )
    }

    fun i(tag: String?, msg: String): Int {
        return if (!DEBUG) -1 else Log.i(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            getTracePrefix("i") + msg
        )
    }

    fun w(tag: String?, msg: String): Int {
        return if (!DEBUG) -1 else Log.w(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            getTracePrefix("w") + msg
        )
    }

    fun e(tag: String?, msg: String): Int {
        return if (!DEBUG) -1 else Log.e(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            getTracePrefix("e") + msg
        )
    }

    fun v(tag: String?, msg: String, tr: Throwable?): Int {
        return if (!DEBUG) -1 else Log.v(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            ": $msg", tr
        )
    }

    fun d(tag: String?, msg: String, tr: Throwable?): Int {
        return if (!DEBUG) -1 else Log.d(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            ": $msg", tr
        )
    }

    fun i(tag: String?, msg: String, tr: Throwable?): Int {
        return if (!DEBUG) -1 else Log.i(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            ": $msg", tr
        )
    }

    fun w(tag: String?, msg: String, tr: Throwable?): Int {
        return if (!DEBUG) -1 else Log.w(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            ": $msg", tr
        )
    }

    fun e(tag: String?, msg: String, tr: Throwable?): Int {
        return if (!DEBUG) -1 else Log.e(
            if (TextUtils.isEmpty(tag)) APP_NAME else tag,
            ": $msg", tr
        )
    }

    private fun getTracePrefix(logLevel: String): String {
        val sts = Throwable().stackTrace
        var st: StackTraceElement? = null
        for (i in sts.indices) {
            if (sts[i].methodName.equals(logLevel, ignoreCase = true) && i + 2 < sts.size) {
                if (sts[i + 1].methodName.equals(logLevel, ignoreCase = true)) {
                    st = sts[i + 2]
                    break
                }
                st = sts[i + 1]
                break
            }
        }
        if (st == null) {
            return ""
        }
        var clsName = st.className
        clsName = if (clsName.contains("$")) {
            clsName.substring(
                clsName.lastIndexOf(".") + 1, clsName
                    .indexOf("$")
            )
        } else {
            clsName.substring(clsName.lastIndexOf(".") + 1)
        }
        return clsName + "-> " + st.methodName + "():"
    }
}
