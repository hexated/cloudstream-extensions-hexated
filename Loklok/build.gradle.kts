// use an integer for version numbers
version = 26


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "#2 best extension based on Loklok API"
    authors = listOf("Hexated")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 0 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "Anime",
        "TvSeries",
        "Movie",
    )


    iconUrl = "https://www.google.com/s2/favicons?domain=loklok.com&sz=%size%"
}