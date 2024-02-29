import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 3

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "SUPERSTREAM_FIRST_API", "\"${properties.getProperty("SUPERSTREAM_FIRST_API")}\"")
        buildConfigField("String", "SUPERSTREAM_SECOND_API", "\"${properties.getProperty("SUPERSTREAM_SECOND_API")}\"")
        buildConfigField("String", "SUPERSTREAM_THIRD_API", "\"${properties.getProperty("SUPERSTREAM_THIRD_API")}\"")
        buildConfigField("String", "SUPERSTREAM_FOURTH_API", "\"${properties.getProperty("SUPERSTREAM_FOURTH_API")}\"")
    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    authors = listOf("Blatzar")

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
        "Anime",
        "TvSeries",
        "Movie",
    )


    iconUrl = "https://cdn.discordapp.com/attachments/1109266606292488297/1196694385061003334/icon.png?ex=65efee7e&is=65dd797e&hm=18fa57323826d0cbf3cf5ce7d3f5705de640f2f8d08739d41f95882d2ae0a3e0&"
}