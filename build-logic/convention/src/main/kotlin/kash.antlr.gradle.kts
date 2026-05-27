// Generates a Kotlin parser from ANTLR grammars in `src/commonMain/antlr/`
// and wires the output into the commonMain source set so the generated
// classes are visible to the rest of the module.
//
// The output package defaults to "com.accucodeai.kash.<projectName>.parser.antlr"
// but can be overridden per-module via the `kashAntlr` extension:
//
//     kashAntlr { packageName.set("com.accucodeai.kash.parser.antlr") }
//
// Applying this plugin transitively pulls in :shared:antlr-runtime, which
// re-exports the antlr-kotlin runtime and provides the small fail-fast +
// two-stage parsing helpers used by every consumer.
import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("kash.kmp")
    id("com.strumenta.antlr-kotlin")
}

interface KashAntlrExtension {
    val packageName: Property<String>
}

val kashAntlr = extensions.create<KashAntlrExtension>("kashAntlr")
kashAntlr.packageName.convention("com.accucodeai.kash.${project.name}.parser.antlr")

val antlrOutputDir = layout.buildDirectory.dir("generated-src/antlr/commonMain/kotlin")

val generateKotlinGrammarSource =
    tasks.register<AntlrKotlinTask>("generateKotlinGrammarSource") {
        source =
            fileTree(layout.projectDirectory.dir("src/commonMain/antlr")) {
                include("**/*.g4")
            }
        packageName = kashAntlr.packageName.get()
        arguments = listOf("-visitor")
        outputDirectory = antlrOutputDir.get().asFile

        // antlr-kotlin emits @Suppress("UNSAFE_CALL") on generated sempred
        // wrappers around already-safe-called expressions (`_input.LT(1)?.text`).
        // Kotlin 2.x warns on suppressing a non-error, so strip the redundant
        // annotation lines from the generated output.
        doLast {
            outputDirectory!!
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    val text = f.readText()
                    val cleaned =
                        text
                            .lineSequence()
                            .filterNot { it.trim() == "@Suppress(\"UNSAFE_CALL\")" }
                            .joinToString("\n")
                    if (cleaned != text) f.writeText(cleaned)
                }
        }
    }

extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    sourceSets.named("commonMain") {
        kotlin.srcDir(generateKotlinGrammarSource.map { it.outputDirectory!! })
    }
}

dependencies {
    // :shared:antlr-runtime re-exports antlrKotlinRuntime + ships the
    // ThrowingErrorListener / configureForFailFast / twoStageParse helpers
    // every consumer needs. Pulling it in here means modules that apply
    // kash.antlr don't have to depend on either explicitly.
    "commonMainApi"(project(":shared:antlr-runtime"))
}

// Generated Kotlin sources need to exist before the Kotlin compile tasks read
// the source set. The KMP compile graph is rooted at compileCommonMainKotlin
// (metadata) on Kotlin 2.x; making every Kotlin compile depend on the generator
// covers commonMain metadata, jvm main, and any future targets.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(generateKotlinGrammarSource)
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("KotlinMetadata") }.configureEach {
    dependsOn(generateKotlinGrammarSource)
}

// Generated grammar sources live under build/ which kash.kmp already excludes
// from ktlint, so no extra filter is needed here.
