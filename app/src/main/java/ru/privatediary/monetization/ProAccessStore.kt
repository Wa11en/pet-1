package ru.privatediary.monetization

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.privatediary.domain.monetization.SignedEntitlementVerifier
import ru.privatediary.security.keystore.KeystoreVault

/** A single authenticated encrypted record, using the same existing Keystore alias as the diary. */
interface ProSecretStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

class ProAccessStore(
    private val secrets: ProSecretStorage,
    private val verifier: SignedEntitlementVerifier,
) {
    constructor(context: Context, verifier: SignedEntitlementVerifier) : this(VaultProSecretStorage(context), verifier)

    private val mutex = Mutex()
    val isVerifierConfigured: Boolean get() = verifier.isConfigured

    /** Local only. Missing records are not created merely by starting or unlocking the app. */
    suspend fun readAccess(nowMillis: Long = System.currentTimeMillis()): ProAccessSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = readRecord() ?: return@withLock ProAccessSnapshot()
            ProAccessSnapshot(
                activeUntilMillis = record.entitlement?.let { verifier.verify(it, record.installationId, nowMillis)?.expiresAtMillis },
                hasAccount = record.accountToken != null,
                pendingCheckout = record.checkout,
            )
        }
    }

    /** Called by the Pay / Check buttons. A failed checkout reuses its persisted idempotency key. */
    suspend fun checkoutIdentity(): CheckoutIdentity = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = readRecord() ?: StoredAccess(UUID.randomUUID().toString())
            val prepared = record.copy(
                accountToken = record.accountToken ?: Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)),
                requestId = record.requestId ?: UUID.randomUUID().toString(),
            )
            writeRecord(prepared)
            CheckoutIdentity(prepared.installationId, requireNotNull(prepared.accountToken), requireNotNull(prepared.requestId))
        }
    }

    suspend fun saveCheckout(checkout: CheckoutSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = requireNotNull(readRecord()) { "No payment identity" }
            require(record.accountToken != null && record.requestId != null)
            writeRecord(record.copy(checkout = checkout))
        }
    }

    suspend fun clearPendingCheckout() = withContext(Dispatchers.IO) {
        mutex.withLock {
            readRecord()?.let { writeRecord(it.copy(checkout = null, requestId = null)) }
        }
    }

    /** A paid status, redirect or local boolean can never activate Pro. */
    suspend fun acceptEntitlement(raw: String, nowMillis: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = readRecord() ?: return@withLock false
            val entitlement = verifier.verify(raw, record.installationId, nowMillis) ?: return@withLock false
            val previousExpiry = record.entitlement?.let { verifier.verify(it, record.installationId, nowMillis)?.expiresAtMillis } ?: 0L
            if (entitlement.expiresAtMillis < previousExpiry) return@withLock false
            writeRecord(record.copy(entitlement = raw, checkout = null, requestId = null))
            true
        }
    }

    suspend fun recoveryCode(): String? = withContext(Dispatchers.IO) { mutex.withLock { readRecord()?.accountToken } }

    /** Only the installation ID is saved here. An incorrect recovery code never replaces the account. */
    suspend fun restoreIdentity(recoveryCode: String): CheckoutIdentity = withContext(Dispatchers.IO) {
        require(isRecoveryCode(recoveryCode)) { "Invalid recovery code" }
        mutex.withLock {
            val existing = readRecord()
            val record = existing ?: StoredAccess(UUID.randomUUID().toString()).also(::writeRecord)
            CheckoutIdentity(record.installationId, recoveryCode, UUID.randomUUID().toString())
        }
    }

    suspend fun acceptRestoredEntitlement(
        identity: CheckoutIdentity,
        raw: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = readRecord() ?: return@withLock false
            if (record.installationId != identity.installationId) return@withLock false
            val entitlement = verifier.verify(raw, record.installationId, nowMillis) ?: return@withLock false
            val previousExpiry = record.entitlement?.let { verifier.verify(it, record.installationId, nowMillis)?.expiresAtMillis } ?: 0L
            if (entitlement.expiresAtMillis < previousExpiry) return@withLock false
            writeRecord(record.copy(accountToken = identity.accountToken, entitlement = raw, requestId = null, checkout = null))
            true
        }
    }

    private fun readRecord(): StoredAccess? {
        val bytes = secrets.read() ?: return null
        try {
            require(bytes.size <= 64 * 1024) { "Damaged payment record" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.keys().asSequence().toSet() == setOf("version", "installationId", "accountToken", "requestId", "entitlement", "checkout"))
            require(json.getInt("version") == 1)
            val installationId = json.getString("installationId").also { require(isCanonicalUuid(it)) }
            val accountToken = json.nullableString("accountToken")?.also { require(isRecoveryCode(it)) }
            val requestId = json.nullableString("requestId")?.also { require(isCanonicalUuid(it)) }
            val entitlement = json.nullableString("entitlement")?.also { require(it.length in 1..8192) }
            val checkout = if (json.isNull("checkout")) null else json.getJSONObject("checkout").let {
                CheckoutSession(it.getString("checkoutId"), it.getString("paymentUrl"))
            }
            require(checkout == null || accountToken != null && requestId != null)
            return StoredAccess(installationId, accountToken, requestId, checkout, entitlement)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeRecord(record: StoredAccess) {
        val json = JSONObject().put("version", 1).put("installationId", record.installationId)
            .put("accountToken", record.accountToken ?: JSONObject.NULL)
            .put("requestId", record.requestId ?: JSONObject.NULL)
            .put("entitlement", record.entitlement ?: JSONObject.NULL)
            .put("checkout", record.checkout?.let {
                JSONObject().put("checkoutId", it.checkoutId).put("paymentUrl", it.paymentUrl)
            } ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        try { secrets.write(bytes) } finally { bytes.fill(0) }
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private data class StoredAccess(
        val installationId: String,
        val accountToken: String? = null,
        val requestId: String? = null,
        val checkout: CheckoutSession? = null,
        val entitlement: String? = null,
    ) {
        override fun toString() = "StoredAccess(<redacted>)"
    }
}

private class VaultProSecretStorage(context: Context) : ProSecretStorage {
    private val appContext = context.applicationContext
    private val vault by lazy { KeystoreVault(appContext) }
    override fun read(): ByteArray? = vault.loadSecret("pro_access_v1")
    override fun write(bytes: ByteArray) = vault.storeSecret("pro_access_v1", bytes)
}
