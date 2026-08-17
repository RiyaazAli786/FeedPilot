package com.feedpilot.client.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Known-answer tests for [NaclSealedBox]. Every expected value was generated from libsodium
 * (PyNaCl) — the same library Instagram's servers use — so a pass proves byte-for-byte
 * compatibility with `crypto_box_seal`, not merely internal self-consistency.
 *
 * The generator, for reference:
 * ```
 * import nacl.bindings as b, hashlib
 * # secretbox: b.crypto_secretbox(msg, nonce, key)
 * # box:       b.crypto_box(msg, nonce, pk, sk); shared = b.crypto_scalarmult(sk, pk)
 * # seal:      eph_pk = b.crypto_scalarmult_base(eph_sk)
 * #            nonce  = hashlib.blake2b(eph_pk + recip_pk, digest_size=24).digest()
 * #            seal   = eph_pk + b.crypto_box(msg, nonce, recip_pk, eph_sk)
 * #            assert b.crypto_box_seal_open(seal, recip_pk, recip_sk) == msg
 * ```
 * X25519 itself is Tink's (RFC 7748) in production; here the ephemeral public key and shared
 * secret are injected so the pure-NaCl layers are what gets checked.
 */
class NaclSealedBoxTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[2 * i], 16) shl 4) + Character.digit(s[2 * i + 1], 16)).toByte()
        }
        return out
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `blake2b matches RFC 7693 for abc`() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            toHex(NaclSealedBox.blake2b(hex("616263"), 64))
        )
    }

    @Test
    fun `poly1305 matches RFC 8439`() {
        assertEquals(
            "a8061dc1305136c6c22b8baf0c0127a9",
            toHex(
                NaclSealedBox.poly1305(
                    ascii("Cryptographic Forum Research Group"),
                    hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
                )
            )
        )
    }

    @Test
    fun `hsalsa20 derives the box key libsodium computes`() {
        // beforenm = HSalsa20(scalarmult(sk, pk), 0^16) — the canonical NaCl "firstkey".
        assertEquals(
            "1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389",
            toHex(
                NaclSealedBox.hsalsa20(
                    hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"),
                    ByteArray(16)
                )
            )
        )
    }

    @Test
    fun `secretbox matches libsodium`() {
        assertEquals(
            "7c5a9ef2f79324f1da8cf99305f1779756d1fce94bc3a78adbdd41e658f3cd38" +
                "58c540878ed3266bd52c2de26ff9e9b99b4f9879354ebb809a71644c",
            toHex(
                NaclSealedBox.secretbox(
                    ascii("The quick brown fox jumps over 13 lazy dogs."),
                    hex("6465666768696a6b6c6d6e6f707172737475767778797a7b"),
                    hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
                )
            )
        )
    }

    @Test
    fun `crypto_box matches libsodium given the shared secret`() {
        assertEquals(
            "bcdee6578f18436a7a973b93fb59abc45dfb1729158e85866bed318cb83708d0" +
                "7b77cbee28562d0463565e312da51cd771",
            toHex(
                NaclSealedBox.boxAfterSharedSecret(
                    hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"),
                    ascii("message for a real crypto_box KAT"),
                    hex("69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37")
                )
            )
        )
    }

    @Test
    fun `crypto_box_seal reproduces a libsodium sealed box`() {
        // Recipient public key and the injected ephemeral public / shared secret come from
        // libsodium; crypto_box_seal_open confirmed (in the generator) this seal opens to the msg.
        val ephemeralPublic = hex("ed7749b4d989f6957f3bfde6c56767e988e21c9f8784d91d610011cd553f9b06")
        val sharedSecret = hex("30d07dfd4b7819226c762159860bf503c5e12258230eb45ea22eabedd47c6503")
        val recipientPk = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

        val injectedScalarmult = object : NaclSealedBox.Scalarmult {
            override fun base(secretKey: ByteArray) = ephemeralPublic
            override fun shared(secretKey: ByteArray, peerPublicKey: ByteArray) = sharedSecret
        }

        val seal = NaclSealedBox.seal(
            message = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
            recipientPublicKey = recipientPk,
            ephemeralSecret = hex("accd44eb8e93319c0570bc11005c0e0189d34ff02f6c17773411ad191293c98f"),
            scalarmult = injectedScalarmult
        )

        assertEquals(
            "ed7749b4d989f6957f3bfde6c56767e988e21c9f8784d91d610011cd553f9b06" +
                "060c60b76a69b5da63b3041c104644535979db1aac41d84944abb6bcd377aff7" +
                "b14394423a445f14af5961f00bb50511",
            toHex(seal)
        )
    }
}
