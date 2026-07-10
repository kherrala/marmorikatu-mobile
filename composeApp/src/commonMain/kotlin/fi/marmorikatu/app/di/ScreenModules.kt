package fi.marmorikatu.app.di

import fi.marmorikatu.app.screens.BussitViewModel
import fi.marmorikatu.app.screens.EnergiaViewModel
import fi.marmorikatu.app.screens.IlmastoViewModel
import fi.marmorikatu.app.screens.KotiViewModel
import fi.marmorikatu.app.screens.TapahtumatViewModel
import fi.marmorikatu.app.screens.ValotViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val kotiModule = module {
    viewModel {
        KotiViewModel(
            climateRepo = get(),
            energyRepo = get(),
            saunaRepo = get(),
            lightsRepo = get(),
            announcementsRepo = get(),
        )
    }
}

val valotModule = module {
    viewModel { ValotViewModel(lights = get()) }
}

val ilmastoModule = module {
    viewModel { IlmastoViewModel(climate = get()) }
}

val energiaModule = module {
    viewModel { EnergiaViewModel(energyRepo = get()) }
}

val bussitModule = module {
    viewModel { BussitViewModel(infoRepo = get()) }
}

val tapahtumatModule = module {
    viewModel { TapahtumatViewModel(announcementsRepo = get()) }
}

/** Every per-screen Koin module; loaded by [initKoin]. Append new modules here. */
val screenModules: List<Module> = listOf(
    kotiModule,
    valotModule,
    ilmastoModule,
    energiaModule,
    bussitModule,
    tapahtumatModule,
)
