version = 11

cloudstream {
    authors     = listOf("Hexated")
    language    = "tr"
    description = "Türkiye'nin en hızlı hd film izleme sitesi"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilmcehennemi.live&sz=%size%"
}