package fi.marmorikatu.app.di

import fi.marmorikatu.app.debug.DebugViewModel
import fi.marmorikatu.app.shell.ShellViewModel
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.speech.SpeechToText
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule: Module = module {
    viewModel {
        ShellViewModel(
            configStore = get(),
            connections = get(),
            announcementsRepo = get(),
            assistantRepo = get(),
            micPermission = get(),
            haptics = get(),
            backgroundMode = get(),
            audioPlayer = get(),
            serverStt = get<SpeechToText>(named("serverStt")),
            serverTts = get<SpeechOutput>(named("serverTts")),
            platformStt = get<SpeechToText>(named("platformStt")),
            platformTts = get<SpeechOutput>(named("platformTts")),
        )
    }

    // The developer screen that proved the transport layer; still reachable so
    // a regression below the UI has somewhere to show itself.
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
