version = 1


cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "YOU HAVE SKIP ADS on the SITE each 24 hours, Hindi dubbed cartoons"
    authors = listOf("anon")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime",
        "Cartoon"
    )

    iconUrl = "https://animedekho.com/wp-content/uploads/2023/07/AnimeDekho-Logo-300x-1.pngg"
}
