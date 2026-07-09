package fi.marmorikatu.core.log

import co.touchlab.kermit.Logger

/** Project-wide logger; tags keep transport noise filterable. */
fun logger(tag: String): Logger = Logger.withTag(tag)
