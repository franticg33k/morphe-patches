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


