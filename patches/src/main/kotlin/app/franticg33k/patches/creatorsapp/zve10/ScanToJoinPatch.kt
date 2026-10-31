package app.franticg33k.patches.creatorsapp.zve10

import app.franticg33k.patches.creatorsapp.shared.Constants.APP
import app.franticg33k.patches.creatorsapp.shared.Constants.COMPATIBILITY_CREATORSAPP
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.ReferenceUtil
import org.w3c.dom.Element

private const val JOIN_FLOW = "Lapp/franticg33k/extension/creatorsapp/JoinFlow;"

// "Connect only via Wi-Fi": searches whatever network the phone is already on. Private, so it is only
// reachable through its two in-class call sites; hooking the body covers both.
object OnetimeConnectionFingerprint : Fingerprint(
    definingClass = "$APP/toppage/devicetab/menu/DeviceMenuSettingsController;",
    name = "onetimeConnection",
)

// The first-time model picker builds its rows from getSupportedCameraObject() inside this adapter.
object SupportCameraListBuildFingerprint : Fingerprint(
    custom = { method, classDef ->
        classDef.type == "$APP/bluetooth/pairing/SupportCameraListAdapter;" &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getSupportedCameraObject"
            } == true
    },
)

// Tapping a model row. Category 0 = a camera model, 1 = "My camera is not listed above".
object SupportCameraItemClickFingerprint : Fingerprint(
    definingClass = "$APP/bluetooth/pairing/fragment/SupportCameraFragment\$itemClickListener\$1;",
    name = "onItemClicked",
)

// The app's own search screen opens: the moment to pick the ZV-E10 for the user.
object SearchedDeviceListStartFingerprint : Fingerprint(
    definingClass = "$APP/toppage/devicetab/wificonnect/SearchedDeviceListController;",
    name = "onStart",
)

// The Cameras tab's menu controller owns the OneTimeConnectionController that the stock "Connect only via Wi-Fi" uses.
object DeviceMenuControllerPrepareFingerprint : Fingerprint(
    definingClass = "$APP/toppage/devicetab/controller/DeviceMenuController;",
    name = "onPrepareOptionsMenu",
)

private val cameraPermissionPatch = resourcePatch(
    description = "Adds the CAMERA permission so the QR scanner can open the camera.",
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val perms = manifest.getElementsByTagName("uses-permission")
            val has = (0 until perms.length).any {
                (perms.item(it) as Element).getAttribute("android:name") == "android.permission.CAMERA"
            }
            if (!has) {
                manifest.appendChild(document.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.CAMERA")
                })
            }
            val feats = manifest.getElementsByTagName("uses-feature")
            val hasFeat = (0 until feats.length).any {
                (feats.item(it) as Element).getAttribute("android:name") == "android.hardware.camera"
            }
            if (!hasFeat) {
                manifest.appendChild(document.createElement("uses-feature").apply {
                    setAttribute("android:name", "android.hardware.camera")
                    setAttribute("android:required", "false")
                })
            }
        }
    }
}

val scanToJoinPatch = bytecodePatch(
    name = "Scan camera QR to join Wi-Fi",
    description = "EXPERIMENTAL. Only one setup has been tested: a ZV-E10 on app 3.6.0 over Wi-Fi Direct " +
        "(Smartphone Connect). It hooks several internal screens of Sony's app, so a Sony update can " +
        "break it, and some failures fall back to the stock behaviour without an error. Works together " +
        "with ZV-E10 Wi-Fi support, which you need to enable as well.\n\n" +
        "One-step Wi-Fi connection for the original ZV-E10. Scan the QR code the camera shows " +
        "(Network > Smartphone Connect / Ctrl w/ Smartphone) and the app joins the camera's hotspot, runs " +
        "its own search, and selects the ZV-E10 for you, so you land on the connected page with Remote " +
        "Shooting and Import. No typing the password into Android's Wi-Fi settings.\n\n" +
        "HOW: adds a \"ZV-E10\" row to the first-time model picker (the stock guide for the Mark II is a " +
        "Bluetooth pairing that does not work on the ZV-E10) and puts the same prompt in front of the gear " +
        "menu's \"Connect only via Wi-Fi\". The prompt offers: Reconnect to the last camera (saved after a " +
        "successful join, no scan needed), Scan the camera's QR code, or Already connected. Android's own " +
        "Wi-Fi approval prompt still appears each time.\n\n" +
        "WORKS: the Wi-Fi QR of Sony cameras that use the \"W01:S:…;P:…;C:…;M:…;\" format; Remote " +
        "Shooting after the connection (tested on a ZV-E10, app 3.6.0, Wi-Fi Direct / Smartphone Connect).\n\n" +
        "CAMERA LIMITS (the ZV-E10 itself; no patch can change these):\n" +
        "- The Wi-Fi QR only exists on the camera's Smartphone Connect screen, so the camera must be " +
        "showing it every time you connect.\n" +
        "- Bluetooth pairing does not work on the ZV-E10 (its Bluetooth service lacks what the app " +
        "needs). This patch replaces it with the QR.\n" +
        "- Movies are not copied automatically, and Import and Send to Smartphone only work with PC " +
        "Remote off (see ZV-E10 Wi-Fi support).\n\n" +
        "ANDROID LIMITS: Android's own Wi-Fi approval prompt appears every time, Location must be on, " +
        "and joining does not work while a VPN is on.\n\n" +
        "NOT COVERED BY THIS PATCH: Import, other cameras, and reconnecting after the camera was " +
        "restarted were not checked. It adds the CAMERA permission.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)
    dependsOn(cameraPermissionPatch)
    extendWith("extensions/extension.mpe")

    execute {
        OnetimeConnectionFingerprint.method.apply {
            check(returnType == "V") { "Creators' App: onetimeConnection no longer returns void ($returnType)" }
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0}, $JOIN_FLOW->intercept(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :zv_stock
                    return-void
                    :zv_stock
                    nop
                """,
            )
        }

        // The first-time model picker lists the ZV-E10 II but not the original ZV-E10: add a plain row...
        SupportCameraListBuildFingerprint.method.apply {
            val call = indexOfRef("CloudInfoDb;->getSupportedCameraObject()", "picker list")
            // call, move-result-object vX, invoke-direct {p2, vX}, ArrayList-><init>(Collection) -> the copy is in a register
            val init = (call + 1..call + 4).firstOrNull {
                (implementation!!.instructions.elementAt(it) as? ReferenceInstruction)
                    ?.let { i -> ReferenceUtil.getReferenceString(i.reference) }
                    ?.contains("ArrayList;-><init>(Ljava/util/Collection;)V") == true
            } ?: error("Creators' App: picker list: no ArrayList copy after getSupportedCameraObject()")
            val reg = (implementation!!.instructions.elementAt(init) as FiveRegisterInstruction).registerC
            addInstruction(init + 1, "invoke-static {${if (reg < 16) "v" else "p"}$reg}, $JOIN_FLOW->addPickerEntry(Ljava/util/ArrayList;)V")
        }

        // Close the picker and let the Cameras tab's own controller start the search (the stock clean-up needs it):
        // remember the tab's menu controller, and start the search when the tab comes back to the front.
        DeviceMenuControllerPrepareFingerprint.method.addInstruction(0, "invoke-static {p0}, $JOIN_FLOW->registerMenu(Ljava/lang/Object;)V")
        FunctionModeControllerOnResumeFingerprint.method.addInstruction(0, "invoke-static {}, $JOIN_FLOW->onTabResume()V")

        // When the app's search screen opens after our join, select the ZV-E10 as soon as it is listed.
        SearchedDeviceListStartFingerprint.method.addInstruction(0, "invoke-static {p0}, $JOIN_FLOW->onSearchStart(Ljava/lang/Object;)V")

        // ...and send a tap on it into the scan / join flow instead of the Bluetooth guide, which stops after bonding.
        SupportCameraItemClickFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0, p1}, $JOIN_FLOW->onPickerClick(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :zv_stock
                return-void
                :zv_stock
                nop
            """,
        )
    }
}
