// use an integer for version numbers
version = 12


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "[!] Requires Setup \n- StremioX allows you to use stream addons"
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
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://raw.githubusercontent.com/VectorHex/Xor/master/StremioX/icon.png"
}