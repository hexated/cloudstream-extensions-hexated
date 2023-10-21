version = 1

cloudstream {
    authors     = listOf("Hexated")
    language    = "tr"
    description = "Watch Turkish Series with English Subtitles"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=turkish123.com&sz=%size%"
}