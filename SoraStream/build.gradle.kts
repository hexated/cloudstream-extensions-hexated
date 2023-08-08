import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 155

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "SORA_API", "\"${properties.getProperty("SORA_API")}\"")
        buildConfigField("String", "SORAHE", "\"${properties.getProperty("SORAHE")}\"")
        buildConfigField("String", "SORAXA", "\"${properties.getProperty("SORAXA")}\"")
        buildConfigField("String", "SORATED", "\"${properties.getProperty("SORATED")}\"")
        buildConfigField("String", "DUMP_API", "\"${properties.getProperty("DUMP_API")}\"")
        buildConfigField("String", "DUMP_KEY", "\"${properties.getProperty("DUMP_KEY")}\"")
        buildConfigField("String", "GOMOVIES_KEY", "\"${properties.getProperty("GOMOVIES_KEY")}\"")
        buildConfigField("String", "CRUNCHYROLL_BASIC_TOKEN", "\"${properties.getProperty("CRUNCHYROLL_BASIC_TOKEN")}\"")
        buildConfigField("String", "CRUNCHYROLL_REFRESH_TOKEN", "\"${properties.getProperty("CRUNCHYROLL_REFRESH_TOKEN")}\"")


    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "#1 best extention based on MultiAPI"
     authors = listOf("Hexated", "Sora")

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
        "Anime",
        "Movie",
    )

    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/SoraStream/Icon.png"
}