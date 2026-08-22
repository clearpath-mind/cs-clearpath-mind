version = 1

cloudstream {
    description = "TopCinema (topcinemaa.co) - افلام ومسلسلات انمي واسيوية مترجمة"
    authors = listOf("clearpath-mind")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    requiresResources = false
    language = "ar"

    iconUrl = "https://topcinemaa.co/wp-content/uploads/2023/05/cropped-icon-192x192.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
