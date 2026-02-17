# IdentityReceiver.smali
# BroadcastReceiver injected into cloned apps.
# Receives NEW_IDENTITY broadcasts from the main AppCloner app,
# updates the HookConfig with the new identity JSON, and optionally
# clears cache or app data.

.class public Lcom/foss/hook/IdentityReceiver;
.super Landroid/content/BroadcastReceiver;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroid/content/BroadcastReceiver;-><init>()V
    return-void
.end method

.method public onReceive(Landroid/content/Context;Landroid/content/Intent;)V
    .registers 6
    .param p1, "context"
    .param p2, "intent"

    if-eqz p2, :done

    # Check action
    invoke-virtual {p2}, Landroid/content/Intent;->getAction()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :done

    const-string v1, "com.foss.appcloner.action.NEW_IDENTITY"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :done

    # Read identity_json extra
    const-string v1, "identity_json"
    invoke-virtual {p2, v1}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :done

    # Update HookConfig
    sput-object v0, Lcom/foss/hook/HookConfig;->identity_json:Ljava/lang/String;
    invoke-static {}, Lcom/foss/hook/HookConfig;->parseJson()V

    # Apply build props immediately
    invoke-static {}, Lcom/foss/hook/Hooks;->applyBuildProps()V

    # Check clear_cache flag
    const-string v1, "clear_cache"
    const/4 v2, 0x0
    invoke-virtual {p2, v1, v2}, Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z
    move-result v1
    if-eqz v1, :check_data

    invoke-virtual {p1}, Landroid/content/Context;->getCacheDir()Ljava/io/File;
    move-result-object v1
    invoke-static {v1}, Lcom/foss/hook/IdentityReceiver;->deleteDir(Ljava/io/File;)V

    :check_data
    # Check delete_app_data flag
    const-string v1, "delete_app_data"
    const/4 v2, 0x0
    invoke-virtual {p2, v1, v2}, Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z
    move-result v1
    if-eqz v1, :done

    invoke-virtual {p1}, Landroid/content/Context;->getFilesDir()Ljava/io/File;
    move-result-object v1
    invoke-virtual {v1}, Ljava/io/File;->getParentFile()Ljava/io/File;
    move-result-object v1
    invoke-static {v1}, Lcom/foss/hook/IdentityReceiver;->deleteDir(Ljava/io/File;)V

    :done
    return-void
.end method

# Recursively delete a directory (used for cache/data clearing)
.method public static deleteDir(Ljava/io/File;)V
    .registers 5
    .param p0, "dir"

    if-eqz p0, :done

    invoke-virtual {p0}, Ljava/io/File;->isDirectory()Z
    move-result v0
    if-eqz v0, :delete_file

    invoke-virtual {p0}, Ljava/io/File;->listFiles()[Ljava/io/File;
    move-result-object v0
    if-eqz v0, :delete_file
    array-length v1, v0
    const/4 v2, 0x0

    :loop
    if-ge v2, v1, :delete_file
    aget-object v3, v0, v2
    invoke-static {v3}, Lcom/foss/hook/IdentityReceiver;->deleteDir(Ljava/io/File;)V
    add-int/lit8 v2, v2, 0x1
    goto :loop

    :delete_file
    invoke-virtual {p0}, Ljava/io/File;->delete()Z

    :done
    return-void
.end method
