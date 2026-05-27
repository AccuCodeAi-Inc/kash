import com.vanniktech.maven.publish.MavenPublishBaseExtension

/*
 * Convention plugin for Maven publishing.
 *
 * Wraps Vanniktech Maven Publish so every kash module ends up with the same
 * coordinates, POM metadata, and Maven Central (Sonatype Central Portal)
 * routing. KMP modules publish the root multiplatform module + per-target
 * artifacts automatically; non-KMP modules publish a single jar.
 *
 *   ./gradlew publishToMavenLocal     # everything to ~/.m2 (snapshots unsigned)
 *   ./gradlew publishToMavenCentral   # -SNAPSHOT -> Central Portal snapshot
 *                                     # repo; release versions auto-released.
 *                                     # Needs ORG_GRADLE_PROJECT_mavenCentral*
 *                                     # creds (+ signing key for releases) —
 *                                     # see .github/workflows/
 *                                     # publish-maven-central.yml.
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
    // Publish to the Sonatype Central Portal (https://central.sonatype.com).
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

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
