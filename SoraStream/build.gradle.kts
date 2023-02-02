// use an integer for version numbers
version = 82


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "#1 best extention based on MultiAPI"
     authors = listOf("Hexated", "Sora")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Anime",
        "Movie",
    )

    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/SoraStream/Icon.png"
}