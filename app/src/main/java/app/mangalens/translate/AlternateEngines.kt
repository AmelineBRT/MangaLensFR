package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * MyMemory's public translation endpoint. No API key is needed, but the service
 * has daily limits and may be unavailable; TranslationService treats it as a
 * fallback rather than promising unlimited use.
 */
class MyMemoryEngine : TranslationEngine {
    override val label = "MyMemory · gratuit"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            items.map { source ->
                if (source.isBlank() || Script.cjkCount(source) == 0) return@map source
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("api.mymemory.translated.net")
                    .addPathSegment("get")
                    .addQueryParameter("q", source)
                    .addQueryParameter("langpair", sourceCode(source, lang) + "|fr")
                    .build()
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("MyMemory HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    if (root.optString("responseStatus") !in listOf("200", "202")) {
                        throw RuntimeException(root.optString("responseDetails", "MyMemory indisponible"))
                    }
                    root.optJSONObject("responseData")?.optString("translatedText")
                        ?.takeIf { it.isNotBlank() } ?: source
                }
            }
        }

    private fun sourceCode(text: String, lang: SourceLang): String =
        if (Script.cjkCount(text) == 0) "en" else when (lang) {
            SourceLang.EN -> "en"
            SourceLang.KO -> "ko"
            SourceLang.JA -> "ja"
            SourceLang.ZH -> "zh"
            SourceLang.AUTO -> when {
                Script.koreanCount(text) > Script.cjkCount(text) * 0.45f -> "ko"
                Script.japaneseCount(text) > 0 -> "ja"
                else -> "zh"
            }
        }
}

/**
 * DeepL API engine. A Free API key (usually ending in :fx) uses api-free;
 * other keys use the paid API host. The provider's own usage limits and billing
 * apply to the supplied key.
 */
class DeepLEngine(private val apiKey: String) : TranslationEngine {
    override val label = "DeepL · API"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw RuntimeException("Ajoute ta clé API DeepL dans les réglages.")
            if (items.isEmpty()) return@withContext emptyList()
            val host = if (apiKey.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            val form = FormBody.Builder().add("target_lang", "FR")
            items.forEach { form.add("text", it) }
            val request = Request.Builder()
                .url("https://$host/v2/translate")
                .header("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                .post(form.build())
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                    throw RuntimeException("DeepL HTTP ${resp.code}" + if (detail.isBlank()) "" else ": $detail")
                }
                val arr = JSONObject(body).optJSONArray("translations")
                    ?: throw RuntimeException("Réponse DeepL invalide")
                val out = (0 until arr.length()).map { arr.optJSONObject(it)?.optString("text").orEmpty() }
                if (out.size != items.size) throw RuntimeException("Réponse DeepL incomplète")
                out
            }
        }
}
