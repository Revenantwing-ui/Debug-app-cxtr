package com.foss.appcloner.identity

import com.foss.appcloner.model.Identity
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Generates cryptographically plausible random identity values.
 * All values are purely random and do not correspond to any real device.
 */
object IdentityGenerator {

    // Curated list of real Android device profiles for realistic build props
    private val DEVICE_PROFILES = listOf(
        DeviceProfile("samsung",  "Samsung",  "SM-G991B",    "galaxy_s21",    "s5e9825",   "y0s",    "Exynos"),
        DeviceProfile("samsung",  "Samsung",  "SM-S908B",    "galaxy_s22u",   "s5e9925",   "b0s",    "Exynos"),
        DeviceProfile("google",   "Google",   "Pixel 7",     "panther",       "panther",   "panther","Tensor G2"),
        DeviceProfile("google",   "Google",   "Pixel 8",     "shiba",         "shiba",     "shiba",  "Tensor G3"),
        DeviceProfile("OnePlus",  "OnePlus",  "CPH2551",     "salami",        "qualcomm",  "salami", "Snapdragon"),
        DeviceProfile("xiaomi",   "Xiaomi",   "2312MPCDEG",  "evergreen",     "kalama",    "evergreen","Snapdragon"),
        DeviceProfile("motorola", "motorola", "XT2301-4",    "omanchi_g",     "bengal",    "omanchi","Snapdragon"),
        DeviceProfile("Umax",     "Umax",     "8Qa_3G_EEA",  "8Qa_3G",        "mt6761",    "8Qa_3G", "MediaTek"),
        DeviceProfile("HUAWEI",   "HUAWEI",   "BLA-L09",     "HWBLA",         "kirin960",  "BLA",    "Kirin"),
        DeviceProfile("realme",   "realme",   "RMX3630",     "salaa",         "mt6877",    "salaa",  "MediaTek")
    )

    private val CHROME_VERSIONS = listOf("109", "110", "112", "114", "116", "120", "121", "122", "124")
    private val ANDROID_BUILD_VERSIONS = listOf("13", "14")

    fun generate(): Identity {
        val profile = DEVICE_PROFILES.random()
        val androidVersion = ANDROID_BUILD_VERSIONS.random()
        val chromeVersion = CHROME_VERSIONS.random()

        return Identity(
            androidId            = randomHex(16),
            serial               = randomSerial(),
            imei                 = generateImei(),
            imsi                 = generateImsi(),
            wifiMacAddress       = randomMac(),
            bluetoothMacAddress  = randomMac(),
            ethernetMacAddress   = randomMac(),
            googleGsfId          = randomHex(16),
            googleAdvertisingId  = UUID.randomUUID().toString(),
            facebookAttributionId= UUID.randomUUID().toString(),
            amazonAdvertisingId  = UUID.randomUUID().toString(),
            appSetId             = UUID.randomUUID().toString(),
            manufacturer         = profile.manufacturer,
            brand                = profile.brand,
            model                = profile.model,
            product              = profile.product,
            device               = profile.device,
            board                = profile.board,
            hardware             = profile.hardware,
            bootloader           = "bootloader-${randomHex(4)}",
            fingerprint          = buildFingerprint(profile, androidVersion),
            buildId              = buildId(),
            osVersion            = androidVersion,
            webViewUserAgent     = buildWebViewUserAgent(profile.model, androidVersion, chromeVersion),
            systemUserAgent      = "Dalvik/2.1.0 (Linux; U; Android $androidVersion; ${profile.model} Build/${buildId()})",
            batteryLevel         = Random.nextInt(15, 99),
            batteryTemperature   = (Random.nextFloat() * 15f + 20f),  // 20-35°C
            installTime          = randomPastTimestamp(),
            updateTime           = randomPastTimestamp(),
            userCreationTime     = randomPastTimestamp(),
            hideWifiInfo         = false,
            hideSimInfo          = false,
            hideDnsServers       = false,
            hideCpuInfo          = false
        )
    }

    /** Enable all privacy/hide flags on the generated identity */
    fun generateWithAllHidden(): Identity = generate().copy(
        hideWifiInfo   = true,
        hideSimInfo    = true,
        hideDnsServers = true,
        hideCpuInfo    = true
    )

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun randomSerial(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"
        return (1..10).map { chars.random() }.joinToString("")
    }

    /**
     * Generates a syntactically valid IMEI (15 digits, passes Luhn check).
     */
    fun generateImei(): String {
        // TAC (Type Allocation Code) – first 8 digits
        val tac = listOf(
            "35261311", "35978100", "35401030", "86866802", "35326510",
            "86038203", "35256015", "35611019", "01326400", "35978300"
        ).random()
        // 6 random serial digits
        val serial = (100000..999999).random().toString()
        val partial = tac + serial
        val check = luhnCheck(partial)
        return partial + check
    }

    fun generateImsi(): String {
        // MCC + MNC + subscriber number (15 digits total)
        val mccMnc = listOf("310260", "234010", "505003", "460001", "404010").random()
        val subscriber = (100000000..999999999).random().toString()
        return mccMnc + subscriber
    }

    private fun luhnCheck(number: String): Int {
        var sum = 0
        number.reversed().forEachIndexed { index, ch ->
            var digit = ch.digitToInt()
            if (index % 2 == 0) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return (10 - (sum % 10)) % 10
    }

    fun randomMac(): String {
        val bytes = ByteArray(6) { Random.nextInt(256).toByte() }
        // Clear multicast bit, set locally administered bit
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    private fun buildFingerprint(p: DeviceProfile, androidVer: String): String {
        val buildId = buildId()
        return "${p.brand}/${p.product}/${p.device}:$androidVer/$buildId/${randomHex(7)}:user/release-keys"
    }

    private fun buildId(): String {
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val prefix = (1..3).map { letters.random() }.joinToString("")
        val num = (1..9).map { Random.nextInt(10) }.joinToString("")
        return "$prefix.$num"
    }

    private fun buildWebViewUserAgent(model: String, androidVer: String, chromeVer: String): String {
        val bid = buildId()
        return "Mozilla/5.0 (Linux; Android $androidVer; $model Build/$bid; wv) " +
               "AppleWebKit/537.36 (KHTML, like Gecko) " +
               "Version/4.0 Chrome/$chromeVer.0.0.0 Mobile Safari/537.36"
    }

    private fun randomPastTimestamp(): Long {
        // Random timestamp within the last 2 years
        val twoYears = 2L * 365 * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - (Math.random() * twoYears).toLong()
    }

    data class DeviceProfile(
        val brand: String,
        val manufacturer: String,
        val model: String,
        val product: String,
        val board: String,
        val device: String,
        val hardware: String
    )
}
