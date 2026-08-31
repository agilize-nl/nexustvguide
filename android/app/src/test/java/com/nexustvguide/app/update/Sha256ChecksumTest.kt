package com.nexustvguide.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Sha256ChecksumTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `calculates correct sha256 for known test vector`() {
        val file = tempFolder.newFile("test.txt")
        // "Hello, World!" -> dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f
        file.writeText("Hello, World!")

        val expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"
        val actualHash = Sha256Checksum.calculate(file)

        assertEquals(expectedHash, actualHash)
        assertTrue(Sha256Checksum.verify(file, expectedHash))
        assertTrue(Sha256Checksum.verify(file, expectedHash.uppercase()))
    }

    @Test
    fun `mutated single byte fails verification`() {
        val file = tempFolder.newFile("test_mutated.txt")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val originalHash = Sha256Checksum.calculate(file)

        // Mutate one byte
        file.writeBytes(byteArrayOf(1, 2, 99, 4, 5))

        val mutatedHash = Sha256Checksum.calculate(file)
        assertFalse(originalHash == mutatedHash)
        assertFalse(Sha256Checksum.verify(file, originalHash))
    }
}
