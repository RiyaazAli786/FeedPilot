package com.feedpilot.client.common

import java.math.BigInteger

/**
 * A from-scratch implementation of libsodium's `crypto_box_seal` — the sealed box Instagram's web
 * login uses to wrap the AES key inside `enc_password`.
 *
 * A sealed box is `crypto_box` (X25519 + XSalsa20-Poly1305) under an ephemeral sender key:
 *
 * ```
 * crypto_box_seal(m, recipientPk):
 *     ephSk        = random X25519 secret
 *     ephPk        = X25519_base(ephSk)
 *     nonce        = Blake2b-192(ephPk || recipientPk)          // 24 bytes, unkeyed
 *     shared       = X25519(ephSk, recipientPk)
 *     c            = crypto_box(m, nonce, recipientPk, ephSk)     // = XSalsa20-Poly1305(m, nonce, HSalsa20(shared))
 *     return ephPk || c                                          // 32 + 16 + |m|
 * ```
 *
 * The X25519 scalar multiplication is delegated to a [Scalarmult] (Tink's vetted implementation in
 * production); everything else — Salsa20, HSalsa20, XSalsa20, Poly1305, Blake2b — is here and is
 * pinned by known-answer vectors generated from libsodium in `NaclSealedBoxTest`.
 *
 * All multi-byte integers are little-endian, per the NaCl/RFC specifications.
 */
object NaclSealedBox {

    /** X25519 scalar multiplication, injected so the primitive crypto below stays testable. */
    interface Scalarmult {
        /** X25519 base point multiplication: derive the public key from a 32-byte secret. */
        fun base(secretKey: ByteArray): ByteArray
        /** X25519 of a 32-byte secret and a 32-byte peer public key → 32-byte shared secret. */
        fun shared(secretKey: ByteArray, peerPublicKey: ByteArray): ByteArray
    }

    /**
     * Seals [message] to [recipientPublicKey] (32 bytes). [ephemeralSecret] is the random 32-byte
     * X25519 secret; it is a parameter only so a test can pin it — production passes fresh randomness.
     * Returns `ephemeralPublic(32) || tag(16) || ciphertext(|message|)`.
     */
    fun seal(
        message: ByteArray,
        recipientPublicKey: ByteArray,
        ephemeralSecret: ByteArray,
        scalarmult: Scalarmult
    ): ByteArray {
        require(recipientPublicKey.size == 32) { "recipient public key must be 32 bytes" }
        require(ephemeralSecret.size == 32) { "ephemeral secret must be 32 bytes" }

        val ephemeralPublic = scalarmult.base(ephemeralSecret)
        val nonce = blake2b(ephemeralPublic + recipientPublicKey, 24)
        val shared = scalarmult.shared(ephemeralSecret, recipientPublicKey)
        val boxed = boxAfterSharedSecret(shared, message, nonce)
        return ephemeralPublic + boxed
    }

    // ---------------------------------------------------------------------------------------------
    // crypto_box / crypto_secretbox (XSalsa20-Poly1305)
    // ---------------------------------------------------------------------------------------------

    /** crypto_box with the raw X25519 shared secret already computed. Returns tag(16) || ciphertext. */
    fun boxAfterSharedSecret(sharedSecret: ByteArray, message: ByteArray, nonce: ByteArray): ByteArray {
        // crypto_box derives its symmetric key by running HSalsa20 over the DH secret with a zero nonce.
        val key = hsalsa20(sharedSecret, ByteArray(16))
        return secretbox(message, nonce, key)
    }

    /** crypto_secretbox_easy: XSalsa20-Poly1305. Returns tag(16) || ciphertext(|message|). */
    fun secretbox(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == 24) { "nonce must be 24 bytes" }
        require(key.size == 32) { "key must be 32 bytes" }

        // NaCl pads the message with 32 leading zero bytes and XORs the whole thing against the
        // XSalsa20 keystream from offset 0. The first 32 keystream bytes therefore become the
        // Poly1305 key, and the message itself is encrypted with the keystream from byte 32 on.
        val keystream = xsalsa20(key, nonce, 32 + message.size)
        val polyKey = keystream.copyOfRange(0, 32)

        val ciphertext = ByteArray(message.size)
        for (i in message.indices) ciphertext[i] = (message[i].toInt() xor keystream[32 + i].toInt()).toByte()

        val tag = poly1305(ciphertext, polyKey)
        return tag + ciphertext
    }

    // ---------------------------------------------------------------------------------------------
    // Salsa20 family
    // ---------------------------------------------------------------------------------------------

    // "expand 32-byte k"
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    private fun rotl(x: Int, c: Int): Int = (x shl c) or (x ushr (32 - c))

    /**
     * The Salsa20 core: 20 rounds over the 16-word state. With [feedforward] it adds the input back
     * (the stream-cipher block function); without it, it is the HSalsa20 core.
     */
    private fun salsaRounds(state: IntArray): IntArray {
        val x = state.copyOf()
        repeat(10) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7);  x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7);   x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13);  x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7);  x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)

            x[1] = x[1] xor rotl(x[0] + x[3], 7);   x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13);  x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7);   x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13);  x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13);  x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        return x
    }

    /** HSalsa20: 32-byte output used for key derivation (no feedforward addition). */
    fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce16.size == 16) { "hsalsa20 nonce must be 16 bytes" }

        val state = IntArray(16)
        state[0] = SIGMA[0]; state[5] = SIGMA[1]; state[10] = SIGMA[2]; state[15] = SIGMA[3]
        state[1] = loadLE(key, 0);  state[2] = loadLE(key, 4);  state[3] = loadLE(key, 8);  state[4] = loadLE(key, 12)
        state[11] = loadLE(key, 16); state[12] = loadLE(key, 20); state[13] = loadLE(key, 24); state[14] = loadLE(key, 28)
        state[6] = loadLE(nonce16, 0); state[7] = loadLE(nonce16, 4); state[8] = loadLE(nonce16, 8); state[9] = loadLE(nonce16, 12)

        val x = salsaRounds(state)
        val out = ByteArray(32)
        storeLE(out, 0, x[0]); storeLE(out, 4, x[5]); storeLE(out, 8, x[10]); storeLE(out, 12, x[15])
        storeLE(out, 16, x[6]); storeLE(out, 20, x[7]); storeLE(out, 24, x[8]); storeLE(out, 28, x[9])
        return out
    }

    /** One 64-byte Salsa20 keystream block for [key], 8-byte [nonce8], and 64-bit block [counter]. */
    private fun salsa20Block(key: ByteArray, nonce8: ByteArray, counter: Long): ByteArray {
        val state = IntArray(16)
        state[0] = SIGMA[0]; state[5] = SIGMA[1]; state[10] = SIGMA[2]; state[15] = SIGMA[3]
        state[1] = loadLE(key, 0);  state[2] = loadLE(key, 4);  state[3] = loadLE(key, 8);  state[4] = loadLE(key, 12)
        state[11] = loadLE(key, 16); state[12] = loadLE(key, 20); state[13] = loadLE(key, 24); state[14] = loadLE(key, 28)
        state[6] = loadLE(nonce8, 0); state[7] = loadLE(nonce8, 4)
        state[8] = (counter and 0xffffffffL).toInt()
        state[9] = (counter ushr 32).toInt()

        val x = salsaRounds(state)
        val out = ByteArray(64)
        for (i in 0 until 16) storeLE(out, i * 4, x[i] + state[i]) // feedforward
        return out
    }

    /** XSalsa20 keystream of [length] bytes: HSalsa20 subkey, then Salsa20 over the last 8 nonce bytes. */
    fun xsalsa20(key: ByteArray, nonce24: ByteArray, length: Int): ByteArray {
        require(nonce24.size == 24) { "xsalsa20 nonce must be 24 bytes" }
        val subKey = hsalsa20(key, nonce24.copyOfRange(0, 16))
        val streamNonce = nonce24.copyOfRange(16, 24)

        val out = ByteArray(length)
        var offset = 0
        var counter = 0L
        while (offset < length) {
            val block = salsa20Block(subKey, streamNonce, counter)
            val n = minOf(64, length - offset)
            System.arraycopy(block, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Poly1305 — one-time authenticator over p = 2^130 - 5 (BigInteger; MAC size is tiny)
    // ---------------------------------------------------------------------------------------------

    private val POLY_P = BigInteger.valueOf(2).pow(130).subtract(BigInteger.valueOf(5))
    private val POLY_CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)

    fun poly1305(message: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "poly1305 key must be 32 bytes" }
        val r = leToBig(key, 0, 16).and(POLY_CLAMP)
        val s = leToBig(key, 16, 16)

        var acc = BigInteger.ZERO
        var i = 0
        while (i < message.size) {
            val n = minOf(16, message.size - i)
            // Interpret the block as a little-endian number, then set the bit just above it.
            var block = leToBig(message, i, n)
            block = block.add(BigInteger.ONE.shiftLeft(8 * n))
            acc = acc.add(block)
            acc = r.multiply(acc).mod(POLY_P)
            i += n
        }
        acc = acc.add(s)

        // Low 128 bits, little-endian.
        val out = ByteArray(16)
        val mask = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val bytes = acc.and(mask).toByteArray() // big-endian, possibly with sign byte / short
        for (j in 0 until 16) {
            val srcIndex = bytes.size - 1 - j
            out[j] = if (srcIndex >= 0) bytes[srcIndex] else 0
        }
        return out
    }

    private fun leToBig(bytes: ByteArray, offset: Int, len: Int): BigInteger {
        val be = ByteArray(len + 1) // leading zero keeps it positive
        for (j in 0 until len) be[len - j] = bytes[offset + j]
        return BigInteger(be)
    }

    // ---------------------------------------------------------------------------------------------
    // Blake2b (RFC 7693), unkeyed, arbitrary output length ≤ 64
    // ---------------------------------------------------------------------------------------------

    private val BLAKE2B_IV = longArrayOf(
        0x6a09e667f3bcc908uL.toLong(), 0xbb67ae8584caa73buL.toLong(),
        0x3c6ef372fe94f82buL.toLong(), 0xa54ff53a5f1d36f1uL.toLong(),
        0x510e527fade682d1uL.toLong(), 0x9b05688c2b3e6c1fuL.toLong(),
        0x1f83d9abfb41bd6buL.toLong(), 0x5be0cd19137e2179uL.toLong()
    )

    private val BLAKE2B_SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3)
    )

    fun blake2b(input: ByteArray, outLen: Int): ByteArray {
        require(outLen in 1..64) { "blake2b output length must be 1..64" }

        val h = BLAKE2B_IV.copyOf()
        // Parameter block for an unkeyed hash: digest length, key length 0, fanout 1, depth 1.
        h[0] = h[0] xor 0x01010000L xor outLen.toLong()

        var counter = 0L
        var offset = 0
        // Process every full 128-byte block except the last chunk (which is finalized below).
        while (input.size - offset > 128) {
            counter += 128
            compress(h, input, offset, counter, false)
            offset += 128
        }

        val remaining = input.size - offset
        counter += remaining.toLong()
        val lastBlock = ByteArray(128)
        System.arraycopy(input, offset, lastBlock, 0, remaining)
        compress(h, lastBlock, 0, counter, true)

        val out = ByteArray(outLen)
        var produced = 0
        var word = 0
        while (produced < outLen) {
            val n = minOf(8, outLen - produced)
            var v = h[word]
            for (b in 0 until n) {
                out[produced + b] = (v and 0xff).toByte()
                v = v ushr 8
            }
            produced += n
            word++
        }
        return out
    }

    private fun rotr64(x: Long, c: Int): Long = (x ushr c) or (x shl (64 - c))

    private fun compress(h: LongArray, block: ByteArray, blockOffset: Int, counter: Long, last: Boolean) {
        val m = LongArray(16) { loadLE64(block, blockOffset + it * 8) }
        val v = LongArray(16)
        for (i in 0 until 8) { v[i] = h[i]; v[i + 8] = BLAKE2B_IV[i] }
        v[12] = v[12] xor counter
        // v[13] xor counter-high: message length never exceeds 2^63 here, so the high word is 0.
        if (last) v[14] = v[14].inv()

        for (round in 0 until 12) {
            val s = BLAKE2B_SIGMA[round]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = rotr64(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = rotr64(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = rotr64(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = rotr64(v[b] xor v[c], 63)
    }

    // ---------------------------------------------------------------------------------------------
    // Little-endian helpers
    // ---------------------------------------------------------------------------------------------

    private fun loadLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun storeLE(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v ushr 8) and 0xff).toByte()
        b[o + 2] = ((v ushr 16) and 0xff).toByte()
        b[o + 3] = ((v ushr 24) and 0xff).toByte()
    }

    private fun loadLE64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }
}
