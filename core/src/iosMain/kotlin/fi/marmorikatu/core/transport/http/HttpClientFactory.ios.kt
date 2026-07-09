package fi.marmorikatu.core.transport.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpClient(config: HttpClient.() -> Unit): HttpClient =
    HttpClient(Darwin).apply(config)
