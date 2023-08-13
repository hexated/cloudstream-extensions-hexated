// use an integer for version numbers
version = 5


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "The Crunchyroll provider allows you to watch all the shows that are on Crunchyroll."
    authors = listOf("Sir Aguacata (KillerDogeEmpire)")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 0 // will be 3 if unspecified
    tvTypes = listOf("AnimeMovie", "Anime", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=crunchyroll.com&sz=%size%"
}
