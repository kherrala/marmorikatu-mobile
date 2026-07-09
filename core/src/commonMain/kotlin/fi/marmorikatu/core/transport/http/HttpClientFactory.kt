package fi.marmorikatu.core.transport.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Platform HTTP engine: OkHttp on Android, Darwin on iOS. */
expect fun platformHttpClient(config: HttpClient.() -> Unit = {}): HttpClient

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * Shared client for request/response calls. Streaming endpoints
 * (SSE, NDJSON) override the request timeout per call.
 */
fun createHttpClient(): HttpClient = platformHttpClient().config {
    install(ContentNegotiation) { json(AppJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
    }
    install(Logging) {
        logger = KermitKtorLogger
        level = LogLevel.INFO
    }
}

private object KermitKtorLogger : io.ktor.client.plugins.logging.Logger {
    private val log = fi.marmorikatu.core.log.logger("http")
    override fun log(message: String) = log.d { message }
}
