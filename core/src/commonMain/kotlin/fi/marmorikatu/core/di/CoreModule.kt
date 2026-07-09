package fi.marmorikatu.core.di

import fi.marmorikatu.core.audio.AudioPlayer
import fi.marmorikatu.core.audio.AudioRecorder
import fi.marmorikatu.core.audio.MicPermission
import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.lifecycle.AppForeground
import fi.marmorikatu.core.lifecycle.ConnectionManager
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.AssistantRepository
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.repository.DefaultAnnouncementsRepository
import fi.marmorikatu.core.repository.DefaultAssistantRepository
import fi.marmorikatu.core.repository.DefaultClimateRepository
import fi.marmorikatu.core.repository.DefaultEnergyRepository
import fi.marmorikatu.core.repository.DefaultInfoRepository
import fi.marmorikatu.core.repository.DefaultLightsRepository
import fi.marmorikatu.core.repository.DefaultSaunaRepository
import fi.marmorikatu.core.repository.DefaultTvRepository
import fi.marmorikatu.core.repository.EnergyRepository
import fi.marmorikatu.core.repository.InfoRepository
import fi.marmorikatu.core.repository.LightsRepository
import fi.marmorikatu.core.repository.SaunaRepository
import fi.marmorikatu.core.repository.TvRepository
import fi.marmorikatu.core.speech.PlatformStt
import fi.marmorikatu.core.speech.PlatformTts
import fi.marmorikatu.core.speech.ServerStt
import fi.marmorikatu.core.speech.ServerTts
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.speech.SpeechToText
import fi.marmorikatu.core.transport.bridge.BridgeApi
import fi.marmorikatu.core.transport.http.createHttpClient
import fi.marmorikatu.core.transport.mcp.DefaultMcpApi
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mcp.McpConnection
import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttasticClient
import fi.marmorikatu.core.transport.widgets.BusApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Platform name used in the MQTT client id (`android` / `ios`). */
expect val platformName: String

val coreModule: Module = module {
    single { ConfigStore() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { createHttpClient() }

    single<MqttClient> { MqttasticClient(get()) }
    single { McpConnection(get(), get()) }
    single<McpApi> { DefaultMcpApi(get()) }
    single { BridgeApi(get(), get()) }
    single { BusApi(get(), get()) }

    single<LightsRepository> { DefaultLightsRepository(get(), get(), get()) }
    single<ClimateRepository> { DefaultClimateRepository(get(), get(), get()) }
    single<EnergyRepository> { DefaultEnergyRepository(get(), get(), get()) }
    single<SaunaRepository> { DefaultSaunaRepository(get()) }
    single<TvRepository> { DefaultTvRepository(get()) }
    single<AnnouncementsRepository> { DefaultAnnouncementsRepository(get(), get()) }
    single<AssistantRepository> { DefaultAssistantRepository(get()) }
    single<InfoRepository> { DefaultInfoRepository(get(), get()) }

    single { AppForeground() }
    single {
        ConnectionManager(
            mqtt = get(), bridge = get(), announcements = get(), lights = get(),
            appForeground = get(), configStore = get(), scope = get(),
            platformName = platformName,
        )
    }

    single { AudioRecorder() }
    single { AudioPlayer() }
    single { MicPermission() }

    single<SpeechToText>(named("serverStt")) { ServerStt(get(), get()) }
    single<SpeechOutput>(named("serverTts")) { ServerTts(get(), get()) }
    single<SpeechToText>(named("platformStt")) { PlatformStt() }
    single<SpeechOutput>(named("platformTts")) { PlatformTts() }
}
