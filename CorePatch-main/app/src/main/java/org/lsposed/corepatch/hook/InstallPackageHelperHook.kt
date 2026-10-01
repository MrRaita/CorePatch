package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.hostClassLoader

object InstallPackageHelperHook : BaseHook() {
    override val name = "InstallPackageHelperHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val installPackageHelperClazz =
            hostClassLoader.loadClass("com.android.server.pm.InstallPackageHelper")
        val doesSignatureMatchForPermissionsMethod =
            installPackageHelperClazz.declaredMethods.first { m -> m.name == "doesSignatureMatchForPermissions" }
        hookAfter(doesSignatureMatchForPermissionsMethod) { callback ->
            if (callback.result != false) return@hookAfter

            // args[0] = sourcePackageName: the package that already owns the permission.
            // args[1] = the AndroidPackage currently being installed/scanned.
            val getPackageNameMethod =
                callback.args[1]!!.javaClass.declaredMethods.first { m -> m.name == "getPackageName" }
            val installingPackageName = getPackageNameMethod.invoke(callback.args[1]) as String
            val permissionOwnerPackageName = callback.args[0] as String

            if (Config.isBypassDigestEnabled() && Config.isUsePreviousSignaturesEnabled()) {
                // If we decide to crack this then at least make sure they are same apks, avoid another one that tries to impersonate.
                if (installingPackageName == permissionOwnerPackageName) {
                    callback.result = true
                    return@hookAfter
                }
            }

            if (Config.isBypassDuplicatePermissionEnabled()) {
                // Different package name (e.g. a cloned/re-signed copy of an app) redefining a
                // custom permission already declared by another app. Unlike the check above,
                // this intentionally does NOT require the package names to match, since the
                // whole point is to let a clone with a different signature/package id reuse a
                // permission name owned by the original app. This is a broad bypass: enabling
                // it allows ANY installing package to redefine ANY already-owned custom
                // permission, so it is kept behind its own explicit, separately-labelled toggle.
                callback.result = true
            }
        }
    }
}
