extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.franticg33k.extension"
    // ponytail: default build-tools (36.0.0) is corrupted locally (missing aapt);
    // pin to an installed healthy version. Drop once the SDK is repaired.
    buildToolsVersion = "36.1.0"
}
