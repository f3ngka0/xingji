package com.tripshare.app.data.remote

import android.content.Context
import com.tripshare.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.InetAddress

/** Strictly validates a server origin and returns its canonical root URL. */
object ServerAddressValidator {
    private val schemePattern = Regex("^(https?)://", RegexOption.IGNORE_CASE)
    private val numericHostPattern = Regex("^[0-9.]+$")
    private val domainLabelPattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun normalize(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "请输入服务器地址" }
        require(value.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
            "服务器地址不能包含空格或反斜杠"
        }

        val schemeMatch = schemePattern.find(value)
        val suppliedScheme = schemeMatch?.groupValues?.get(1)?.lowercase()
        val authorityInput = if (schemeMatch == null) value else value.substring(schemeMatch.range.last + 1)
        require(authorityInput.isNotEmpty() && !authorityInput.contains('@') &&
            !authorityInput.contains('?') && !authorityInput.contains('#')) {
            "请输入域名或 IP 地址，不能包含账号、查询参数或片段"
        }

        val authority = authorityInput.removeSuffix("/")
        require(authority.isNotEmpty() && !authority.contains('/')) {
            "服务器地址只能填写域名或 IP 与端口，不能包含路径"
        }
        val (rawHost, rawPort) = parseAuthority(authority)
        val hostKind = validateHost(rawHost)
        val localHost = isLocalHost(rawHost, hostKind)
        val scheme = suppliedScheme ?: if (localHost) "http" else "https"

        require(scheme == "https" || localHost) {
            "公网服务器必须使用 HTTPS；HTTP 仅支持内网 IP、localhost 或模拟器地址"
        }
        val candidate = "$scheme://$authority/".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务器地址或端口无效")
        require(candidate.username.isEmpty() && candidate.password.isEmpty() &&
            candidate.encodedPath == "/" && candidate.encodedQuery == null && candidate.encodedFragment == null) {
            "服务器地址只能填写域名或 IP 与端口，不能包含路径、账号或参数"
        }
        if (rawPort != null) require(candidate.port in 1..65_535) { "端口必须在 1 到 65535 之间" }

        return candidate.toString()
    }

    fun isSameOrigin(first: String, second: String): Boolean {
        val a = first.toHttpUrlOrNull() ?: return false
        val b = second.toHttpUrlOrNull() ?: return false
        return a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }

    fun allowsCleartextHost(host: String): Boolean {
        val authority = if (host.contains(':')) "[$host]" else host
        return runCatching { normalize("http://$authority") }.isSuccess
    }

    private fun parseAuthority(authority: String): Pair<String, String?> {
        require(!authority.startsWith("@") && !authority.endsWith("@")) { "服务器地址格式无效" }
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            require(end > 1) { "IPv6 地址格式无效，请使用方括号" }
            val suffix = authority.substring(end + 1)
            val port = when {
                suffix.isEmpty() -> null
                suffix.startsWith(":") && suffix.length > 1 -> suffix.substring(1)
                else -> throw IllegalArgumentException("端口格式无效")
            }
            if (port != null) require(port.all { it in '0'..'9' }) { "端口格式无效" }
            return authority.substring(0, end + 1) to port
        }

        require(authority.count { it == ':' } <= 1) { "IPv6 地址请使用方括号，例如 [fd00::1]:8080" }
        val separator = authority.lastIndexOf(':')
        if (separator < 0) return authority to null
        val port = authority.substring(separator + 1)
        require(port.isNotEmpty() && port.all { it in '0'..'9' }) { "端口格式无效" }
        return authority.substring(0, separator) to port
    }

    private fun validateHost(rawHost: String): HostKind {
        require(rawHost.isNotBlank() && !rawHost.contains('%')) { "域名或 IP 地址无效" }
        if (rawHost.startsWith("[")) {
            require(rawHost.endsWith("]")) { "IPv6 地址格式无效" }
            val literal = rawHost.substring(1, rawHost.length - 1)
            require(literal.contains(':') && literal.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
                "IPv6 地址无效"
            }
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull()
                ?: throw IllegalArgumentException("IPv6 地址无效")
            require(address.address.size == 16) { "IPv6 地址无效" }
            return HostKind.Ipv6(address.address)
        }

        if (numericHostPattern.matches(rawHost)) {
            val parts = rawHost.split('.')
            require(parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    (part.length == 1 || part.first() != '0') && (part.toIntOrNull()?.let { it in 0..255 } == true)
            }) { "IPv4 地址无效，请使用四段式地址，例如 192.168.1.10" }
            return HostKind.Ipv4(parts.map(String::toInt).toIntArray())
        }

        val asciiHost = runCatching { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase() }
            .getOrElse { throw IllegalArgumentException("域名格式无效") }
        val domain = asciiHost.removeSuffix(".")
        if (domain == "localhost") return HostKind.Domain(domain)
        val labels = domain.split('.')
        require(domain.isNotEmpty() && domain.length <= 253 && labels.size >= 2 &&
            !numericHostPattern.matches(domain) && labels.all { it.length <= 63 && domainLabelPattern.matches(it) }) { "域名格式无效" }
        require(!domain.endsWith(".example")) { "请设置真实可访问的服务器域名" }
        return HostKind.Domain(domain)
    }

    private fun isLocalHost(rawHost: String, kind: HostKind): Boolean = when (kind) {
        is HostKind.Domain -> kind.value == "localhost"
        is HostKind.Ipv4 -> {
            val (a, b) = kind.octets
            a == 10 || a == 127 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) || rawHost == "10.0.2.2"
        }
        is HostKind.Ipv6 -> {
            val bytes = kind.bytes
            val loopback = bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte()
            val uniqueLocal = (bytes[0].toInt() and 0xfe) == 0xfc
            val linkLocal = (bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80
            loopback || uniqueLocal || linkLocal
        }
    }

    private sealed interface HostKind {
        data class Domain(val value: String) : HostKind
        data class Ipv4(val octets: IntArray) : HostKind
        data class Ipv6(val bytes: ByteArray) : HostKind
    }
}

class ServerAddressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentBaseUrl(): String? {
        val stored = prefs.getString(KEY_ADDRESS, null)
        if (stored != null) return runCatching { ServerAddressValidator.normalize(stored) }.getOrNull()
        val legacy = BuildConfig.API_BASE_URL
        if (legacy.equals(DEFAULT_PLACEHOLDER, ignoreCase = true)) return null
        return runCatching { ServerAddressValidator.normalize(legacy) }.getOrNull()
    }

    fun save(baseUrl: String) {
        check(prefs.edit().putString(KEY_ADDRESS, baseUrl).commit()) { "服务器地址保存失败，请重试" }
    }

    private companion object {
        const val PREFS = "trip-share-server"
        const val KEY_ADDRESS = "base_url"
        const val DEFAULT_PLACEHOLDER = "https://your-domain.example/"
    }
}
