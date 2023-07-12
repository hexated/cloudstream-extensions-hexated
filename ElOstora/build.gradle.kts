// use an integer for version numbers
version = 1


cloudstream {
    language = "ar"
    // All of these properties are optional, you can safely remove them

    description = "El Ostora TV livestreams"
    authors = listOf("KingLucius")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://raw.githubusercontent.com/KingLucius/cs-hx/master/YacienTVProvider/icon.png"
}