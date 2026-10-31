package app.franticg33k.patches.creatorsapp.zve10

import app.franticg33k.patches.creatorsapp.shared.Constants.APP
import app.franticg33k.patches.creatorsapp.shared.Constants.COMPATIBILITY_CREATORSAPP
import app.franticg33k.patches.creatorsapp.shared.Constants.EXTENSION
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.util.ReferenceUtil

private const val MODE = "$APP/ptpip/base/transaction/EnumFunctionMode;"
private const val FUNCTION = "$APP/camera/CameraConnector\$EnumFunction;"

/** Indices of every instruction whose reference string contains [needle]. */
private fun MutableMethod.indicesOfRef(needle: String): List<Int> =
    implementation!!.instructions.withIndex().filter { (_, ins) ->
        (ins as? ReferenceInstruction)?.let { ReferenceUtil.getReferenceString(it.reference) }
            ?.contains(needle) == true
    }.map { it.index }

/** The single such index; anything else means the app changed -- fail with a recovery step. */
internal fun MutableMethod.indexOfRef(needle: String, what: String): Int {
    val hits = indicesOfRef(needle)
    check(hits.size == 1) {
        "Creators' App: $what: expected exactly one '$needle' in $definingClass->$name, found ${hits.size}. " +
            "The app changed; run tools/fingerprints.py --app creatorsapp and re-anchor."
    }
    return hits.single()
}

context(_: BytecodePatchContext)
private fun Fingerprint.stubReturnVoid(what: String) = method.apply {
    check(returnType == "V") { "Creators' App: $what: expected a void method, got $returnType" }
    addInstructions(0, "return-void")
}

val zvE10CorePatch = bytecodePatch(
    name = "ZV-E10 core",
    description = "Shared fixes for using the original ZV-E10 with Creators' App: skips the " +
        "SDIO_GetDeviceLog request the camera rejects (which poisons the session), keeps a " +
        "remote-control session out of contents-transfer mode, and starts live view when it is " +
        "already running. Enabled automatically by the Wi-Fi and USB patches; does nothing on " +
        "its own.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)
    extendWith("extensions/extension.mpe")

    execute {
        // P4: the ZV-E10 does not implement SDIO_GetDeviceLog; the rejection kills the session.
        PtpUsbClientGetDeviceLogFingerprint.stubReturnVoid("P4 usb")
        PtpIpClientGetDeviceLogFingerprint.stubReturnVoid("P4 ip")

        // P3: the Cameras tab re-opens the session in contents-transfer mode on every resume.
        FunctionModeControllerOnResumeFingerprint.method.apply {
            val i = indexOfRef("CameraConnector;->switchFunctionMode(", "P3")
            check(implementation!!.instructions.elementAt(i).opcode.name == "invoke-virtual") { "P3: unexpected opcode" }
            // v0 = connector, v2 = function (result discarded) -> same registers + this controller.
            replaceInstruction(
                i,
                "invoke-static {v0, v2, p0}, $EXTENSION->switchFunctionMode(" +
                    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }

        // P9: if live view is already on when the session opens, no status change ever starts the stream.
        LiveviewScreenControllerInitFingerprint.method.apply {
            val i = indexOfRef("->addLiveViewStreamCallback(", "P9")
            addInstruction(i + 1, "invoke-static {p1}, $EXTENSION->startLiveView(Ljava/lang/Object;)V")
        }
    }
}

val zvE10WifiPatch = bytecodePatch(
    name = "ZV-E10 Wi-Fi support",
    description = "EXPERIMENTAL. Only one setup has been tested: a ZV-E10 on app 3.6.0 over Wi-Fi Direct " +
        "(Smartphone Connect). PC Remote over Wi-Fi, Import, other firmware and other app versions are " +
        "untested, and it may stop working when Sony updates the app.\n\n" +
        "Lets Creators' App list, connect to and use the original ZV-E10 over Wi-Fi " +
        "(PC Remote and Smartphone Connect): accepts its protocol version, keeps a PC Remote " +
        "session in remote-control mode, and enables Import / camera-initiated transfer.\n\n" +
        "WORKS over Wi-Fi: connection, live view and exposure controls, photo capture, automatic " +
        "copy of photos to the phone, movie recording. With Smartphone Connect (PC Remote OFF on " +
        "the camera) also Send to Smartphone from the camera and Import (browse the card from the " +
        "phone). Upstream saw all of this on 3.5.0; this patch's own device test on 3.6.0 covered " +
        "connection and Remote Shooting.\n\n" +
        "CAMERA LIMITS (the ZV-E10 itself; no patch can change these):\n" +
        "- It offers two Wi-Fi services with different abilities: PC Remote (remote control) and " +
        "Smartphone Connect / Send to Smartphone (file transfer).\n" +
        "- While PC Remote is on, the camera exposes no storage and refuses to send images, so " +
        "Import and Send to Smartphone cannot work then. Turn PC Remote off to transfer.\n" +
        "- Smartphone Connect works over Wi-Fi Direct only (the camera's own hotspot), not through " +
        "a router. PC Remote also works through a router (access point).\n" +
        "- A movie is never copied automatically after recording: the camera's save-destination " +
        "setting covers still images only. Use Import or send it from the camera.\n" +
        "- Bluetooth pairing does not work: the ZV-E10's Bluetooth service lacks what the app's " +
        "pairing flow needs (it stops right after bonding). Use the Wi-Fi search list, or the QR " +
        "patch, instead.\n\n" +
        "NOT COVERED BY THIS PATCH: other firmware versions, other app versions, other cameras.\n\n" +
        "SETUP (from upstream's testing on 3.5.0): for remote control set the camera's Network > " +
        "PC Remote Function to PC Remote: On, with Cnct Method Wi-Fi Access Point or Wi-Fi " +
        "Direct; in the app use Cameras > gear icon > Connect only via Wi-Fi. For transfer / " +
        "Import turn PC Remote OFF, start Send to Smartphone or Smartphone Connect on the " +
        "camera, join its DIRECT-... hotspot, then Connect only via Wi-Fi. Set Still Img. Save " +
        "Dest. to PC+Camera (or Phone+Camera) so photos also stay on the card. Uninstall the " +
        "original app first (different signing key).\n" +
        "Support list is from the upstream project (ZV-E10 firmware 2.03, app 3.5.0); it has " +
        "not been re-tested on 3.6.0.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)
    dependsOn(zvE10CorePatch)

    execute {
        // P7a: the ZV-E10 reports X_ServerVersion 3.00 and is classified Unknown (never listed).
        DeviceDescriptionControlModelFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0}, $EXTENSION->isZvE10Dd(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :zv_stock
                sget-object v0, $APP/camera/EnumControlModel;->SelectFunction:$APP/camera/EnumControlModel;
                return-object v0
                :zv_stock
                nop
            """,
        )

        // P7b: selecting the camera re-checks contents transfer; PC Remote reports no media server.
        SelectedConnectDeviceFingerprint.method.apply {
            val i = indexOfRef("DigitalImagingDescription;->isContentsTransferAvailable()Z", "P7b")
            val ins = implementation!!.instructions.elementAt(i) as FiveRegisterInstruction
            check(ins.registerCount == 1) { "Creators' App: P7b: unexpected invoke shape" }
            replaceInstruction(
                i,
                "invoke-static {${if (ins.registerC < 16) "v" else "p"}${ins.registerC}}, " +
                    "$EXTENSION->isContentsTransferAvailable(Ljava/lang/Object;)Z",
            )
        }

        // P7c: a PC Remote session always opens in, and never leaves, remote-control mode.
        PtpIpCameraInitializeFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1}, $EXTENSION->functionMode(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $MODE
            """,
        )
        PtpIpCameraSwitchFunctionModeFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0, p1}, $EXTENSION->refuseSwitch(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :zv_allow
                const/4 v0, 0x0
                return v0
                :zv_allow
                nop
            """,
        )

        // P8: transfer paths are gated on DID version >= 3.01; the ZV-E10 reports 3.00. Only this model.
        DeviceInfoIsUxpSupportedFingerprint.method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $APP/common/device/did/DeviceInfo;->mModelName:Ljava/lang/String;
                const-string v1, "ZV-E10"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :zv_stock
                return v0
                :zv_stock
                nop
            """,
        )
    }
}

val zvE10UsbPatch = bytecodePatch(
    name = "ZV-E10 USB support",
    description = "EXPERIMENTAL. Not yet tested on a device with app 3.6.0; the behaviour below is what " +
        "the upstream project saw on 3.5.0 with the same changes.\n\n" +
        "Lets Creators' App use the original ZV-E10 over USB (PC Remote): adds it to the " +
        "supported-camera list and forces USB sessions into remote-control mode. NOTE: this " +
        "applies to every camera on USB, so other cameras lose USB contents transfer with it " +
        "(the app cannot know the model before it connects).\n\n" +
        "WORKS over USB (camera set to PC Remote: On, Cnct Method: USB): connection, live view and " +
        "exposure controls, photo capture, automatic copy of photos to the phone, movie recording.\n\n" +
        "CAMERA LIMITS (the ZV-E10 itself; no patch can change these):\n" +
        "- Over USB the camera only offers PC Remote, and in PC Remote it exposes no storage and " +
        "refuses to send images. So Import and Send to Smartphone cannot work over USB. Use Wi-Fi " +
        "with PC Remote off to transfer.\n" +
        "- A movie is never copied automatically after recording: the camera's save-destination " +
        "setting covers still images only.\n" +
        "- Bluetooth pairing does not work on the ZV-E10, but over USB it is not needed: the camera " +
        "appears by itself, there is no Add camera step.\n\n" +
        "NOT COVERED BY THIS PATCH:\n" +
        "- The Import button is not hidden on the USB camera card (upstream hides it; that part is " +
        "not ported). Tapping it shows a storage error, which is expected.\n" +
        "- Other firmware versions, other app versions, other cameras.\n\n" +
        "HOW TO USE: on the camera set Network > PC Remote Function to PC Remote: On and PC Remote " +
        "Cnct Method: USB. Plug the camera into the phone with a data-capable USB-C cable and allow " +
        "USB access for Creators' App when Android asks. The ZV-E10 then appears by itself on the " +
        "Cameras tab (no scan, no picker) with a Remote Shooting button. If another app (e.g. " +
        "Gallery) asks for USB access, tap Cancel, or live view stops until you reconnect. Set Still " +
        "Img. Save Dest. to PC+Camera so photos also stay on the card. Uninstall the original app " +
        "first (different signing key).\n" +
        "Support list is from the upstream project (ZV-E10 firmware 2.03, app 3.5.0); it has " +
        "not been re-tested on 3.6.0.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)
    dependsOn(zvE10CorePatch)

    execute {
        // P1: append ZV-E10 to the app's local copy of the supported-camera list.
        UsbModelCheckFingerprint.method.apply {
            val i = indexOfRef("ArrayList;-><init>(Ljava/util/Collection;)V", "P1")
            val ins = implementation!!.instructions.elementAt(i) as FiveRegisterInstruction
            addInstruction(i + 1, "invoke-static {v${ins.registerC}}, $EXTENSION->addModel(Ljava/util/ArrayList;)V")
        }

        // P2: plug-in auto-connect opens contents transfer, which PC Remote refuses (endless spinner).
        for (fp in listOf(
            UsbInsertedStateFingerprint,
            UsbInsertedStateLambdaExecutedFingerprint,
            UsbInsertedStateLambdaFailedFingerprint,
        )) {
            fp.method.apply {
                val hits = indicesOfRef("EnumFunction;->CONTENTS_TRANSFER_PULL:")
                check(hits.isNotEmpty()) { "Creators' App: P2: no CONTENTS_TRANSFER_PULL in $definingClass->$name" }
                for (i in hits) {
                    val reg = (implementation!!.instructions.elementAt(i) as OneRegisterInstruction).registerA
                    replaceInstruction(i, "sget-object v$reg, $FUNCTION->REMOTE_CONTROL:$FUNCTION")
                }
            }
        }

        // P5: every USB PTP session stays in remote-control mode.
        PtpUsbCameraInitializeFingerprint.method.addInstruction(
            0, "sget-object p1, $MODE->REMOTE_CONTROL_MODE:$MODE",
        )
        PtpUsbCameraSwitchFunctionModeFingerprint.method.addInstructionsWithLabels(
            0,
            """
                sget-object v0, $MODE->REMOTE_CONTROL_MODE:$MODE
                if-eq p1, v0, :zv_remote
                const/4 v0, 0x0
                return v0
                :zv_remote
                nop
            """,
        )
    }
}

val skipMandatorySignInPatch = bytecodePatch(
    name = "Skip mandatory sign-in",
    description = "Creators' App forces a Creators' Cloud sign-in before connecting a first camera " +
        "when the region is mainland China, or when a Sony server flag turns it on for other " +
        "regions. This makes that check always answer \"not required\", so no region workaround " +
        "is needed. Sign-in stays available and optional.\n\n" +
        "SUPPORTED: connecting a first camera without signing in, on any region. First-launch " +
        "sign-in was already skippable. With this on, the upstream tip of choosing Hong Kong as " +
        "the region is not needed.\n" +
        "NOT SUPPORTED: Creators' Cloud features (cloud upload, cloud registration) still need " +
        "an account. Not tested on a device.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)

    execute {
        // Patch the callee: one stub covers all three callers (device tab, device menu, USB receiver).
        IsNeedSignInForMandatoryFingerprint.method.apply {
            check(returnType == "Z") { "Creators' App: isNeedSignInForMandatory no longer returns boolean ($returnType)" }
            addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }
    }
}
