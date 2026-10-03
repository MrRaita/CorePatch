package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.findClassIfExists
import org.lsposed.corepatch.XposedHelper.getOriginInvoker
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.log
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

/**
 * Provider-side counterpart to the signature-mismatch handling InstallPackageHelperHook already
 * does for permissions (USE_PREVIOUS_SIGNATURES). Its only job: when reinstalling/updating a
 * package whose signature no longer matches its previous install (typical for an unsigned or
 * debug build rebuilt on every deploy), the platform treats its own, already-declared <provider>
 * authorities as "owned by someone else" and throws INSTALL_FAILED_CONFLICTING_PROVIDER. This
 * hook recognizes that specific case -- the conflicting authority's current owner is this SAME
 * package, by name -- and suppresses it so the reinstall proceeds.
 *
 * This intentionally does NOT handle a genuinely different package (e.g. a cloned app with a
 * different applicationId) colliding with someone else's authority. That case is a real conflict
 * and is left to fail exactly as stock Android would; fix it by giving the clone unique
 * authorities in its own manifest instead.
 *
 * ComponentResolver#assertProvidersNotDefined(AndroidPackage) validates every <provider> authority
 * the package declares in one pass and throws on the FIRST conflict it finds, without checking the
 * rest. An app can easily have more than one provider that collides with its own previous install
 * (e.g. this one has eight), so a single suppress-and-stop would only ever clear the first and
 * leave every later one unregistered -- exactly the silent breakage this project spent a while
 * chasing down before. To handle all of them, we temporarily pull each self-owned conflicting
 * provider out of the package's own list (so the real check can get past it to whatever's next),
 * retry via the ORIGIN invoker, and -- once there's nothing left to resolve -- put every one of
 * them straight back before returning, so the normal registration step that runs after this check
 * still sees the complete, unmodified provider list.
 */
object ComponentResolverHook : BaseHook() {
    override val name = "ComponentResolverHook"

    // android.content.pm.PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER
    private const val INSTALL_FAILED_CONFLICTING_PROVIDER = -13
    private const val MAX_ATTEMPTS = 32

    private val authorityFieldCache = HashMap<Class<*>, Field?>()

    // "Can't install because provider name <authority> (in package <pkg>) is already used by <owner>"
    private val AUTHORITY_REGEX = Regex("""provider name (\S+)""")
    private val OWNER_REGEX = Regex("""already used by (\S+)""")

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

        // void assertProvidersNotDefined(AndroidPackage pkg) -- the locking wrapper that
        // validates every <provider> authority declared by the package being scanned.
        val assertProvidersNotDefinedMethod =
            componentResolverClazz.declaredMethods.firstOrNull { m ->
                m.name == "assertProvidersNotDefined" && m.parameterTypes.size == 1
            } ?: run {
                log("[$name] assertProvidersNotDefined method not found, skipping")
                return
            }

        // Lets us call the *real* implementation again after pulling a provider out, without
        // re-entering this very hook (which invoking the hooked Method object directly would do).
        val invoker = getOriginInvoker(assertProvidersNotDefinedMethod)

        hookAfter(assertProvidersNotDefinedMethod) { callback ->
            if (!Config.isBypassDuplicateProviderEnabled()) return@hookAfter

            var thrown = callback.throwable
            if (thrown == null || !isConflictingProviderException(thrown)) return@hookAfter

            val pkg = callback.args.getOrNull(0)
            val thisObject = callback.thisObject
            if (pkg == null || thisObject == null || invoker == null) {
                // Can't safely retry on this ROM -- leave the exception as-is rather than guess.
                return@hookAfter
            }

            val installingPackageName = getPackageName(pkg)
            if (installingPackageName == null) return@hookAfter

            // Providers we've temporarily pulled out of pkg's own list to get the real check past
            // them; always restored before this hook returns, success or not.
            val setAside = mutableListOf<Any>()
            var attempts = 0

            while (thrown != null && isConflictingProviderException(thrown!!) && attempts < MAX_ATTEMPTS) {
                attempts++

                val ownerPackageName = extractOwnerPackageName(thrown!!)
                if (ownerPackageName == null || ownerPackageName != installingPackageName) {
                    // Not our case -- a genuinely different package already owns this authority.
                    // Leave it to fail like stock Android; we only handle reinstalling ourselves.
                    log("[$name] authority conflict is with a different package ($ownerPackageName); not touching it")
                    break
                }

                val conflictingAuthority = extractConflictingAuthority(thrown!!)
                val provider = conflictingAuthority?.let { findProviderOwning(pkg, it) }
                val list = getMutableProviders(pkg)
                if (provider == null || list == null || !list.remove(provider)) {
                    log("[$name] could not isolate the self-conflicting provider on this ROM, leaving install as-is")
                    break
                }
                setAside.add(provider)
                log("[$name] '$conflictingAuthority' already belongs to this same package ($installingPackageName); signature mismatch on reinstall, letting it through")

                thrown = try {
                    invoker.invoke(thisObject, pkg)
                    null
                } catch (t: InvocationTargetException) {
                    t.targetException ?: t
                } catch (t: Throwable) {
                    t
                }
            }

            // Put everything back, whether we succeeded or gave up, so the registration step that
            // runs after this check still sees pkg's full, unmodified set of providers.
            getMutableProviders(pkg)?.let { list ->
                for (provider in setAside) {
                    if (!list.contains(provider)) list.add(provider)
                }
            }

            callback.throwable = thrown
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
        // error code field/method via reflection on this ROM.
        return t.message?.contains("provider name") == true
    }

    private fun extractConflictingAuthority(t: Throwable): String? {
        val msg = t.message ?: return null
        return AUTHORITY_REGEX.find(msg)?.groupValues?.get(1)
    }

    // The package name that currently owns the conflicting authority, per the exception message.
    // Trailing punctuation (a sentence-ending period, stray ')') is stripped defensively since we
    // don't control the exact wording/formatting across AOSP versions.
    private fun extractOwnerPackageName(t: Throwable): String? {
        val msg = t.message ?: return null
        val raw = OWNER_REGEX.find(msg)?.groupValues?.get(1) ?: return null
        return raw.trimEnd('.', ')', ' ').ifEmpty { null }
    }

    private fun getPackageName(pkg: Any): String? = try {
        val m = pkg.javaClass.methods.firstOrNull { it.name == "getPackageName" && it.parameterCount == 0 }
        m?.invoke(pkg) as? String
    } catch (_: Throwable) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun getMutableProviders(pkg: Any): MutableList<Any>? = try {
        val m = pkg.javaClass.methods.firstOrNull { it.name == "getProviders" && it.parameterCount == 0 }
        m?.invoke(pkg) as? MutableList<Any>
    } catch (_: Throwable) {
        null
    }

    private fun authorityField(provider: Any): Field? =
        authorityFieldCache.getOrPut(provider.javaClass) {
            var c: Class<*>? = provider.javaClass
            var found: Field? = null
            while (c != null && found == null) {
                found = c.declaredFields.firstOrNull { it.type == String::class.java && it.name.contains("uthorit", ignoreCase = true) }
                c = c.superclass
            }
            found?.apply { isAccessible = true }
        }

    private fun readAuthority(provider: Any): String? = try {
        val m = provider.javaClass.methods.firstOrNull { it.name == "getAuthority" && it.parameterCount == 0 }
        (m?.invoke(provider) as? String) ?: authorityField(provider)?.get(provider) as? String
    } catch (_: Throwable) {
        null
    }

    private fun findProviderOwning(pkg: Any, authorityToken: String): Any? {
        val list = getMutableProviders(pkg) ?: return null
        for (provider in list) {
            val value = readAuthority(provider) ?: continue
            val tokens = value.split(';', ',').map { it.trim() }
            if (tokens.contains(authorityToken)) return provider
        }
        return null
    }
}
