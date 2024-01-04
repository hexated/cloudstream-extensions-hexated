import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 30

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        buildConfigField("String", "ZSHOW_API", "\"${properties.getProperty("ZSHOW_API")}\"")
    }
}

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Includes: DutaMovie, Ngefilm, Nodrakorid, Multiplex, Pusatfilm"
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
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=gomov.bio&sz=%size%"
}