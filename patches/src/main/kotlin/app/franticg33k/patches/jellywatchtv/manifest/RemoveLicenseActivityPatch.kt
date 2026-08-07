package app.franticg33k.patches.jellywatchtv.manifest

import app.morphe.patcher.patch.resourcePatch
import app.franticg33k.patches.jellywatchtv.shared.Constants.COMPATIBILITY_JELLYWATCH_TV
import org.w3c.dom.Element

@Suppress("unused")
val removeJellyWatchTvLicenseActivityPatch = resourcePatch(
    name = "Remove License Activity",
    description = "Removes the PairIP LicenseActivity from AndroidManifest.xml.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JELLYWATCH_TV)

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
