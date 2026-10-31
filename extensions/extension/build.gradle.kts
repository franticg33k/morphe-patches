extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.franticg33k.extension"
    // ponytail: default build-tools (36.0.0) is corrupted locally (missing aapt);
    // pin to an installed healthy version. Drop once the SDK is repaired.
    buildToolsVersion = "36.1.0"

    sourceSets["main"].java.srcDir(layout.buildDirectory.get().dir("generated/zxing").asFile)
}

// Creators' App ships its own, trimmed copy of com.google.zxing (the writer side). If the extension also
// carried com.google.zxing, the patcher keeps ONE copy per class name and the two versions get mixed at
// runtime (NoSuchMethodError: BitMatrix.set(II)V on the first camera frame). So the decoder's sources are
// pulled from the library's sources jar and renamed into a private package at build time.
val zxingSources: Configuration by configurations.creating

dependencies {
    zxingSources("com.google.zxing:core:3.5.3:sources")
}

val relocateZxing = tasks.register<Sync>("relocateZxing") {
    from(zipTree(zxingSources.elements.map { it.single().asFile })) {
        include("com/google/zxing/**/*.java")
        eachFile { path = path.replace("com/google/zxing", "app/franticg33k/extension/zxing") }
        filter { it.replace("com.google.zxing", "app.franticg33k.extension.zxing") }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/zxing"))
}

tasks.matching { it.name.startsWith("compile") && (it.name.endsWith("JavaWithJavac") || it.name.endsWith("Kotlin")) }.configureEach {
    dependsOn(relocateZxing)
}
