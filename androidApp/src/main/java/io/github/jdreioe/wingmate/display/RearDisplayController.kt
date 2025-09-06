package io.github.jdreioe.wingmate.display

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.os.Looper
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

// Reflection-based rear display controller (Window Area API) with graceful fallback.
// Step 1: Detect and attempt to start a rear display session via reflection (API 34+ only).
// Step 2: If any step fails, remain inactive and let Presentation fallback handle display.
class RearDisplayController(private val appContext: Context) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var sessionCloser: (() -> Unit)? = null

    fun startIfPossible(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        if (_active.value) return
        runCatching { tryStartReflective(activity) }
            .onFailure { Log.d("RearDisplay", "Rear display not available: ${'$'}it") }
    }

    fun stopIfActive() {
        runCatching { sessionCloser?.invoke() }
        sessionCloser = null
        _active.value = false
    }

    private fun mainExecutor(): Executor = Executor { r -> r.run() }

    private fun tryStartReflective(activity: Activity) {
        // Load controller class
        val controllerClass = findFirstClass(
            listOf(
                "androidx.window.area.WindowAreaController",
                "androidx.window.extensions.area.WindowAreaController"
            )
        ) ?: return.also { Log.d("RearDisplay", "No WindowAreaController class found") }
        val controller =
            // Try no-arg getOrCreate()
            runCatching { controllerClass.getMethod("getOrCreate").invoke(null) }.getOrNull()
                // Fallback to getOrCreate(Context)
                ?: runCatching { controllerClass.getMethod("getOrCreate", Context::class.java).invoke(null, appContext) }.getOrNull()
                ?: return

        // Obtain rear display area from controller (rearDisplay or getRearDisplay)
        val rear = runCatching { controllerClass.getMethod("getRearDisplay").invoke(controller) }.getOrNull()
            ?: runCatching { controllerClass.getMethod("getRearDisplayArea").invoke(controller) }.getOrNull()
            ?: runCatching { controllerClass.getDeclaredField("rearDisplay").apply { isAccessible = true }.get(controller) }.getOrNull()
            ?: return.also { Log.d("RearDisplay", "No rear display area accessor available") }

        // Try checking status (optional)
        val rearClass = rear.javaClass
        val statusObj = runCatching { rearClass.getMethod("getStatus").invoke(rear) }.getOrNull()
            ?: runCatching { rearClass.getDeclaredField("status").apply { isAccessible = true }.get(rear) }.getOrNull()
        val statusStr = statusObj?.toString() ?: "UNKNOWN"
        Log.d("RearDisplay", "rear status=${'$'}statusStr")

        // Load presentation mode enum and callback interface
        val modeClass = findFirstClass(
            listOf(
                "androidx.window.area.RearDisplayPresentationMode",
                "androidx.window.extensions.area.RearDisplayPresentationMode"
            )
        ) ?: return.also { Log.d("RearDisplay", "No RearDisplayPresentationMode class") }
        val callbackClass = findFirstClass(
            listOf(
                "androidx.window.area.WindowAreaSessionCallback",
                "androidx.window.extensions.area.WindowAreaSessionCallback"
            )
        ) ?: return.also { Log.d("RearDisplay", "No WindowAreaSessionCallback class") }

        val presentationMode = runCatching { modeClass.getField("PRESENTATION").get(null) }.getOrNull() ?: return
        val exec = mainExecutor()

        // Capture session instance to enable close()
        var sessionObj: Any? = null
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            InvocationHandler { _, method: Method, args: Array<out Any?>? ->
                when (method.name) {
                    "onSessionStarted" -> {
                        // Signature may be onSessionStarted(session)
                        sessionObj = args?.getOrNull(0)
                        _active.value = true
                    }
                    // Different versions may have onSessionEnded() or onSessionEnded(Throwable?)
                    "onSessionEnded" -> {
                        _active.value = false
                        sessionObj = null
                    }
                    "onSessionError" -> {
                        _active.value = false
                        sessionObj = null
                    }
                }
                null
            }
        )

        // startRearDisplaySession(Activity, RearDisplayPresentationMode, Executor, WindowAreaSessionCallback)
        val startMethod = runCatching {
            rearClass.getMethod(
                "startRearDisplaySession",
                Activity::class.java,
                modeClass,
                Executor::class.java,
                callbackClass
            )
        }.getOrNull()
            ?: rearClass.methods.firstOrNull { m ->
                m.parameterTypes.size == 4 &&
                Activity::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1].isAssignableFrom(presentationMode.javaClass) &&
                Executor::class.java.isAssignableFrom(m.parameterTypes[2]) &&
                callbackClass.isAssignableFrom(m.parameterTypes[3])
            }
            ?: return.also { Log.d("RearDisplay", "No compatible start method on rear area") }

        val invoked = runCatching { startMethod.invoke(rear, activity, presentationMode, exec, callback) }
        if (invoked.isFailure) {
            Log.d("RearDisplay", "startRearDisplaySession failed trying standard signature: ${'$'}{invoked.exceptionOrNull()}")
            // Try alternative signatures: (Activity|Context, Executor, Callback) without mode
            val altMethod = rearClass.methods.firstOrNull { m ->
                m.name.lowercase().contains("start") &&
                m.parameterTypes.size == 3 &&
                (Activity::class.java.isAssignableFrom(m.parameterTypes[0]) || Context::class.java.isAssignableFrom(m.parameterTypes[0])) &&
                Executor::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                callbackClass.isAssignableFrom(m.parameterTypes[2])
            }
            if (altMethod != null) {
                runCatching { altMethod.invoke(rear, activity, exec, callback) }
                    .onFailure { Log.d("RearDisplay", "Alternate start method failed: ${'$'}it") }
            }
        }

        // Prepare closer if session exposes close()
        sessionCloser = {
            runCatching {
                val s = sessionObj ?: return@runCatching
                val m = s.javaClass.methods.firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                m?.invoke(s)
            }
        }
        if (_active.value) {
            showToast("Rear display session active")
        } else {
            showToast("Rear display session requested")
        }
    }

    private fun findFirstClass(candidates: List<String>): Class<*>? {
        for (name in candidates) {
            val c = runCatching { Class.forName(name) }.getOrNull()
            if (c != null) return c
        }
        return null
    }

    private fun showToast(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
