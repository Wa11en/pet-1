package ru.privatediary.security.lock

import ru.privatediary.security.keystore.KeystoreVault
import ru.privatediary.security.DiarySettings

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** App-level authentication. The random SQLCipher key is independent of the user's low-entropy PIN. */
class PinLock(context: Context, private val settings: DiarySettings) {
    enum class PinResult { SUCCESS, WRONG_PIN, THROTTLED }
    private val vault = KeystoreVault(context)
    private val preferences = context.getSharedPreferences("pin_throttle", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked
    private val _onboarded = MutableStateFlow(vault.contains("pin_verifier"))
    val onboarded: StateFlow<Boolean> = _onboarded
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    val isConfigured: Boolean get() = _onboarded.value
    private var backgroundAt: Long? = null
    private var pendingLock: Job? = null
    val retryRemaining: Long get() = remainingCooldownSeconds()

    suspend fun createPin(pin: String) = setPin(pin)
    suspend fun setPin(pin: String): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!isConfigured) { "PIN is already configured" }
            require(pin.length in 6..12 && pin.all { it in '0'..'9' }) { "PIN must contain 6–12 digits" }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = derive(pin, salt)
            try { vault.storeSecret("pin_verifier", salt + hash) } finally { hash.fill(0); salt.fill(0) }
            preferences.edit().clear().commit()
            _onboarded.value = true
            setUnlocked(true)
        }
    }

    suspend fun unlock(pin: String): PinResult = verifyPin(pin)
    suspend fun verifyPin(pin: String): PinResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (remainingCooldownSeconds() > 0) return@withLock PinResult.THROTTLED
            val verifier = checkNotNull(vault.loadSecret("pin_verifier")) { "PIN is not configured" }
            require(verifier.size == 48) { "Damaged PIN record" }
            val hash = derive(pin.take(128), verifier.copyOfRange(0, 16))
            val valid = try { MessageDigest.isEqual(hash, verifier.copyOfRange(16, 48)) }
                finally { hash.fill(0); verifier.fill(0) }
            if (valid) {
                preferences.edit().clear().commit()
                setUnlocked(true)
                PinResult.SUCCESS
            } else {
                val attempts = (preferences.getInt("failures", 0) + 1).coerceAtMost(20)
                val seconds = if (attempts < 5) 0L else (30L shl (attempts - 5).coerceAtMost(5)).coerceAtMost(900L)
                preferences.edit().putInt("failures", attempts)
                    .putLong("retry_after", System.currentTimeMillis() + seconds * 1000L).commit()
                PinResult.WRONG_PIN
            }
        }
    }

    fun remainingCooldownSeconds(): Long = ((preferences.getLong("retry_after", 0) - System.currentTimeMillis() + 999) / 1000L)
        .coerceIn(0, 900)
    fun unlockWithBiometrics() { check(isConfigured && settings.biometricsEnabled); setUnlocked(true) }
    fun lock() { setUnlocked(false) }
    private fun setUnlocked(value: Boolean) { _locked.value = !value; _unlocked.value = value }

    fun onBackground() {
        if (backgroundAt != null) return
        backgroundAt = SystemClock.elapsedRealtime()
        pendingLock?.cancel()
        if (settings.timeoutSeconds == 0) lock()
        else pendingLock = scope.launch { delay(settings.timeoutSeconds * 1000L); lock() }
    }
    fun onForeground() {
        val started = backgroundAt
        pendingLock?.cancel()
        if (started != null && SystemClock.elapsedRealtime() - started >= settings.timeoutSeconds * 1000L) lock()
        backgroundAt = null
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
    }
}
