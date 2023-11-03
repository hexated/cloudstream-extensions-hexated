import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 1

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "MOENIME_API", "\"${properties.getProperty("MOENIME_API")}\"")
    }
}

cloudstream {
    language = "id"
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
    status = 0 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://cdn.discordapp.com/attachments/1170001679085744209/1170001727332810802/fast-forward.png?ex=65577405&is=6544ff05&hm=bdc8c8a9325e31ead9d528fd44a142e2254f29961679eb5196981cf9c06d2171&"
}