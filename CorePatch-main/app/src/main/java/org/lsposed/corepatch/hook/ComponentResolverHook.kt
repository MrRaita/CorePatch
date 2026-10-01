package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.findClassIfExists
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.log

/**
 * Bypasses INSTALL_FAILED_CONFLICTING_PROVIDER, the <provider> authority counterpart of the
 * duplicate-permission check handled in [InstallPackageHelperHook]. A package that declares a
 * <provider android:authorities="..."> already owned by another installed package (e.g. a
 * cloned/re-signed copy of an app, or two build flavors of the same app) is rejected by the
 * platform during scanning, before [InstallPackageHelperHook]'s permission check is even
 * relevant.
 *
 * Unlike doesSignatureMatchForPermissions(), which returns a boolean we can flip,
 * ComponentResolver#assertProvidersNotDefined(AndroidPackage) is a void method that either
 * returns normally or throws a PackageManagerException(INSTALL_FAILED_CONFLICTING_PROVIDER, ...).
 * So instead of changing a result, we hook it, let it run, and when it throws for exactly that
 * reason (and the dedicated toggle is enabled) we swallow the exception via
 * AfterHookCallback#throwable so installation proceeds as if the authority were free.
 *
 * The exact class moved from com.android.server.pm.ComponentResolver (pre Android 12) to
 * com.android.server.pm.resolution.ComponentResolver in later AOSP refactors; both are probed.
 */
object ComponentResolverHook : BaseHook() {
    override val name = "ComponentResolverHook"

    // android.content.pm.PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER
    private const val INSTALL_FAILED_CONFLICTING_PROVIDER = -13

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val componentResolverClazz =
            findClassIfExists("com.android.server.pm.resolution.ComponentResolver")
                ?: findClassIfExists("com.android.server.pm.ComponentResolver")
                ?: run {
                    log("[$name] ComponentResolver class not found, skipping")
                    return
                }

        // void assertProvidersNotDefined(AndroidPackage pkg) — the locking wrapper that
        // validates every <provider> authority declared by the package being scanned.
        val assertProvidersNotDefinedMethod =
            componentResolverClazz.declaredMethods.firstOrNull { m ->
                m.name == "assertProvidersNotDefined" && m.parameterTypes.size == 1
            } ?: run {
                log("[$name] assertProvidersNotDefined method not found, skipping")
                return
            }

        hookAfter(assertProvidersNotDefinedMethod) { callback ->
            if (!Config.isBypassDuplicateProviderEnabled()) return@hookAfter

            val thrown = callback.throwable ?: return@hookAfter
            if (isConflictingProviderException(thrown)) {
                // Suppress: let the install proceed despite the authority clash.
                callback.throwable = null
            }
        }
    }

    private fun isConflictingProviderException(t: Throwable): Boolean {
        // Avoid hardcoding the exception's package; different AOSP versions keep
        // PackageManagerException in slightly different places.
        if (t.javaClass.simpleName != "PackageManagerException") return false

        val errorCode: Int? = try {
            val getError = t.javaClass.methods.firstOrNull { m ->
                m.name == "getError" && m.parameterTypes.isEmpty()
            }
            if (getError != null) {
                getError.isAccessible = true
                getError.invoke(t) as? Int
            } else {
                val errorField = t.javaClass.declaredFields.firstOrNull { it.name == "error" }
                errorField?.let {
                    it.isAccessible = true
                    it.get(t) as? Int
                }
            }
        } catch (_: Throwable) {
            null
        }

        if (errorCode != null) {
            return errorCode == INSTALL_FAILED_CONFLICTING_PROVIDER
        }

        // Fall back to matching the well-known AOSP message format if we couldn't read the
        // error code field/method via reflection on this ROM:
        // "Can't install because provider name <authority> (in package <pkg>) is already used by <owner>"
        return t.message?.contains("provider name") == true
    }
}
