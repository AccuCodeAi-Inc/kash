plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:kash:shell"))
            api(project(":tools:kash:git"))
            api(project(":tools:kash:jq"))
            api(project(":tools:kash:nano"))
            api(project(":tools:kash:vi"))
            api(project(":tools:kash:less"))
            api(project(":tools:kash:fzf"))
            api(project(":tools:kash:ed"))
        }
    }
}
