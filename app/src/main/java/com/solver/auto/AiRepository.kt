package com.solver.auto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class Msg(val role: String, val content: String)
data class ChatReq(val model: String, val messages: List<Msg>, val temperature: Double = 0.0)
data class Choice(val message: Msg?)
data class ChatResp(val choices: List<Choice>?)

interface AiApi {
    @POST("v1/chat/completions")
    suspend fun complete(@Body req: ChatReq, @Header("Authorization") auth: String): ChatResp
}

object AiRepository {

    private suspend fun <T> withBackoff(times: Int = 2, block: suspend () -> T?): T? {
        var d = 1000L
        repeat(times - 1) { i ->
            try { block()?.let { return it } } catch (e: Exception) { if (i == times - 2) throw e }
            delay(d); d *= 2
        }
        return block()
    }

    suspend fun solve(ctx: Context, text: String): String? = withContext(Dispatchers.IO) {
        val p = ctx.getSharedPreferences("solver", Context.MODE_PRIVATE)
        val key = Secrets.getKey(ctx)
        if (key.isBlank()) return@withContext null

        val providers = listOf(
            Triple(p.getString("base_url", "https://api.openai.com/")!!.trimEnd('/') + "/",
                p.getString("model", "gpt-4o-mini")!!.ifBlank { "gpt-4o-mini" }, key),
            Triple(p.getString("base_url2", "").orEmpty().trimEnd('/') + "/",
                p.getString("model2", "").orEmpty().ifBlank { "gpt-4o-mini" },
                p.getString("key2", "").orEmpty().ifBlank { key })
        ).filter { it.first != "/" }

        for ((base, model, k) in providers) {
            val res = withTimeoutOrNull(25_000) {
                withBackoff(2) { callOnce(base, model, k, text, p) }
            }
            if (res != null) return@withContext res
        }
        null
    }

    private suspend fun callOnce(
        base: String, model: String, key: String,
        text: String, p: android.content.SharedPreferences
    ): String? {
        val host = base.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val pins = p.getString("pins", "").orEmpty()
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
        if (pins.isNotBlank()) {
            val cp = CertificatePinner.Builder().also { b ->
                pins.split(",").forEach { b.add(host, it.trim()) }
            }.build()
            builder.certificatePinner(cp)
        }
        val api = Retrofit.Builder().baseUrl(base).client(builder.build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(AiApi::class.java)

        val req = ChatReq(model, listOf(
            Msg("system", "Kamu mesin penjawab ujian. Balas HANYA teks persis opsi yang benar, atau hurufnya (A/B/C/D/E). Tanpa penjelasan."),
            Msg("user", text)
        ))
        return try {
            api.complete(req, "Bearer $key").choices?.firstOrNull()?.message?.content
                ?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }
}
