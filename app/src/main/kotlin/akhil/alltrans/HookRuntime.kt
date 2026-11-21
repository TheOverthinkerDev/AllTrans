package akhil.alltrans

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Bridges the parts of the legacy Xposed API used by AllTrans to libxposed API 102.
 *
 * The bridge is attached in XposedModule.onModuleLoaded() and is valid for the
 * lifetime of the process.
 */
object HookRuntime {

    private const val TAG = "AllTrans"

    @Volatile
    private var module: XposedModule? = null

    private val additionalFields: MutableMap<Any, MutableMap<String, Any?>> =
        Collections.synchronizedMap(WeakHashMap())

    fun attach(module: XposedModule) {
        this.module = module
    }

    /** "Vector 2.1 (123), API 102" for the status screen. */
    fun frameworkDescription(): String {
        val m = module ?: return "unknown"
        return try {
            "${m.frameworkName} ${m.frameworkVersion} (${m.frameworkVersionCode}), API ${m.apiVersion}"
        } catch (t: Throwable) {
            "unknown (${t.message})"
        }
    }

    /**
     * Global and per-app settings as JSON, read from libxposed remote preferences
     * (written by the AllTrans app through libxposed/service, see RemotePrefs).
     * Returns null when the framework has no remote capability or AllTrans has
     * not synced yet, so the caller can fall back to the ContentProvider.
     */
    fun readRemoteSettings(packageName: String): Pair<String, String>? {
        val m = module ?: return null
        return try {
            if (m.frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) return null
            val global = m.getRemotePreferences("AllTransPref").all
            if (global.isEmpty()) return null
            val local = if (global[packageName] == true) m.getRemotePreferences(packageName).all else emptyMap()
            val gson = com.google.gson.Gson()
            Pair(gson.toJson(global), gson.toJson(local))
        } catch (t: Throwable) {
            Log.w(TAG, "Remote preferences unavailable: ${t.message}")
            null
        }
    }

    private fun requireModule(): XposedModule =
        module ?: throw IllegalStateException("HookRuntime has not been attached to XposedModule")

    // Modern frameworks keep module logs inside their manager app. Mirror them to
    // logcat as well, like XposedBridge.log() did, so `adb logcat -s AllTrans` works.
    fun log(message: String) {
        module?.log(Log.INFO, TAG, message)
        Log.i(TAG, message)
    }

    fun log(message: String, throwable: Throwable) {
        module?.log(Log.ERROR, TAG, message, throwable)
        Log.e(TAG, message, throwable)
    }

    fun log(throwable: Throwable) {
        log("AllTrans hook error", throwable)
    }

    fun hook(executable: Executable, hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
        return requireModule()
            .hook(executable)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
    }

    fun hook(method: Method, hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
        hook(method as Executable, hooker)

    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        hooker: XposedInterface.Hooker
    ): Int {
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name != methodName) continue
            try {
                hook(method, hooker)
                hooked++
            } catch (t: Throwable) {
                log("Failed to hook ${clazz.name}#${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }}", t)
            }
        }
        return hooked
    }

    fun findAndHookMethod(
        clazz: Class<*>?,
        methodName: String?,
        vararg parameterTypesAndHook: Any?
    ): XposedInterface.HookHandle {
        require(clazz != null) { "Class is null for method $methodName" }
        require(methodName != null) { "Method name is null for class ${clazz.name}" }
        val hooker = parameterTypesAndHook.lastOrNull() as? XposedInterface.Hooker
            ?: throw IllegalArgumentException("Last argument must be an XposedInterface.Hooker")
        val parameterTypes = parameterTypesAndHook
            .dropLast(1)
            .map { it as Class<*> }
            .toTypedArray()
        return hook(findMethod(clazz, methodName, parameterTypes), hooker)
    }

    private fun findMethod(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>
    ): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, *parameterTypes)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("${clazz.name}#$methodName")
    }

    fun findAndHookMethod(
        className: String?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndHook: Any?
    ): XposedInterface.HookHandle {
        require(className != null) { "Class name is null for method $methodName" }
        val clazz = Class.forName(className, false, classLoader)
        return findAndHookMethod(clazz, methodName, *parameterTypesAndHook)
    }

    fun findAndHookConstructor(
        clazz: Class<*>?,
        vararg parameterTypesAndHook: Any?
    ): XposedInterface.HookHandle {
        require(clazz != null) { "Class is null for constructor" }
        val hooker = parameterTypesAndHook.lastOrNull() as? XposedInterface.Hooker
            ?: throw IllegalArgumentException("Last argument must be an XposedInterface.Hooker")
        val parameterTypes = parameterTypesAndHook
            .dropLast(1)
            .map { it as Class<*> }
            .toTypedArray()
        val constructor: Constructor<*> = clazz.getDeclaredConstructor(*parameterTypes)
        return hook(constructor, hooker)
    }

    fun findAndHookConstructor(
        className: String?,
        classLoader: ClassLoader?,
        vararg parameterTypesAndHook: Any?
    ): XposedInterface.HookHandle {
        require(className != null) { "Class name is null for constructor" }
        val clazz = Class.forName(className, false, classLoader)
        return findAndHookConstructor(clazz, *parameterTypesAndHook)
    }

    fun invokeOriginal(member: Member, thisObject: Any?, args: Array<Any?>): Any? {
        val method = member as? Method
            ?: throw IllegalArgumentException("Original invocation is only supported for methods, got $member")
        val invoker = requireModule().getInvoker(method)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        return invoker.invoke(thisObject, *args)
    }

    fun setAdditionalInstanceField(target: Any, key: String, value: Any?) {
        synchronized(additionalFields) {
            additionalFields.getOrPut(target) { HashMap() }[key] = value
        }
    }

    fun getAdditionalInstanceField(target: Any, key: String): Any? {
        synchronized(additionalFields) {
            return additionalFields[target]?.get(key)
        }
    }
}
