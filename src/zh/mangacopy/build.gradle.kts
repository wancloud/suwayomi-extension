import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaCopy (WanCloud)"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        name = "拷貝漫畫 (WanCloud)"
        lang = "zh"
        baseUrl {
            mirrors(
                "https://www.mangacopy.com",
                "https://www.2026copy.com",
                "https://www.copy20.com",
            )
        }
    }

    deeplink {
        host("www.mangacopy.com")
        host("mangacopy.com")
        host("www.2026copy.com")
        host("www.copy20.com")
        path("/comic/..*")
    }
}
