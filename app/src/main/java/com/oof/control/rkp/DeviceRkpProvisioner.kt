package com.oof.control.rkp

import android.content.Context
import android.util.Log
import com.oof.control.rkp.CborUtils.decode
import com.oof.control.rkp.CborUtils.encodeArray
import com.oof.control.rkp.CborUtils.encodeBytes
import com.oof.control.rkp.CborUtils.encodeInt
import com.oof.control.rkp.CborUtils.encodeMap
import com.oof.control.rkp.CborUtils.encodeText
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Device RKP Issuer — uses the Qualcomm KeyMint TA to sign CSRs
 * with the device's real DICE chain.
 *
 * The attestation key is generated in software; only the CSR signature
 * comes from the TEE. This matches Duck-ToolBox's Device RKP V2 flow.
 */
class DeviceRkpProvisioner(
    private val context: Context,
    private val fingerprint: String,
) {
    companion object {
        private const val TAG = "DeviceRkp"
        private const val RKP_SERVER = "https://remoteprovisioning.googleapis.com/v1"
        private const val ALG_ES256 = -7L
        private const val TA_CSR_COMMAND = 0x3122
        private const val TA_BINARY = "ta_csr"
    }

    fun provision(): KeyboxResult {
        Log.i(TAG, "Starting Device RKP provisioning")

        val ecKp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val ecPub = ecKp.public as ECPublicKey
        val ecCoseKey = buildCoseKey(ecPub)

        Log.i(TAG, "Fetching challenge from server...")
        val challenge = fetchChallenge()

        Log.i(TAG, "Sending CSR_V2 request to TA...")
        val taRequest = buildTaRequest(challenge, ecCoseKey)
        val csrPayload = callTa(taRequest)
        val csrBytes = unwrapCsr(csrPayload)

        val finalCsr = if (fingerprint.isNotEmpty()) {
            appendFingerprint(csrBytes)
        } else {
            csrBytes
        }

        Log.i(TAG, "Submitting CSR to Google...")
        val certChainsDer = submitCsr(finalCsr, challenge)
        if (certChainsDer.isEmpty()) throw RkpException("No certificate chains from server")

        val certs = parseDerCertChain(certChainsDer[0])
        Log.i(TAG, "Received ${certs.size} certificates")

        ensureChainMatchesKey(certs, ecPub)
        val sortedCerts = sortCertChain(certs)
        ensureChainIsLinked(certs, sortedCerts)
        val xml = buildKeyboxXml(ecKp.private as ECPrivateKey, ecPub, sortedCerts)

        return KeyboxResult(
            keyboxXml = xml,
            certCount = certs.size,
            leafSubject = certs.firstOrNull()?.subjectX500Principal?.name ?: "unknown",
            leafNotAfter = sortedCerts.firstOrNull()?.notAfter?.toString() ?: "unknown",
        )
    }

    // ---- TA communication ----

    private fun buildCoseKey(pub: ECPublicKey): ByteArray {
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

    private fun buildTaRequest(challenge: ByteArray, coseKey: ByteArray): ByteArray {
        val keysToSign = encodeArray(listOf(coseKey))
        val payload = encodeMap(listOf(
            encodeInt(4) to encodeBytes(challenge),
            encodeInt(57) to encodeBytes(keysToSign),
        ))
        val buf = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(TA_CSR_COMMAND)
        buf.put(payload)
        return buf.array()
    }

    private fun callTa(request: ByteArray): ByteArray {
        val binary = extractTaBinary()
        val requestHex = request.joinToString("") { "%02x".format(it) }
        val ldPath = "LD_LIBRARY_PATH=/system/lib64:/vendor/lib64:/odm/lib64:/vendor/lib64/hw:/odm/lib64/hw"
        val cmd = "${binary.absolutePath} $requestHex"

        // Try direct execution first (works when SELinux is permissive)
        var process = Runtime.getRuntime().exec(arrayOf(
            "/system/bin/sh", "-c", "$ldPath $cmd"
        ))
        var stdout = process.inputStream.bufferedReader().readText().trim()
        var stderr = process.errorStream.bufferedReader().readText().trim()
        var exitCode = process.waitFor()

        if (exitCode != 0 || stdout.isEmpty()) {
            Log.i(TAG, "Direct execution failed (exit=$exitCode), trying with su...")
            val suPaths = listOf("/system/bin/su", "/sbin/su", "/su/bin/su")
            val suBin = suPaths.firstOrNull { File(it).exists() }
            if (suBin != null) {
                process = Runtime.getRuntime().exec(arrayOf(suBin, "-c", "$ldPath $cmd"))
                stdout = process.inputStream.bufferedReader().readText().trim()
                stderr = process.errorStream.bufferedReader().readText().trim()
                exitCode = process.waitFor()
            }
        }

        if (exitCode != 0 || stdout.isEmpty()) {
            val errMsg = stderr.ifEmpty { "TA helper exited with code $exitCode" }
            if (stderr.contains("status: -18") || stderr.contains("TA status: -18")) {
                throw RkpException("TA rejected the CSR request (status -18). The firmware may not support software-key CSR_V2.")
            }
            throw RkpException("TA communication failed: $errMsg")
        }

        return hexToBytes(stdout)
    }

    private fun extractTaBinary(): File {
        val binary = File(context.filesDir, TA_BINARY)
        if (!binary.exists() || binary.length() == 0L) {
            context.assets.open(TA_BINARY).use { input ->
                binary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            binary.setExecutable(true)
        }
        return binary
    }

    private fun unwrapCsr(payload: ByteArray): ByteArray {
        val decoded = decode(payload)
        if (decoded is List<*>) return payload

        val map = decoded as? Map<*, *>
            ?: throw RkpException("Unexpected CSR response format")
        val csrBytes = map[61L] as? ByteArray
            ?: throw RkpException("CSR response missing field 61")
        if (csrBytes.isEmpty()) throw RkpException("TA returned empty CSR")
        return csrBytes
    }

    private fun appendFingerprint(csr: ByteArray): ByteArray {
        val decoded = decode(csr) as? List<*>
            ?: throw RkpException("CSR is not a CBOR array")
        if (decoded.size != 4) throw RkpException("Expected 4-element AuthenticatedRequest")

        val reader = CborUtils.CborReader(csr)
        val initial = csr[0].toInt() and 0xFF
        if ((initial shr 5) != 4) throw RkpException("CSR is not a CBOR array")

        val fpMap = encodeMap(listOf(
            encodeText("fingerprint") to encodeText(fingerprint)
        ))
        val out = ByteArrayOutputStream()
        out.write(0x85) // 5-element array
        out.write(csr, 1, csr.size - 1) // skip old array header
        out.write(fpMap)
        return out.toByteArray()
    }

    // ---- Server communication (same as RkpProvisioner) ----

    private fun fetchChallenge(): ByteArray {
        val url = URL("$RKP_SERVER:fetchEekChain")
        val body = encodeMap(listOf(
            encodeText("fingerprint") to encodeText(fingerprint),
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

        return result[1] as? ByteArray
            ?: throw RkpException("No challenge in fetchEekChain response")
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
                throw DeviceNotRegisteredError("Device not recognized by server: $err")
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

    // ---- Certificate parsing and keybox (shared logic) ----

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
                val cert = cf.generateCertificate(java.io.ByteArrayInputStream(data, pos, certLen)) as X509Certificate
                certs.add(cert)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cert at offset $pos", e)
                break
            }
            pos += certLen
        }
        return certs
    }

    private fun sortCertChain(certs: List<X509Certificate>): List<X509Certificate> {
        if (certs.size <= 1) return certs
        val bySubject = certs.associateBy { it.subjectX500Principal }
        val issuers = certs.map { it.issuerX500Principal }.toSet()
        val leaf = certs.firstOrNull { it.subjectX500Principal !in issuers } ?: certs.first()

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
        return sorted
    }

    private fun ensureChainMatchesKey(certs: List<X509Certificate>, generatedPub: ECPublicKey) {
        val issuers = certs.map { it.issuerX500Principal }.toSet()
        val leaf = certs.firstOrNull { it.subjectX500Principal !in issuers } ?: certs.first()
        val leafPub = leaf.publicKey as? ECPublicKey
            ?: throw RkpException("Leaf certificate does not contain an EC public key")
        if (leafPub.w.affineX != generatedPub.w.affineX || leafPub.w.affineY != generatedPub.w.affineY) {
            throw RkpException("Leaf certificate public key does not match the generated key — the keybox would be invalid")
        }
        Log.i(TAG, "Leaf certificate public key matches generated key")
    }

    private fun ensureChainIsLinked(original: List<X509Certificate>, sorted: List<X509Certificate>) {
        if (sorted.size != original.size) {
            throw RkpException("Certificate chain contains ${original.size - sorted.size} certificate(s) outside the leaf-to-root path")
        }
        for (i in 0 until sorted.size - 1) {
            if (sorted[i].issuerX500Principal != sorted[i + 1].subjectX500Principal) {
                throw RkpException("Certificate chain is broken: ${sorted[i].subjectX500Principal} is not issued by ${sorted[i + 1].subjectX500Principal}")
            }
        }
        Log.i(TAG, "Certificate chain is linked (${sorted.size} certs, leaf to root)")
    }

    private fun buildKeyboxXml(ecPrivKey: ECPrivateKey, ecPubKey: ECPublicKey, certChain: List<X509Certificate>): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\"?>")
        sb.appendLine("<AndroidAttestation>")
        sb.appendLine("    <NumberOfKeyboxes>1</NumberOfKeyboxes>")
        sb.appendLine("    <Keybox DeviceID=\"device-${randomHex(6)}\">")
        sb.appendLine("        <Key algorithm=\"ecdsa\">")
        sb.appendLine("            <PrivateKey format=\"pem\">")

        val keyPem = pemEncode("EC PRIVATE KEY", ecPrivKeyToSec1(ecPrivKey, ecPubKey))
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

    private fun ecPrivKeyToSec1(key: ECPrivateKey, pub: ECPublicKey): ByteArray {
        val s = key.s.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else ByteArray(32 - it.size) + it }
        val x = pub.w.affineX.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else ByteArray(32 - it.size) + it }
        val y = pub.w.affineY.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else ByteArray(32 - it.size) + it }
        val uncompressedPoint = byteArrayOf(0x04) + x + y // 65 bytes

        val inner = ByteArrayOutputStream()
        inner.write(byteArrayOf(0x02, 0x01, 0x01)) // version 1
        inner.write(byteArrayOf(0x04, 0x20)) // OCTET STRING, 32 bytes
        inner.write(s)
        // [0] namedCurve OID secp256r1
        inner.write(byteArrayOf(0xA0.toByte(), 0x0A, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07))
        // [1] publicKey BIT STRING (65-byte uncompressed point)
        val pubBitString = byteArrayOf(0x00) + uncompressedPoint // 0x00 = no unused bits
        inner.write(0xA1.toByte().toInt()) // context [1]
        writeDerLength(inner, pubBitString.size + 2)
        inner.write(0x03) // BIT STRING tag
        writeDerLength(inner, pubBitString.size)
        inner.write(pubBitString)

        val innerBytes = inner.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x30) // SEQUENCE
        writeDerLength(out, innerBytes.size)
        out.write(innerBytes)
        return out.toByteArray()
    }

    private fun writeDerLength(out: ByteArrayOutputStream, len: Int) {
        if (len < 128) {
            out.write(len)
        } else if (len < 256) {
            out.write(0x81)
            out.write(len)
        } else {
            out.write(0x82)
            out.write(len shr 8)
            out.write(len and 0xFF)
        }
    }

    private fun pemEncode(type: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
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
