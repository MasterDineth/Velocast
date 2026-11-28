package com.mastersoft.velocast.launcher.adb

import android.annotation.SuppressLint
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

/**
 * Builds per-identity Conscrypt `TLSv1.3` [SSLContext] instances and exports RFC 5705 keying
 * material (`"adb-label\u0000"`) without static singleton key caching or hidden-API reflection.
 */
internal object AdbTlsSupport {
    const val TLS_PROTOCOL = "TLSv1.3"
    const val ADB_EXPORT_LABEL = "adb-label\u0000"
    const val ADB_EXPORT_KEY_BYTES = 64

    private val conscryptProvider: Provider by lazy { Conscrypt.newProvider() }

    /**
     * Creates a `TLSv1.3` [SSLContext] presenting [identity]'s RSA-2048 private key and self-signed
     * X.509 certificate.
     *
     * Note on peer certificate verification: In Android's Wireless Debugging protocol, both `adbd`
     * and the viewer present ephemeral/self-signed X.509 certificates without a CA hierarchy.
     * During `_adb-tls-pairing._tcp`, MITM attacks are defeated by binding the 64-byte RFC 5705 TLS
     * exporter output into the SPAKE2 password. During `_adb-tls-connect._tcp` (`STLS`), `adbd`
     * authenticates the client's RSA public key against `/data/misc/adb/adb_keys`, and optional
     * [peerTrustValidator] lets test servers or pinned callers enforce peer key checks.
     */
    fun createSslContext(
        identity: AdbKeyIdentity,
        random: SecureRandom = SecureRandom(),
        peerTrustValidator: ((Array<out X509Certificate>?) -> Boolean)? = null
    ): SSLContext {
        val context = SSLContext.getInstance(TLS_PROTOCOL, conscryptProvider)
        context.init(
            arrayOf<KeyManager>(IdentityKeyManager(identity)),
            arrayOf(AdbPeerTrustManager(peerTrustValidator)),
            random
        )
        return context
    }

    fun upgradeToClientTlsSocket(
        plainSocket: Socket,
        host: String,
        port: Int,
        identity: AdbKeyIdentity
    ): SSLSocket {
        val sslContext = createSslContext(identity)
        val sslSocket = sslContext.socketFactory
            .createSocket(plainSocket, host, port, true) as SSLSocket
        sslSocket.enabledProtocols = arrayOf(TLS_PROTOCOL)
        sslSocket.useClientMode = true
        sslSocket.startHandshake()
        return sslSocket
    }

    fun exportKeyingMaterial(
        sslSocket: SSLSocket,
        label: String = ADB_EXPORT_LABEL,
        length: Int = ADB_EXPORT_KEY_BYTES
    ): ByteArray = Conscrypt.exportKeyingMaterial(sslSocket, label, null, length)
        ?: throw SSLException("Conscrypt returned null keying material for ADB TLS session")

    private class IdentityKeyManager(private val identity: AdbKeyIdentity) :
        X509ExtendedKeyManager() {
        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out Principal>?
        ): Array<String>? = if (keyType == RSA_KEY_TYPE) arrayOf(identity.alias) else null

        override fun chooseClientAlias(
            keyTypes: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = if (keyTypes?.any { it.contains(RSA_KEY_TYPE) } == true) {
            identity.alias
        } else {
            null
        }

        override fun chooseEngineClientAlias(
            keyTypes: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String? = chooseClientAlias(keyTypes, issuers, null)

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out Principal>?
        ): Array<String>? = if (keyType == RSA_KEY_TYPE) arrayOf(identity.alias) else null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = if (keyType?.contains(RSA_KEY_TYPE) == true) identity.alias else null

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String? = chooseServerAlias(keyType, issuers, null)

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == identity.alias) arrayOf(identity.certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == identity.alias) identity.privateKey else null

        private companion object {
            const val RSA_KEY_TYPE = "RSA"
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class AdbPeerTrustManager(
        private val validator: ((Array<out X509Certificate>?) -> Boolean)?
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (validator != null && !validator.invoke(chain)) {
                throw CertificateException("ADB client certificate rejected")
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (validator != null && !validator.invoke(chain)) {
                throw CertificateException("ADB server certificate rejected")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
