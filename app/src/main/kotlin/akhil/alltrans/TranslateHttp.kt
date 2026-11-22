package akhil.alltrans

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared HTTP client of the online translation providers.
 *
 * The providers run in the ReAllTrans process, which is usually in the
 * background while another app is translated. Android 15+ and ROMs such as
 * HyperOS cut network access of background/frozen apps, which silently kills
 * the pooled HTTP/2 connection. OkHttp would reuse that dead connection and
 * every request multiplexed on it would hang until the read timeout. So:
 *  - after an idle period the pool is dropped and a fresh connection is used;
 *  - a request that fails with an I/O error is retried once on a new connection;
 *  - timeouts are short, so a dead connection costs seconds, not 15 s each.
 */
internal object TranslateHttp {
    private const val TAG = "AllTrans:Http"
    /** Pooled connections idle longer than this are not trusted any more. */
    private const val IDLE_RESET_MS = 20_000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            // Detects HTTP/2 connections that died while the app was in the background.
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val lastUse = AtomicLong(0)

    /** Executes [request]; the caller closes the response. */
    @Throws(IOException::class)
    fun execute(request: Request): Response {
        val now = System.currentTimeMillis()
        val previous = lastUse.getAndSet(now)
        if (previous != 0L && now - previous > IDLE_RESET_MS) {
            // Idle for a while (likely backgrounded): do not reuse old sockets.
            client.connectionPool.evictAll()
        }
        return try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            Log.w(TAG, "Request failed (${e.javaClass.simpleName}: ${e.message}), retrying on a new connection")
            client.connectionPool.evictAll()
            client.newCall(request).execute()
        }
    }
}
