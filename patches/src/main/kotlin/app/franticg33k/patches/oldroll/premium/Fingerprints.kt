package app.franticg33k.patches.oldroll.premium

import app.morphe.patcher.Fingerprint

object PurchaseSharedPrefManagerIsSkuPurchasedFingerprint : Fingerprint(
    definingClass = "Lcom/lightcone/analogcam/dao/PurchaseSharedPrefManager;",
    name = "isSkuPurchased",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
)

object PurchaseSharedPrefManagerGetProMonthPurchaseTimeFingerprint : Fingerprint(
    definingClass = "Lcom/lightcone/analogcam/dao/PurchaseSharedPrefManager;",
    name = "getProMonthPurchaseTime",
    returnType = "J",
    parameters = listOf("J"),
)

object PurchaseSharedPrefManagerGetProYearPurchaseTimeFingerprint : Fingerprint(
    definingClass = "Lcom/lightcone/analogcam/dao/PurchaseSharedPrefManager;",
    name = "getProYearPurchaseTime",
    returnType = "J",
    parameters = listOf("J"),
)

object BaseSharedPrefManagerGetBooleanFingerprint : Fingerprint(
    definingClass = "Lcom/lightcone/analogcam/dao/BaseSharedPrefManager;",
    name = "getBoolean",
    returnType = "Z",
    parameters = listOf("Landroid/content/SharedPreferences;", "Ljava/lang/String;", "Z"),
)

object BaseSharedPrefManagerPutBooleanFingerprint : Fingerprint(
    definingClass = "Lcom/lightcone/analogcam/dao/BaseSharedPrefManager;",
    name = "putBoolean",
    returnType = "V",
    parameters = listOf("Landroid/content/SharedPreferences;", "Ljava/lang/String;", "Z"),
)
