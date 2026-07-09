package fi.marmorikatu.app.di

import fi.marmorikatu.app.debug.DebugViewModel
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.speech.SpeechToText
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule: Module = module {
    viewModel {
        DebugViewModel(
            configStore = get(),
            connections = get(),
            lightsRepo = get(),
            climateRepo = get(),
            tvRepo = get(),
            announcementsRepo = get(),
            assistantRepo = get(),
            micPermission = get(),
            audioPlayer = get(),
            mcpApi = get(),
            serverStt = get<SpeechToText>(named("serverStt")),
            serverTts = get<SpeechOutput>(named("serverTts")),
            platformStt = get<SpeechToText>(named("platformStt")),
            platformTts = get<SpeechOutput>(named("platformTts")),
        )
    }
}
