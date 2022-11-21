// use an integer for version numbers
version = 4


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "Series porn (use VPN if links not working)"
     authors = listOf("Sora")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "NSFW",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=en.paradisehill.cc&sz=%size%"
}