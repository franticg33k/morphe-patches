package app.franticg33k.patches.fricam.manifest

import app.morphe.patcher.patch.resourcePatch
import app.franticg33k.patches.fricam.shared.Constants.COMPATIBILITY_FRICAM
import org.w3c.dom.Element

@Suppress("unused")
val removeFricamLicenseActivityPatch = resourcePatch(
    name = "Remove License Activity",
    description = "Removes the PairIP LicenseActivity from AndroidManifest.xml so the app runs " +
        "without a valid Play Store license (required because the APK is re-signed during patching).",
    default = true
) {
    compatibleWith(COMPATIBILITY_FRICAM)

    execute {
        document("AndroidManifest.xml").use { document ->
            val app = document.getElementsByTagName("application").item(0) as Element
            val activities = app.getElementsByTagName("activity")
            for (i in activities.length - 1 downTo 0) {
                val activity = activities.item(i) as Element
                if (activity.getAttribute("android:name") == "com.pairip.licensecheck.LicenseActivity") {
                    app.removeChild(activity)
                }
            }
        }
    }
}
