package com.nexustvguide.app.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object Sha256Checksum {

    private const val BUFFER_SIZE = 8192

    /**
     * Berekent de SHA-256 hash van een bestand en geeft een 64-karakter lowercase hex string terug.
     */
    fun calculate(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        val hashBytes = digest.digest()
        val hexChars = CharArray(hashBytes.size * 2)
        val hexDigits = "0123456789abcdef"
        for (i in hashBytes.indices) {
            val v = hashBytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexDigits[v ushr 4]
            hexChars[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Verifieert of de SHA-256 hash van een bestand exact overeenkomt met de verwachte hash (case-insensitive).
     */
    fun verify(file: File, expectedSha256: String): Boolean {
        val actual = calculate(file)
        return actual.equals(expectedSha256.trim(), ignoreCase = true)
    }
}
