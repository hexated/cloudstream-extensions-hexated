import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 9

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "ANICHI_API", "\"${properties.getProperty("ANICHI_API")}\"")
        buildConfigField("String", "ANICHI_SERVER", "\"${properties.getProperty("ANICHI_SERVER")}\"")
        buildConfigField("String", "ANICHI_ENDPOINT", "\"${properties.getProperty("ANICHI_ENDPOINT")}\"")
        buildConfigField("String", "ANICHI_APP", "\"${properties.getProperty("ANICHI_APP")}\"")


    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
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
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://media.discordapp.net/attachments/1059306855865782282/1123970193274712096/Anichi.png"
}