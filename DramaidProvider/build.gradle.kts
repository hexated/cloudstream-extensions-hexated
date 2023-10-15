// use an integer for version numbers
version = 10


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Include: Oppadrama"
     authors = listOf("Hexated")

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
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=dramaid.asia&sz=%size%"
}