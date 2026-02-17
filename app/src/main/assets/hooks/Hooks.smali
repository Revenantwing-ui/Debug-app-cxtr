# Hooks.smali
# Central class containing all static hook methods.
# DexPatcher rewrites invoke-virtual calls to system APIs into invoke-static calls here.

.class public final Lcom/foss/hook/Hooks;
.super Ljava/lang/Object;

# ── Settings.Secure.getString interceptor ────────────────────────────────────────
# Intercepts: Settings.Secure.getString(ContentResolver, String) → String
# If key == "android_id", return our fake ID.
# If key == "gsf_id" (Google Service Framework), return our fake GSF ID.
.method public static spoofSettingSecure(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;
    .registers 4

    const-string v0, "android_id"
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :check_gsf

    sget-object v0, Lcom/foss/hook/HookConfig;->android_id:Ljava/lang/String;
    if-eqz v0, :original
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :original
    return-object v0

    :check_gsf
    const-string v0, "android_gsf_id"
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :original
    sget-object v0, Lcom/foss/hook/HookConfig;->gsf_id:Ljava/lang/String;
    if-eqz v0, :original
    return-object v0

    :original
    invoke-static {p0, p1}, Landroid/provider/Settings$Secure;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# ── TelephonyManager.getDeviceId / getImei interceptor ───────────────────────────
.method public static spoofImei(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
    .registers 2

    sget-object v0, Lcom/foss/hook/HookConfig;->imei:Ljava/lang/String;
    if-eqz v0, :original
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :original
    return-object v0

    :original
    invoke-virtual {p0}, Landroid/telephony/TelephonyManager;->getDeviceId()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# ── TelephonyManager.getSubscriberId (IMSI) interceptor ─────────────────────────
.method public static spoofImsi(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
    .registers 2

    sget-object v0, Lcom/foss/hook/HookConfig;->imsi:Ljava/lang/String;
    if-eqz v0, :original
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :original
    return-object v0

    :original
    invoke-virtual {p0}, Landroid/telephony/TelephonyManager;->getSubscriberId()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# ── WifiInfo.getMacAddress interceptor ───────────────────────────────────────────
.method public static spoofWifiMac(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;
    .registers 2

    sget-object v0, Lcom/foss/hook/HookConfig;->wifi_mac:Ljava/lang/String;
    if-eqz v0, :original
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :original
    return-object v0

    :original
    invoke-virtual {p0}, Landroid/net/wifi/WifiInfo;->getMacAddress()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# ── WifiInfo.getSSID interceptor (hide SSID) ─────────────────────────────────────
.method public static spoofSsid(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;
    .registers 2

    sget-boolean v0, Lcom/foss/hook/HookConfig;->hide_wifi_info:Z
    if-eqz v0, :original

    const-string v0, "<hidden>"
    return-object v0

    :original
    invoke-virtual {p0}, Landroid/net/wifi/WifiInfo;->getSSID()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# ── WebSettings.getUserAgentString interceptor ───────────────────────────────────
.method public static spoofUserAgent(Landroid/webkit/WebSettings;)Ljava/lang/String;
    .registers 2

    sget-object v0, Lcom/foss/hook/HookConfig;->webview_ua:Ljava/lang/String;
    if-eqz v0, :original
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :original
    return-object v0

    :original
    invoke-virtual {p0}, Landroid/webkit/WebSettings;->getUserAgentString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# ── android.os.Build field spoofing via static methods ───────────────────────────
# These are called by the Application's onCreate to overwrite Build fields via reflection.
.method public static applyBuildProps()V
    .registers 5

    # Spoof Build.MANUFACTURER
    sget-object v0, Lcom/foss/hook/HookConfig;->manufacturer:Ljava/lang/String;
    if-eqz v0, :skip_mfr
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :skip_mfr
    const-string v1, "android.os.Build"
    const-string v2, "MANUFACTURER"
    invoke-static {v1, v2, v0}, Lcom/foss/hook/Hooks;->setStaticField(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
    :skip_mfr

    # Spoof Build.MODEL
    sget-object v0, Lcom/foss/hook/HookConfig;->model:Ljava/lang/String;
    if-eqz v0, :skip_model
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :skip_model
    const-string v1, "android.os.Build"
    const-string v2, "MODEL"
    invoke-static {v1, v2, v0}, Lcom/foss/hook/Hooks;->setStaticField(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
    :skip_model

    # Spoof Build.FINGERPRINT
    sget-object v0, Lcom/foss/hook/HookConfig;->fingerprint:Ljava/lang/String;
    if-eqz v0, :skip_fp
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :skip_fp
    const-string v1, "android.os.Build"
    const-string v2, "FINGERPRINT"
    invoke-static {v1, v2, v0}, Lcom/foss/hook/Hooks;->setStaticField(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
    :skip_fp

    # Spoof Build.BRAND
    sget-object v0, Lcom/foss/hook/HookConfig;->brand:Ljava/lang/String;
    if-eqz v0, :skip_brand
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :skip_brand
    const-string v1, "android.os.Build"
    const-string v2, "BRAND"
    invoke-static {v1, v2, v0}, Lcom/foss/hook/Hooks;->setStaticField(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
    :skip_brand

    return-void
.end method

# Reflection helper: sets a static field value by class name and field name
.method public static setStaticField(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
    .registers 5
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    :try_start
    invoke-static {p0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v0
    invoke-virtual {v0, p1}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v1
    const/4 v2, 0x1
    invoke-virtual {v1, v2}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    const/4 v2, 0x0
    invoke-virtual {v1, v2, p2}, Ljava/lang/reflect/Field;->set(Ljava/lang/Object;Ljava/lang/Object;)V
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch_all
    :catch_all

    return-void
.end method
