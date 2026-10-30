package com.nxnson.vesnavi.navcore

/** Language of every text the core generates itself: instructions, distances, TFT lines. */
enum class Language {
    VI, EN;

    /** Picks the Vietnamese or English variant. */
    fun pick(vi: String, en: String): String = if (this == VI) vi else en
}
