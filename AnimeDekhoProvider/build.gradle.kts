version = 2

cloudstream {
    language = "hi"
    description = "SKIP ADS on the SITE every 24 hours to Play, has Hindi Dubbed Cartoons"
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
