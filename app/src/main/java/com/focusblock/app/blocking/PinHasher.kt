package com.focusblock.app.blocking

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted hashing for the commitment PIN.
 *
 * The previous Hard Mode stored the PIN in plain text in the settings table
 * and compared it with `pin == storedPin`. A PIN is not a secret worth a KDF's
 * full cost here, but storing it in the clear in a database that also backs up
 * is careless, so it is salted and hashed.
 */
object PinHasher {

    private const val ITERATIONS = 10_000

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var value = (salt + pin).toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) { value = digest.digest(value) }
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }

    /** Constant-time comparison so a wrong PIN leaks nothing through timing. */
    fun verify(pin: String, salt: String, expectedHash: String): Boolean {
        if (salt.isEmpty() || expectedHash.isEmpty()) return false
        val actual = hash(pin, salt)
        if (actual.length != expectedHash.length) return false
        var diff = 0
        for (i in actual.indices) {
            diff = diff or (actual[i].code xor expectedHash[i].code)
        }
        return diff == 0
    }
}
