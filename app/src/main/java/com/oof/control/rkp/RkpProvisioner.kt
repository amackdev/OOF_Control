package com.oof.control.rkp

import android.os.Build
import android.util.Log
import com.oof.control.rkp.CborUtils.decode
import com.oof.control.rkp.CborUtils.encodeArray
import com.oof.control.rkp.CborUtils.encodeBytes
import com.oof.control.rkp.CborUtils.encodeInt
import com.oof.control.rkp.CborUtils.encodeMap
import com.oof.control.rkp.CborUtils.encodeText
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * Software-only RKP (Remote Key Provisioning) client.
 *
 * Aligned with Duck-ToolBox's experimental CSR v3 flow:
 * no EEK encryption or ProtectedData — just DICE chain + signed CSR.
 */
class RkpProvisioner(
    private val seed: ByteArray,
    private val config: DeviceRkpConfig,
    private val curve: String = "p256",
) {
    companion object {
        private const val TAG = "RkpProvisioner"
        private const val RKP_SERVER = "https://remoteprovisioning.googleapis.com/v1"

        private const val ALG_ES256 = -7L
        private const val CWT_ISSUER = 1L
        private const val CWT_SUBJECT = 2L
        private const val DICE_SUBJECT_PUB_KEY = -4670552L
        private const val DICE_KEY_USAGE = -4670553L

        private val P256_ORDER = java.math.BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16
        )
    }

    private val signingKeyPair: KeyPair
    private val algId: Long = ALG_ES256
    private val pubRaw: ByteArray

    init {
        require(seed.size == 32) { "Seed must be 32 bytes" }
        require(curve == "p256") { "Only P-256 is supported" }
        signingKeyPair = deriveP256(seed)
        val pub = signingKeyPair.public as ECPublicKey
        val x = pub.w.affineX.toByteArray().takeLast(32).toByteArray().padStart32()
        val y = pub.w.affineY.toByteArray().takeLast(32).toByteArray().padStart32()
        pubRaw = x + y
    }

    fun provision(): KeyboxResult {
        Log.i(TAG, "Starting RKP provisioning (curve=$curve)")

        val ecKp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val ecPub = ecKp.public as ECPublicKey
        val ecCosePub = buildEcCoseKey(ecPub)

        Log.i(TAG, "Fetching challenge from server...")
        val challenge = fetchChallenge()

        Log.i(TAG, "Building CSR v3...")
        val csrBytes = buildCsr(challenge, listOf(ecCosePub))

        Log.i(TAG, "Submitting CSR...")
        val certChainsDer = submitCsr(csrBytes, challenge)
        if (certChainsDer.isEmpty()) throw RkpException("No certificate chains received from server")

        val certs = parseDerCertChain(certChainsDer[0])
        Log.i(TAG, "Received ${certs.size} certificates")

        val sortedCerts = sortCertChain(certs)
        val xml = buildKeyboxXml(ecKp.private as ECPrivateKey, sortedCerts)

        return KeyboxResult(
            keyboxXml = xml,
            certCount = certs.size,
            leafSubject = certs.firstOrNull()?.subjectX500Principal?.name ?: "unknown",
            leafNotAfter = sortedCerts.firstOrNull()?.notAfter?.toString() ?: "unknown",
        )
    }

    // ---- Key derivation ----

    private fun deriveP256(seed: ByteArray): KeyPair {
        val scalar = java.math.BigInteger(1, seed).mod(P256_ORDER)
        val s = if (scalar == java.math.BigInteger.ZERO) java.math.BigInteger.ONE else scalar

        val P = java.math.BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val A = java.math.BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
        val GX = java.math.BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
        val GY = java.math.BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
        val TWO = java.math.BigInteger.TWO
        val THREE = java.math.BigInteger.valueOf(3)

        fun pointDouble(px: java.math.BigInteger, py: java.math.BigInteger): Pair<java.math.BigInteger, java.math.BigInteger> {
            val lam = (THREE.multiply(px).multiply(px).add(A)).multiply(TWO.multiply(py).modInverse(P)).mod(P)
            val rx = lam.multiply(lam).subtract(TWO.multiply(px)).mod(P)
            val ry = lam.multiply(px.subtract(rx)).subtract(py).mod(P)
            return rx to ry
        }

        fun pointAdd(
            px: java.math.BigInteger, py: java.math.BigInteger,
            qx: java.math.BigInteger, qy: java.math.BigInteger
        ): Pair<java.math.BigInteger, java.math.BigInteger> {
            if (px == qx && py == qy) return pointDouble(px, py)
            val lam = qy.subtract(py).multiply(qx.subtract(px).modInverse(P)).mod(P)
            val rx = lam.multiply(lam).subtract(px).subtract(qx).mod(P)
            val ry = lam.multiply(px.subtract(rx)).subtract(py).mod(P)
            return rx to ry
        }

        var result: Pair<java.math.BigInteger, java.math.BigInteger>? = null
        var cur = GX to GY
        var k = s
        while (k > java.math.BigInteger.ZERO) {
            if (k.testBit(0)) {
                result = if (result == null) cur else pointAdd(result.first, result.second, cur.first, cur.second)
            }
            cur = pointDouble(cur.first, cur.second)
            k = k.shiftRight(1)
        }

        val pubPoint = java.security.spec.ECPoint(result!!.first, result!!.second)
        val ecSpec = java.security.spec.ECParameterSpec(
            java.security.spec.EllipticCurve(
                java.security.spec.ECFieldFp(P), A,
                java.math.BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
            ),
            java.security.spec.ECPoint(GX, GY),
            P256_ORDER, 1
        )

        val kf = KeyFactory.getInstance("EC")
        val ecPriv = kf.generatePrivate(ECPrivateKeySpec(s, ecSpec)) as ECPrivateKey
        val ecPub = kf.generatePublic(ECPublicKeySpec(pubPoint, ecSpec)) as ECPublicKey
        return KeyPair(ecPub, ecPriv)
    }

    // ---- COSE structures ----

    private fun coseKey(): ByteArray {
        val x = pubRaw.copyOfRange(0, 32)
        val y = pubRaw.copyOfRange(32, 64)
        return encodeMap(listOf(
            encodeInt(1) to encodeInt(2),          // kty: EC2
            encodeInt(3) to encodeInt(ALG_ES256),  // alg: ES256
            encodeInt(-1) to encodeInt(1),         // crv: P-256
            encodeInt(-2) to encodeBytes(x),
            encodeInt(-3) to encodeBytes(y),
        ))
    }

    private fun sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(signingKeyPair.private)
        sig.update(data)
        val derSig = sig.sign()
        return derToRawEcdsaSig(derSig)
    }

    private fun coseSign1(protected: ByteArray, payload: ByteArray): ByteArray {
        val sigInput = encodeArray(listOf(
            encodeText("Signature1"),
            encodeBytes(protected),
            encodeBytes(ByteArray(0)),
            encodeBytes(payload),
        ))
        val signature = sign(sigInput)
        return encodeArray(listOf(
            encodeBytes(protected),
            encodeMap(emptyList()),
            encodeBytes(payload),
            encodeBytes(signature),
        ))
    }

    // ---- DICE chain ----

    private fun buildDiceEntry(): ByteArray {
        val algProtected = encodeMap(listOf(encodeInt(1) to encodeInt(algId)))
        val payload = encodeMap(listOf(
            encodeInt(CWT_ISSUER) to encodeText(config.diceIssuer),
            encodeInt(CWT_SUBJECT) to encodeText(config.diceSubject),
            encodeInt(DICE_SUBJECT_PUB_KEY) to encodeBytes(coseKey()),
            encodeInt(DICE_KEY_USAGE) to encodeBytes(byteArrayOf(0x20)),
        ))
        return coseSign1(algProtected, payload)
    }

    private fun buildDiceChain(): ByteArray {
        return encodeArray(listOf(coseKey(), buildDiceEntry()))
    }

    // ---- Device info — field order matches Duck-ToolBox exactly ----

    private fun buildDeviceInfo(): ByteArray {
        val entries = mutableListOf<Pair<ByteArray, ByteArray>>()
        fun str(k: String, v: String) { entries.add(encodeText(k) to encodeText(v)) }
        fun intV(k: String, v: Long) { entries.add(encodeText(k) to encodeInt(v)) }

        str("brand", config.brand)
        str("manufacturer", config.manufacturer)
        str("product", config.product)
        str("model", config.model)
        str("device", config.device)
        str("vb_state", config.vbState)
        str("bootloader_state", config.bootloaderState)
        intV("system_patch_level", config.systemPatchLevel)
        intV("boot_patch_level", config.bootPatchLevel)
        intV("vendor_patch_level", config.vendorPatchLevel)
        str("security_level", "tee")
        intV("fused", config.fused.toLong())
        if (config.vbmetaDigest.isNotEmpty()) {
            entries.add(encodeText("vbmeta_digest") to encodeBytes(hexToBytes(config.vbmetaDigest)))
        }
        if (config.osVersion.isNotBlank()) {
            str("os_version", config.osVersion)
        }

        return encodeMap(entries)
    }

    // ---- EC key for attestation (not the DICE signing key) ----

    private fun buildEcCoseKey(pub: ECPublicKey): ByteArray {
        val x = pub.w.affineX.toByteArray().takeLast(32).toByteArray().padStart32()
        val y = pub.w.affineY.toByteArray().takeLast(32).toByteArray().padStart32()
        return encodeMap(listOf(
            encodeInt(1) to encodeInt(2),
            encodeInt(3) to encodeInt(ALG_ES256),
            encodeInt(-1) to encodeInt(1),
            encodeInt(-2) to encodeBytes(x),
            encodeInt(-3) to encodeBytes(y),
        ))
    }

    // ---- CSR v3 (experimental — no EEK encryption, matches Duck-ToolBox production) ----

    private fun buildCsr(
        challenge: ByteArray,
        keysToSign: List<ByteArray>,
    ): ByteArray {
        val deviceInfo = buildDeviceInfo()
        val csrPayload = encodeArray(listOf(
            encodeInt(3),
            encodeText("keymint"),
            deviceInfo,
            encodeArray(keysToSign),
        ))

        val dice = buildDiceChain()

        val signedPayload = encodeArray(listOf(
            encodeBytes(challenge),
            encodeBytes(csrPayload),
        ))
        val algProtected = encodeMap(listOf(encodeInt(1) to encodeInt(algId)))
        val signedData = coseSign1(algProtected, signedPayload)

        val items = mutableListOf(
            encodeInt(1),
            encodeMap(emptyList()),
            dice,
            signedData,
        )
        if (config.fingerprint.isNotEmpty()) {
            items.add(encodeMap(listOf(
                encodeText("fingerprint") to encodeText(config.fingerprint)
            )))
        }
        return encodeArray(items)
    }

    // ---- Server communication ----

    private fun fetchChallenge(): ByteArray {
        val url = URL("$RKP_SERVER:fetchEekChain")
        val body = encodeMap(listOf(
            encodeText("fingerprint") to encodeText(config.fingerprint),
            encodeText("id") to encodeInt(42),
        ))

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/cbor")
        conn.setRequestProperty("Accept", "application/cbor")
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.outputStream.use { it.write(body) }

        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RkpException("fetchEekChain failed: HTTP ${conn.responseCode}: $err")
        }

        val respBytes = conn.inputStream.use { it.readBytes() }
        val result = decode(respBytes) as? List<*>
            ?: throw RkpException("fetchEekChain: unexpected response format")

        val challenge = result[1] as? ByteArray
            ?: throw RkpException("No challenge in fetchEekChain response")
        if (challenge.size > 64) throw RkpException("Challenge too large: ${challenge.size} bytes")

        return challenge
    }

    private fun submitCsr(csrBytes: ByteArray, challenge: ByteArray): List<ByteArray> {
        val challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
        val url = URL("$RKP_SERVER:signCertificates?challenge=$challengeB64")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/cbor")
        conn.setRequestProperty("Accept", "application/cbor")
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.outputStream.use { it.write(csrBytes) }

        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            if (conn.responseCode == 444) {
                throw DeviceNotRegisteredError("Device DICE chain not recognized by server: $err")
            }
            throw RkpException("signCertificates failed: HTTP ${conn.responseCode}: $err")
        }

        val respBytes = conn.inputStream.use { it.readBytes() }
        val result = decode(respBytes) as? List<*> ?: return emptyList()

        val inner = (result[0] as? List<*>) ?: result
        if (inner.size < 2) return emptyList()
        val shared = inner[0] as? ByteArray ?: ByteArray(0)
        val uniqueList = inner[1] as? List<*> ?: return emptyList()

        return uniqueList.filterIsInstance<ByteArray>().map { shared + it }
    }

    // ---- Certificate parsing ----

    private fun parseDerCertChain(data: ByteArray): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        var pos = 0
        while (pos < data.size && data[pos] == 0x30.toByte()) {
            val headerLen: Int
            val contentLen: Int
            if (data[pos + 1].toInt() and 0x80 != 0) {
                val numLenBytes = data[pos + 1].toInt() and 0x7F
                var len = 0L
                for (i in 0 until numLenBytes) {
                    len = (len shl 8) or (data[pos + 2 + i].toLong() and 0xFF)
                }
                contentLen = len.toInt()
                headerLen = 2 + numLenBytes
            } else {
                contentLen = data[pos + 1].toInt() and 0xFF
                headerLen = 2
            }
            val certLen = headerLen + contentLen
            try {
                val cert = cf.generateCertificate(data.inputStream(pos, certLen)) as X509Certificate
                certs.add(cert)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cert at offset $pos", e)
                break
            }
            pos += certLen
        }
        return certs
    }

    private fun ByteArray.inputStream(offset: Int, length: Int) =
        java.io.ByteArrayInputStream(this, offset, length)

    private fun sortCertChain(certs: List<X509Certificate>): List<X509Certificate> {
        if (certs.size <= 1) return certs
        val bySubject = certs.associateBy { it.subjectX500Principal }
        val issuers = certs.map { it.issuerX500Principal }.toSet()
        val leaf = certs.firstOrNull { it.subjectX500Principal !in issuers }
            ?: certs.first()

        val sorted = mutableListOf(leaf)
        var current = leaf
        val seen = mutableSetOf(current)
        while (sorted.size < certs.size) {
            val parent = bySubject[current.issuerX500Principal]
            if (parent == null || parent in seen) break
            sorted.add(parent)
            seen.add(parent)
            current = parent
        }
        certs.filter { it !in seen }.forEach { sorted.add(it) }
        return sorted
    }

    // ---- Keybox XML export ----

    private fun buildKeyboxXml(ecPrivKey: ECPrivateKey, certChain: List<X509Certificate>): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\"?>")
        sb.appendLine("<AndroidAttestation>")
        sb.appendLine("    <NumberOfKeyboxes>1</NumberOfKeyboxes>")
        sb.appendLine("    <Keybox DeviceID=\"${config.manufacturer}-${randomHex(6)}\">")
        sb.appendLine("        <Key algorithm=\"ecdsa\">")
        sb.appendLine("            <PrivateKey format=\"pem\">")

        val keyPem = pemEncode("EC PRIVATE KEY", ecPrivKeyToSec1(ecPrivKey))
        keyPem.lines().forEach { sb.appendLine("                $it") }

        sb.appendLine("            </PrivateKey>")
        sb.appendLine("            <CertificateChain>")
        sb.appendLine("                <NumberOfCertificates>${certChain.size}</NumberOfCertificates>")

        for (cert in certChain) {
            sb.appendLine("                <Certificate format=\"pem\">")
            val certPem = pemEncode("CERTIFICATE", cert.encoded)
            certPem.lines().forEach { sb.appendLine("                    $it") }
            sb.appendLine("                </Certificate>")
        }

        sb.appendLine("            </CertificateChain>")
        sb.appendLine("        </Key>")
        sb.appendLine("    </Keybox>")
        sb.appendLine("</AndroidAttestation>")
        return sb.toString()
    }

    // ---- Utilities ----

    private fun ecPrivKeyToSec1(key: ECPrivateKey): ByteArray {
        val s = key.s.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else ByteArray(32 - it.size) + it }
        val out = ByteArrayOutputStream()
        val inner = ByteArrayOutputStream()
        inner.write(byteArrayOf(0x02, 0x01, 0x01))
        inner.write(byteArrayOf(0x04, 0x20))
        inner.write(s)
        inner.write(byteArrayOf(0xA0.toByte(), 0x0A, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07))
        val innerBytes = inner.toByteArray()
        out.write(0x30)
        if (innerBytes.size < 128) {
            out.write(innerBytes.size)
        } else {
            out.write(0x81)
            out.write(innerBytes.size)
        }
        out.write(innerBytes)
        return out.toByteArray()
    }

    private fun pemEncode(type: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----"
    }

    private fun derToRawEcdsaSig(der: ByteArray): ByteArray {
        var pos = 2
        if (der[0] != 0x30.toByte()) throw IllegalArgumentException("Not a DER signature")
        pos++
        val rLen = der[pos++].toInt() and 0xFF
        val rBytes = der.copyOfRange(pos, pos + rLen)
        pos += rLen
        pos++
        val sLen = der[pos++].toInt() and 0xFF
        val sBytes = der.copyOfRange(pos, pos + sLen)
        return rBytes.takeLast(32).toByteArray().padStart32() +
               sBytes.takeLast(32).toByteArray().padStart32()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.padStart32(): ByteArray {
        if (size >= 32) return copyOfRange(size - 32, size)
        return ByteArray(32 - size) + this
    }
}

data class DeviceRkpConfig(
    val brand: String,
    val manufacturer: String,
    val product: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val osVersion: String = Build.VERSION.RELEASE,
    val vbState: String = "green",
    val bootloaderState: String = "locked",
    val fused: Int = 1,
    val systemPatchLevel: Long = 202601,
    val bootPatchLevel: Long = 20260101,
    val vendorPatchLevel: Long = 20260101,
    val vbmetaDigest: String = "",
    val diceIssuer: String = "Android",
    val diceSubject: String = "KeyMint",
)

data class KeyboxResult(
    val keyboxXml: String,
    val certCount: Int,
    val leafSubject: String,
    val leafNotAfter: String,
)

open class RkpException(message: String, cause: Throwable? = null) : Exception(message, cause)
class DeviceNotRegisteredError(message: String) : RkpException(message)
