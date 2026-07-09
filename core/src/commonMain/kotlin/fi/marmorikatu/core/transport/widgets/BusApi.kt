package fi.marmorikatu.core.transport.widgets

import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.model.BusDepartures
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * The Nysse bus service is the only widget with a directly published host
 * port (:3010). Weather/news/calendar are container-internal and reached
 * through MCP tools instead ([fi.marmorikatu.core.transport.mcp.McpApi]).
 */
class BusApi(
    private val httpClient: HttpClient,
    private val configStore: ConfigStore,
) {
    suspend fun departures(): BusDepartures =
        httpClient.get("${configStore.config.value.busUrl}/api/departures").body()
}
