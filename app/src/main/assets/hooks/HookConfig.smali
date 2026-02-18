# HookConfig.smali
# Reads and caches identity values for the cloned app.
# The identity JSON is embedded at clone time via placeholder substitution,
# and can also be refreshed at runtime via IdentityReceiver.
.class public final Lcom/foss/hook/HookConfig;
.super Ljava/lang/Object;

# Static field holding the parsed identity JSON (set at clone-time or runtime)
.field public static identity_json:Ljava/lang/String;
# Individual cached values
.field public static android_id:Ljava/lang/String;
.field public static imei:Ljava/lang/String;
.field public static imsi:Ljava/lang/String;
.field public static wifi_mac:Ljava/lang/String;
.field public static bt_mac:Ljava/lang/String;
.field public static gsf_id:Ljava/lang/String;
.field public static manufacturer:Ljava/lang/String;
.field public static brand:Ljava/lang/String;
.field public static model:Ljava/lang/String;
.field public static product:Ljava/lang/String;
.field public static device:Ljava/lang/String;
.field public static board:Ljava/lang/String;
.field public static hardware:Ljava/lang/String;
.field public static bootloader:Ljava/lang/String;
.field public static fingerprint:Ljava/lang/String;
.field public static build_id:Ljava/lang/String;
.field public static webview_ua:Ljava/lang/String;
.field public static battery_level:I;
.field public static initialized:Z;
.field public static hide_wifi_info:Z;
.field public static hide_sim_info:Z;

.method static constructor <clinit>()V
    .registers 2

    const/4 v0, 0x0
    sput-boolean v0, Lcom/foss/hook/HookConfig;->initialized:Z

    # Embed identity JSON at clone time - this placeholder is replaced by HookInjector
    const-string v0, "%%IDENTITY_JSON%%"
    sput-object v0, Lcom/foss/hook/HookConfig;->identity_json:Ljava/lang/String;
    invoke-static {}, Lcom/foss/hook/HookConfig;->parseJson()V

    return-void
.end method

# Parse the JSON string and populate all cached fields.
# Uses a simple substring-search parser to avoid needing org.json or Gson at hook level.
.method public static parseJson()V
    .registers 4

    sget-object v0, Lcom/foss/hook/HookConfig;->identity_json:Ljava/lang/String;
    if-eqz v0, :done

    # Parse androidId
    const-string v1, "\"androidId\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->android_id:Ljava/lang/String;

    const-string v1, "\"imei\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->imei:Ljava/lang/String;

    const-string v1, "\"imsi\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->imsi:Ljava/lang/String;

    const-string v1, "\"wifiMacAddress\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->wifi_mac:Ljava/lang/String;

    const-string v1, "\"bluetoothMacAddress\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->bt_mac:Ljava/lang/String;

    const-string v1, "\"manufacturer\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->manufacturer:Ljava/lang/String;

    const-string v1, "\"brand\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->brand:Ljava/lang/String;

    const-string v1, "\"model\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->model:Ljava/lang/String;

    const-string v1, "\"device\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->device:Ljava/lang/String;

    const-string v1, "\"fingerprint\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->fingerprint:Ljava/lang/String;

    const-string v1, "\"webViewUserAgent\""
    invoke-static {v0, v1}, Lcom/foss/hook/HookConfig;->extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    sput-object v2, Lcom/foss/hook/HookConfig;->webview_ua:Ljava/lang/String;

    # Mark initialized
    const/4 v1, 0x1
    sput-boolean v1, Lcom/foss/hook/HookConfig;->initialized:Z

    :done
    return-void
.end method

# Extract value for key from JSON string.
# Simple "key":"value" parser. <--- FIXED: Added hash to make this a comment
.method public static extractValue(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .registers 6
    .param p0, "json"
    .param p1, "key"

    invoke-virtual {p0, p1}, Ljava/lang/String;->indexOf(Ljava/lang/String;)I
    move-result v0
    if-ltz v0, :not_found

    # Move past key and ":"
    invoke-virtual {p1}, Ljava/lang/String;->length()I
    move-result v1
    add-int/2addr v0, v1
    # Find opening quote
    const/16 v2, 0x22   # '"'
    invoke-virtual {p0, v2, v0}, Ljava/lang/String;->indexOf(II)I
    move-result v0
    if-ltz v0, :not_found
    add-int/lit8 v0, v0, 0x1
    # Find closing quote
    invoke-virtual {p0, v2, v0}, Ljava/lang/String;->indexOf(II)I
    move-result v1
    if-ltz v1, :not_found
    # Substring
    invoke-virtual {p0, v0, v1}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v0
    return-object v0

    :not_found
    const-string v0, ""
    return-object v0
.end method
