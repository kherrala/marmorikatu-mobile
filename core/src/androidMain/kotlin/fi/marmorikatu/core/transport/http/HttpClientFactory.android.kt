package fi.marmorikatu.core.transport.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformHttpClient(config: HttpClient.() -> Unit): HttpClient =
    HttpClient(OkHttp).apply(config)
