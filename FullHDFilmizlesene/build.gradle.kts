version = 1

cloudstream {
    authors     = listOf("keyiflerolsun")
    language    = "tr"
    description = "Sinema zevkini evinize kadar getirdik. Türkiye'nin lider Film sitesinde, en yeni filmleri Full HD izleyin."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.fullhdfilmizlesene.pw&sz=%size%"
}