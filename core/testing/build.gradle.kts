plugins {
    id("brobehave.jvm.library")
}

dependencies {
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.kotlinx.coroutines.test)
    api(libs.mockk)
    api(libs.turbine)
}
