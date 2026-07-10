package app.franticg33k.patches.providelite.manifest

import app.morphe.patcher.patch.resourcePatch
import app.franticg33k.patches.providelite.shared.Constants.COMPATIBILITY_PROVIDELITE
import org.w3c.dom.Element

@Suppress("unused")
val removeProvideliteLicenseActivityPatch = resourcePatch(
    name = "Remove License Activity",
    description = "Removes the PairIP LicenseActivity and LicenseContentProvider from AndroidManifest.xml " +
        "and the com.android.vending.CHECK_LICENSE permission, so the app runs without a valid " +
        "Play Store license (required because the APK is re-signed during patching).",
    default = true
) {
    compatibleWith(COMPATIBILITY_PROVIDELITE)

    execute {
        document("AndroidManifest.xml").use { document ->
            val app = document.getElementsByTagName("application").item(0) as Element
            val manifest = document.documentElement

            // PairIP LicenseActivity
            val activities = app.getElementsByTagName("activity")
            for (i in activities.length - 1 downTo 0) {
                val activity = activities.item(i) as Element
                if (activity.getAttribute("android:name") == "com.pairip.licensecheck.LicenseActivity") {
                    app.removeChild(activity)
                }
            }

            // PairIP LicenseContentProvider
            val providers = app.getElementsByTagName("provider")
            for (i in providers.length - 1 downTo 0) {
                val provider = providers.item(i) as Element
                val name = provider.getAttribute("android:name")
                if (name == "com.pairip.licensecheck.LicenseContentProvider" ||
                    name.endsWith(".com.pairip.licensecheck.LicenseContentProvider")) {
                    app.removeChild(provider)
                }
            }

            // com.android.vending.CHECK_LICENSE permission
            val permissions = manifest.getElementsByTagName("uses-permission")
            for (i in permissions.length - 1 downTo 0) {
                val perm = permissions.item(i) as Element
                if (perm.getAttribute("android:name") == "com.android.vending.CHECK_LICENSE") {
                    manifest.removeChild(perm)
                }
            }
        }
    }
}
