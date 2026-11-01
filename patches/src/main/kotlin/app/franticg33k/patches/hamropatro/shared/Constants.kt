package app.franticg33k.patches.hamropatro.shared

import app.morphe.patcher.patch.Compatibility

internal object Constants {
    // Package-name-only (AGENTS.md rule 14). The fingerprint is the ten verdict strings of the
    // app's ad policy gate, not obfuscated names, so it survives rebuilds; the patch itself
    // asserts the instruction shape it edits and fails loudly if the gate is reshaped.
    val COMPATIBILITY_HAMROPATRO = Compatibility(
        name = "Hamropatro",
        packageName = "com.hamropatro",
        appIconColor = 0xE2231A,
    )
}
