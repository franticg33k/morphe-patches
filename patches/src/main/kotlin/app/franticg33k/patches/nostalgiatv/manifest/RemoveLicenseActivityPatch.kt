package app.franticg33k.patches.nostalgiatv.manifest

import app.franticg33k.patches.nostalgiatv.shared.Constants.COMPATIBILITY_NOSTALGIA_TV
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

@Suppress("unused")
val removeNostalgiaTvLicenseActivityPatch = resourcePatch(
    name = "Remove License Activity",
    description = "Removes the PairIP LicenseActivity from AndroidManifest.xml so the licensing " +
        "layer can never launch the Play Store paywall, even if a license code path is reached.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NOSTALGIA_TV)

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
