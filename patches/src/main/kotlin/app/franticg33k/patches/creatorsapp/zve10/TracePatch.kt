package app.franticg33k.patches.creatorsapp.zve10

import app.franticg33k.patches.creatorsapp.shared.Constants.APP
import app.franticg33k.patches.creatorsapp.shared.Constants.COMPATIBILITY_CREATORSAPP
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val TRACE = "Lapp/franticg33k/extension/creatorsapp/ZvTrace;"
private const val EXECUTOR = "$APP/ptpip/ptpip/base/transaction/TransactionExecutor;"
private const val OP = "$APP/ptpip/base/packet/EnumOperationCode;"
private const val RESP = "$APP/ptpip/base/packet/EnumResponseCode;"
private const val MODE = "$APP/ptpip/base/transaction/EnumFunctionMode;"

private object TraceOkFingerprint : Fingerprint(
    definingClass = EXECUTOR, name = "onOperationRequested", parameters = listOf(OP, "Ljava/util/List;"),
)
private object TraceFailFingerprint : Fingerprint(
    definingClass = EXECUTOR, name = "onOperationRequestFailed", parameters = listOf(OP, RESP, "Ljava/util/List;"),
)
private object TraceTimeoutFingerprint : Fingerprint(
    definingClass = EXECUTOR, name = "onOperationTimeOut", parameters = listOf(OP),
)
private object TraceProbeFingerprint : Fingerprint(
    definingClass = EXECUTOR, name = "onProbeReceiveFailed", parameters = listOf(OP),
)
private object TraceTearDownFingerprint : Fingerprint(
    definingClass = EXECUTOR, name = "tearDown",
)
private object TraceSendFingerprint : Fingerprint(
    definingClass = EXECUTOR, name = "executeTransaction",
)
private object TraceConnectorFingerprint : Fingerprint(
    definingClass = "$APP/camera/CameraConnector;", name = "switchFunctionMode",
)
private object TraceInitModeFingerprint : Fingerprint(
    definingClass = "$APP/camera/PtpIpCamera;", name = "initialize",
)
private object TraceSwitchModeFingerprint : Fingerprint(
    definingClass = "$APP/camera/PtpIpCamera;", name = "switchFunctionMode",
)

val zvE10TracePatch = bytecodePatch(
    name = "ZV-E10 debug trace",
    description = "Debug only. Logs the app's PTP/IP session with the camera (every operation it sends, " +
        "whether the camera answered, failed or timed out, and who closed the session) to logcat under " +
        "the tag ZVPATCH. Use it to find out why a connected camera does not open Remote Shooting or " +
        "Import: `adb logcat -s ZVPATCH`. Not for everyday use.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_CREATORSAPP)
    extendWith("extensions/extension.mpe")

    execute {
        TraceOkFingerprint.method.addInstruction(0, "invoke-static {p1, p2}, $TRACE->ok(Ljava/lang/Object;Ljava/lang/Object;)V")
        TraceFailFingerprint.method.addInstruction(0, "invoke-static {p1, p2}, $TRACE->fail(Ljava/lang/Object;Ljava/lang/Object;)V")
        TraceTimeoutFingerprint.method.addInstruction(0, "invoke-static {p1}, $TRACE->timeout(Ljava/lang/Object;)V")
        TraceProbeFingerprint.method.addInstruction(0, "invoke-static {p1}, $TRACE->probeFailed(Ljava/lang/Object;)V")
        TraceTearDownFingerprint.method.addInstruction(0, "invoke-static {}, $TRACE->teardown()V")
        TraceSendFingerprint.method.addInstruction(0, "invoke-static {p0}, $TRACE->sending(Ljava/lang/Object;)V")
        // p1 = the EnumFunctionMode; the string label is built in the helper so no extra register is needed
        TraceInitModeFingerprint.method.addInstruction(0, "invoke-static {p1}, $TRACE->initMode(Ljava/lang/Object;)V")
        TraceSwitchModeFingerprint.method.addInstruction(0, "invoke-static {p1}, $TRACE->switchMode(Ljava/lang/Object;)V")
        TraceConnectorFingerprint.method.addInstruction(0, "invoke-static {p1}, $TRACE->function(Ljava/lang/Object;)V")
    }
}
