import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("kash.kmp")
}

description =
    "kash `python3` Pyodide engine — Kotlin/Wasm backend for kash python3 that drives Pyodide (CPython compiled to WebAssembly) via a dedicated Web Worker."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:kash:python3"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
        // wasmJsTest exercises the SabFsServer + FS-bridge plumbing
        // end-to-end in headless Chrome — the bridge uses SharedArrayBuffer
        // + Atomics, which only exist in the browser, so JVM tests can't
        // reach this layer. :corevm carries InMemoryFs.
        val wasmJsTest by sourceSets.getting {
            dependencies {
                implementation(project(":corevm"))
                implementation(project(":coretest"))
            }
        }
        // Pyodide is intentionally NOT declared as an npm dependency. It
        // dynamically imports Node-only modules (`node:vm`, `node:fs`, ...)
        // that webpack can't resolve when targeting the browser. The
        // hosting page is expected to pull Pyodide from the CDN via a
        // `<script src="https://cdn.jsdelivr.net/pyodide/.../pyodide.js">`
        // tag, which exposes `globalThis.loadPyodide`. The externals in
        // `PyodideExternals.kt` access it through that global.
    }
}

// Opt this module into wasmJs browser test execution. The convention
// disables `wasmJsBrowserTest` by default (Karma + headless Chrome are
// heavyweight for `:check`); the FS bridge can't be exercised any other
// way — Atomics/SAB don't exist on JVM.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        browser {
            testTask { enabled = true }
        }
    }
}

// -----------------------------------------------------------------------------
// Pyodide bundle for wasmJsTest.
//
// The integration suite (`PyodideEngineEndToEndTest`) drives a real Python
// interpreter through the FS bridge — same code path the app runs. Karma's
// test server needs to serve `./pyodide/pyodide.js` + its companion files
// AND `./pyodide-worker.js` at predictable URLs. We download the same
// Pyodide tarball :kash-app-web uses, unpack just the runtime, and copy
// it into the test resources tree. `pyodide-worker.js` is copied across
// from main resources (Kotlin/Wasm webpack doesn't auto-merge the two).
//
// Cost: download is ~12 MB (cached after first build); each test invokes
// Pyodide boot which takes ~3s. Suite size deliberately small — mirror
// of GraalPyEngineTest's coverage, no more.
// -----------------------------------------------------------------------------
val pyodideVersionForTest = libs.versions.pyodide.get()
val pyodideTestArchive = layout.buildDirectory.file("pyodide-test/pyodide-$pyodideVersionForTest.tar.bz2")
val pyodideTestUnpackDir = layout.buildDirectory.dir("pyodide-test/unpacked-$pyodideVersionForTest")
val pyodideTestResourcesDir = layout.projectDirectory.dir("src/wasmJsTest/resources/pyodide")
val pyodideTestUrl =
    "https://github.com/pyodide/pyodide/releases/download/$pyodideVersionForTest/pyodide-$pyodideVersionForTest.tar.bz2"

// SHA-256 of the release tarball. Pin so a corrupt/truncated download or a
// tampered upstream artifact fails the build loudly instead of feeding a
// broken bundle into the test suite. Lives in the root `gradle.properties`
// (`pyodide.sha256`); bump it there when bumping `pyodide` in the version
// catalog:
//   shasum -a 256 <downloaded>/pyodide-<version>.tar.bz2
val pyodideSha256ForTest =
    providers
        .gradleProperty("pyodide.sha256")
        .get()

val downloadPyodideForTest by tasks.registering {
    val outFile = pyodideTestArchive
    val url = pyodideTestUrl
    val version = pyodideVersionForTest
    val expectedSha = pyodideSha256ForTest
    outputs.file(outFile)
    doLast {
        val out = outFile.get().asFile
        // Local (not script-level) so the task action stays serializable for
        // the configuration cache — a top-level `fun` reference can't be.
        val sha256Hex: (File) -> String = { f ->
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
        // Self-healing cache: trust an existing file only if its digest
        // matches. A build killed mid-download leaves a partial archive that
        // would otherwise be cached as "complete" forever and break every
        // later unpack; a digest mismatch forces a re-download.
        if (out.exists() && sha256Hex(out).equals(expectedSha, ignoreCase = true)) return@doLast
        out.parentFile.mkdirs()
        println("Downloading Pyodide $version for tests…")
        // Download to a sibling .part file, verify, then atomically move into
        // place — so the final path only ever holds a complete, verified file.
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        val conn = URI(url).toURL().openConnection()
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        conn.getInputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSha = sha256Hex(tmp)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            tmp.delete()
            throw GradleException(
                "Pyodide $version checksum mismatch downloading $url\n" +
                    "  expected: $expectedSha\n" +
                    "  actual:   $actualSha\n" +
                    "If you intentionally bumped the `pyodide` version, update " +
                    "`pyodide.sha256` in the root gradle.properties.",
            )
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val unpackPyodideForTest by tasks.registering(Copy::class) {
    dependsOn(downloadPyodideForTest)
    from(tarTree(resources.bzip2(pyodideTestArchive.map { it.asFile })))
    into(pyodideTestUnpackDir)
}

val bundlePyodideForTest by tasks.registering(Copy::class) {
    dependsOn(unpackPyodideForTest)
    from(pyodideTestUnpackDir.map { it.dir("pyodide") }) {
        // Same minimal runtime kash-app-web ships.
        include(
            "pyodide.js",
            "pyodide.mjs",
            "pyodide.asm.js",
            "pyodide.asm.wasm",
            "python_stdlib.zip",
            "pyodide-lock.json",
        )
    }
    into(pyodideTestResourcesDir)
}

// Make pyodide-worker.js available to the test bundle by copying from main
// resources. Kotlin/Wasm doesn't auto-include main resources in the test
// dist, and the worker is what loads Pyodide so it has to be at the same
// URL root as the bundle.
val copyPyodideWorkerForTest by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("src/wasmJsMain/resources").file("pyodide-worker.js"))
    into(layout.projectDirectory.dir("src/wasmJsTest/resources"))
}

tasks
    .matching {
        it.name == "wasmJsTestProcessResources" ||
            it.name == "compileTestKotlinWasmJs"
    }.configureEach {
        dependsOn(bundlePyodideForTest, copyPyodideWorkerForTest)
    }

// `PyodideEngine` is the wasmJs `PythonEngine` implementation; the
// :kash-app-web entry point constructs one and threads it into
// `python3Commands(...)` alongside the rest of the standard catalog.
