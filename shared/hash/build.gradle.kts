plugins {
    id("kash.kmp")
}

description =
    "kash shared hash — multiplatform MD5/SHA-1/SHA-2 digest API (`Digest`, `Sha1`) backed by `java.security.MessageDigest` on JVM and `kotlincrypto` on Kotlin/Wasm."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.kotlincryptoHashSha1)
                implementation(libs.kotlincryptoHashSha2)
                implementation(libs.kotlincryptoHashMd)
            }
        }
    }
}
