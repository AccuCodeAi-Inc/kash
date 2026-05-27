plugins {
    id("kash.kmp")
}

description =
    "kash `openssl` — multiplatform cryptography tool implementing dgst (MD5/SHA-1/SHA-2 family), base64, and passwd subcommands via cryptography-kotlin (JDK provider on JVM, WebCrypto on Wasm)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:hash"))
            implementation(libs.cryptographyCore)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
        jvmMain.dependencies {
            implementation(libs.cryptographyProviderJdk)
        }
        wasmJsMain.dependencies {
            implementation(libs.cryptographyProviderWebcrypto)
        }
    }
}
