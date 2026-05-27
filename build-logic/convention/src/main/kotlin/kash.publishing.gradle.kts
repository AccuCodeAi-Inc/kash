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

// Fallback POM <description>, used only when a module doesn't set its own
// `description` in its build script. Derived from the module path so even
// un-annotated modules get a readable, per-artifact blurb on Maven Central.
// `:tools:posix:cat`  -> "kash `cat` (tools.posix) — part of kash, …"
// `:api`              -> "kash `api` — part of kash, …"
val derivedDescription =
    buildString {
        append("kash `").append(project.name).append("`")
        val moduleGroup = artifactName.substringBeforeLast('.', "")
        if (moduleGroup.isNotEmpty()) append(" (").append(moduleGroup).append(")")
        append(
            " — part of kash, a Kotlin Multiplatform, UTF-8, " +
                "bash-compatible shell and tool suite.",
        )
    }

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
        // Maven Central rejects releases whose POM lacks <name> or
        // <description>. Each of the ~138 modules publishes its own POM, so
        // both are derived from the module path; a module may override the
        // blurb by setting its own `description` in its build script (read
        // lazily so a later assignment still wins).
        name.set("kash $artifactName")
        description.set(provider { project.description ?: derivedDescription })
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
