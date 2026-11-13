plugins {
    id("brobehave.android.feature")
}

// One base URL for every public web page (see gradle.properties and /docs).
val webBaseUrl = providers.gradleProperty("brobehave.webBaseUrl").get().trimEnd('/')

android {
    namespace = "com.brobehave.feature.profile"
    buildFeatures.resValues = true
    defaultConfig {
        resValue("string", "url_privacy_policy", "$webBaseUrl/privacy.html")
        resValue("string", "url_terms", "$webBaseUrl/terms.html")
        resValue("string", "url_delete_account", "$webBaseUrl/delete-account.html")
        // %1\$s is the username.
        resValue("string", "friends_invite_link", "$webBaseUrl/invite/?u=%1\$s")
    }
}
