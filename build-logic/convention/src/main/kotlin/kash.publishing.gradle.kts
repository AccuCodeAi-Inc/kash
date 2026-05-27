import com.vanniktech.maven.publish.MavenPublishBaseExtension

/*
 * Convention plugin for Maven publishing.
 *
 * Wraps Vanniktech Maven Publish so every kash module ends up with the same
 * coordinates, POM metadata, and (optionally) Nexus routing. KMP modules
 * publish the root multiplatform module + per-target artifacts automatically;
 * non-KMP modules publish a single jar.
 *
 *   ./gradlew publishToMavenLocal        # everything to ~/.m2
 *   NEXUS_URL=... ./gradlew publish      # release/snapshot routed by version
 */

plugins {
    id("com.vanniktech.maven.publish")
}

val kashGroupId = "com.accucodeai.kash"
group = kashGroupId

// Project path -> dotted artifactId. `:tools:posix:cat` -> `tools.posix.cat`,
// `:api` -> `api`. Mirrors what the source layout already implies.
val artifactName = project.path.removePrefix(":").replace(":", ".")

extensions.configure<MavenPublishBaseExtension> {
    coordinates(
        groupId = kashGroupId,
        artifactId = artifactName,
        version = project.version.toString(),
    )

    pom {
        url.set("https://github.com/AccuCodeAi-Inc/Kash")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("accucodeai")
                name.set("AccuCode AI")
                email.set("dev@accucodeai.com")
            }
        }
        scm {
            url.set("https://github.com/AccuCodeAi-Inc/Kash")
            connection.set("scm:git:git://github.com/AccuCodeAi-Inc/Kash.git")
            developerConnection.set("scm:git:ssh://github.com/AccuCodeAi-Inc/Kash.git")
        }
    }
}

// Optional Nexus repo. Skipped when NEXUS_URL is unset, so local publishing
// (publishToMavenLocal) works with zero configuration. Routes to
// maven-releases or maven-snapshots based on the project version suffix.
afterEvaluate {
    val nexusUrl = System.getenv("NEXUS_URL") ?: return@afterEvaluate
    if (nexusUrl.isBlank()) return@afterEvaluate
    val base =
        nexusUrl
            .trimEnd('/')
            .removeSuffix("/maven-releases")
            .removeSuffix("/maven-snapshots")
    val isSnapshot = version.toString().endsWith("-SNAPSHOT")
    val repoSegment = if (isSnapshot) "maven-snapshots" else "maven-releases"
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "Nexus"
                url = uri("$base/$repoSegment/")
                credentials {
                    username = System.getenv("MAINFRAME_NEXUS_USER")
                    password = System.getenv("MAINFRAME_NEXUS_PASSWORD")
                }
            }
        }
    }
}
