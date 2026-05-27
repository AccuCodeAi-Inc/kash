plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.kotlinSerializationGradlePlugin)
    implementation(libs.ktlintGradlePlugin)
    implementation(libs.antlrKotlinGradlePlugin)
    implementation(libs.composeMultiplatformGradlePlugin)
    implementation(libs.kotlinComposeCompilerGradlePlugin)
    implementation(libs.vanniktechMavenPublishGradlePlugin)
}
