import java.io.OutputStream
import java.net.URI

plugins {
    id("kash.kmp-wasm-app")
}

// -----------------------------------------------------------------------------
// Pyodide self-host bundle.
//
// Downloads the Pyodide release tarball (~12 MB compressed, ~30 MB extracted)
// and copies the core runtime files into the wasmJs resources tree. Webpack
// then ships them under `dist/pyodide/` so the app loads Pyodide locally —
// no CDN, works offline.
//
// Files kept (per https://pyodide.org/en/stable/usage/downloading-and-deploying.html):
//   pyodide.js              top-level loader (referenced from index.html)
//   pyodide.asm.js          Emscripten glue
//   pyodide.asm.wasm        compiled CPython
//   python_stdlib.zip       Python standard library
//   pyodide-lock.json       package metadata
//   pyodide.mjs             ES-module variant
//
// Optional packages (.whl files) are NOT bundled by default — they push the
// dist size to ~200 MB. Anyone who needs numpy/pandas/etc. can run
// `./gradlew :kash-app-web:fetchPyodide -Ppyodide.full=true`.
// -----------------------------------------------------------------------------
val pyodideVersion = libs.versions.pyodide.get()
val pyodideArchiveFile = file("build/pyodide/pyodide-$pyodideVersion.tar.bz2")
val pyodideUnpackDirFile = file("build/pyodide/unpacked-$pyodideVersion")
val pyodideBundleDirFile = file("src/wasmJsMain/resources/pyodide")
val pyodideUrl = "https://github.com/pyodide/pyodide/releases/download/$pyodideVersion/pyodide-$pyodideVersion.tar.bz2"
val pyodideFull =
    providers
        .gradleProperty("pyodide.full")
        .orElse("false")
        .get()
        .toBoolean()

val downloadPyodide by tasks.registering {
    val url = pyodideUrl
    val out = pyodideArchiveFile
    val version = pyodideVersion
    inputs.property("url", url)
    outputs.file(out)
    doLast {
        if (out.exists()) return@doLast
        out.parentFile.mkdirs()
        println("Downloading Pyodide $version …")
        val conn = URI(url).toURL().openConnection()
        conn.connect()
        val input = conn.getInputStream()
        try {
            val output: OutputStream = out.outputStream()
            try {
                input.copyTo(output)
            } finally {
                output.close()
            }
        } finally {
            input.close()
        }
    }
}

val unpackPyodide by tasks.registering(Copy::class) {
    dependsOn(downloadPyodide)
    val archive = pyodideArchiveFile
    from(tarTree(resources.bzip2(archive)))
    into(pyodideUnpackDirFile)
}

val bundlePyodide by tasks.registering(Copy::class) {
    dependsOn(unpackPyodide)
    val srcDir = file("$pyodideUnpackDirFile/pyodide")
    val full = pyodideFull
    from(srcDir) {
        if (!full) {
            include(
                "pyodide.js",
                "pyodide.mjs",
                "pyodide.asm.js",
                "pyodide.asm.wasm",
                "python_stdlib.zip",
                "pyodide-lock.json",
            )
        }
    }
    into(pyodideBundleDirFile)
}

// Copy the Pyodide-in-Worker shim from the pyodide module into our dist/
// alongside the Pyodide bundle. The shim is `importScripts('./pyodide/pyodide.js')`
// and only resolves correctly when both files end up in the same dir.
//
// Cross-module wasmJs resources don't merge automatically with the
// hierarchical-resource pipeline, so we copy explicitly. Source-of-truth
// for the shim still lives in :tools:kash:python3-pyodide.
val pyodideWorkerSrcFile =
    file("../tools/kash/python3-pyodide/src/wasmJsMain/resources/pyodide-worker.js")
val pyodideWorkerDestDirFile = file("src/wasmJsMain/resources")
val copyPyodideWorker by tasks.registering(Copy::class) {
    from(pyodideWorkerSrcFile)
    into(pyodideWorkerDestDirFile)
}

// Wire the bundle into Kotlin's resource pipeline. wasmJsProcessResources
// is the task that consumes src/wasmJsMain/resources — declaring the
// dependency here is what Gradle's task-validation requires (the bundle
// task writes into that directory).
tasks
    .matching {
        it.name == "wasmJsProcessResources" ||
            it.name.startsWith("wasmJsBrowser") ||
            it.name.startsWith("compileKotlinWasmJs")
    }.configureEach { dependsOn(bundlePyodide, copyPyodideWorker) }

// Generate a BuildConfig.kt carrying the Gradle `version` into wasmJsMain so
// the About dialog stays in sync with gradle.properties. Plain generated
// source (no extra plugin) keeps it KMP-friendly — it's just another srcDir.
val generateBuildConfig by tasks.registering {
    val pkg = "com.accucodeai.kash.ui"
    val version = project.version.toString()
    val outDir = layout.buildDirectory.dir("generated/buildconfig/wasmJsMain/kotlin")
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve(pkg.replace('.', '/'))
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            package $pkg

            internal object BuildConfig {
                const val VERSION: String = "$version"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Compose resources plugin emits Res / *ResourceCollectors generators
            // into commonMain + jvmMain regardless of which target consumes them,
            // so the runtime dep has to be visible to commonMain to keep the
            // convention plugin's auto-added jvm target compiling.
            implementation(compose.runtime)
            implementation(compose.components.resources)
        }
        val wasmJsMain by getting {
            kotlin.srcDir(generateBuildConfig)
            dependencies {
                implementation(project(":kash"))
                implementation(project(":tools:kash:python3"))
                implementation(project(":tools:kash:python3-pyodide"))
                implementation(project(":tools:kash:git"))
                implementation(project(":tools:kash:git-http"))
                // AI tier — registers `agent`. Multiplatform via Koog.
                implementation(project(":tools:ai:ai-module"))
                implementation(project(":shared:net"))
                implementation(libs.kotlinxCoroutinesCore)

                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
            }
        }
    }
}

// Fix the package + class name for the generated Res object so the
// `compose_resources` import in TerminalCanvas resolves.
compose.resources {
    packageOfResClass = "com.accucodeai.kash.webres"
    generateResClass = org.jetbrains.compose.resources.ResourcesExtension.ResourceClassGeneration.Always
}
