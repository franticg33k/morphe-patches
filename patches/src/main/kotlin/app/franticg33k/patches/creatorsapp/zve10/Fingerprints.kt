package app.franticg33k.patches.creatorsapp.zve10

import app.franticg33k.patches.creatorsapp.shared.Constants.APP
import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// The app is not obfuscated, so class + method names are stable developer-intent anchors.
// Each is pinned to the minimum that is unique: no return types or parameters unless the name
// alone is overloaded. See tools/appdata/creatorsapp.yml.

object UsbModelCheckFingerprint : Fingerprint(
    definingClass = "$APP/usb/UsbSupportedCameraManager\$set\$deviceInfoUpdaterListener\$1;",
    name = "onDeviceInfoChanged",
)

/** Both UsbInsertedState and its onEnter lambda open the plug-in session in CONTENTS_TRANSFER_PULL. */
private fun fieldRefsPull(method: com.android.tools.smali.dexlib2.iface.Method) =
    method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "CONTENTS_TRANSFER_PULL"
    } == true

object UsbInsertedStateFingerprint : Fingerprint(
    definingClass = "$APP/usb/statemachine/UsbInsertedState;",
    name = "onEnter",
    custom = { method, _ -> fieldRefsPull(method) },
)

// The onEnter lambda reads PULL in two methods; a fingerprint resolves one method, so one each.
object UsbInsertedStateLambdaExecutedFingerprint : Fingerprint(
    name = "onExecuted",
    custom = { method, classDef ->
        classDef.type.startsWith("$APP/usb/statemachine/UsbInsertedState\$onEnter") && fieldRefsPull(method)
    },
)

object UsbInsertedStateLambdaFailedFingerprint : Fingerprint(
    name = "onExecutionFailed",
    custom = { method, classDef ->
        classDef.type.startsWith("$APP/usb/statemachine/UsbInsertedState\$onEnter") && fieldRefsPull(method)
    },
)

object FunctionModeControllerOnResumeFingerprint : Fingerprint(
    definingClass = "$APP/toppage/devicetab/controller/FunctionModeController;",
    name = "onResume",
)

object PtpUsbClientGetDeviceLogFingerprint : Fingerprint(
    definingClass = "$APP/ptpip/PtpUsbClient;",
    name = "getDeviceLog",
)

object PtpIpClientGetDeviceLogFingerprint : Fingerprint(
    definingClass = "$APP/ptpip/PtpIpClient;",
    name = "getDeviceLog",
)

object PtpUsbCameraInitializeFingerprint : Fingerprint(
    definingClass = "$APP/camera/PtpUsbCamera;",
    name = "initialize",
)

object PtpUsbCameraSwitchFunctionModeFingerprint : Fingerprint(
    definingClass = "$APP/camera/PtpUsbCamera;",
    name = "switchFunctionMode",
)

object DeviceDescriptionControlModelFingerprint : Fingerprint(
    definingClass = "$APP/common/device/DeviceDescription;",
    name = "getControlModel",
)

object SelectedConnectDeviceFingerprint : Fingerprint(
    custom = { method, classDef ->
        classDef.type.startsWith("$APP/toppage/devicetab/wificonnect/SearchedDeviceListController\$selectedConnectDevice") &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                    ?.name == "isContentsTransferAvailable"
            } == true
    },
)

object PtpIpCameraInitializeFingerprint : Fingerprint(
    definingClass = "$APP/camera/PtpIpCamera;",
    name = "initialize",
)

object PtpIpCameraSwitchFunctionModeFingerprint : Fingerprint(
    definingClass = "$APP/camera/PtpIpCamera;",
    name = "switchFunctionMode",
)

object DeviceInfoIsUxpSupportedFingerprint : Fingerprint(
    definingClass = "$APP/common/device/did/DeviceInfo;",
    name = "isUxpSupported",
)

object LiveviewScreenControllerInitFingerprint : Fingerprint(
    definingClass = "$APP/ptp/remotecontrol/controller/liveview/LiveviewScreenController;",
    name = "<init>",
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                ?.name == "addLiveViewStreamCallback"
        } == true
    },
)

// The single mandatory-sign-in decision (region CN, or a server flag, with no camera registered yet).
// Callers: DeviceTabController, DeviceMenuSettingsController, UsbPermissionReceiver.
object IsNeedSignInForMandatoryFingerprint : Fingerprint(
    definingClass = "$APP/auth/AuthUtil\$Companion;",
    name = "isNeedSignInForMandatory",
)
