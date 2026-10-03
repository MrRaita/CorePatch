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
 * Fixes INSTALL_FAILED_CONFLICTING_PROVIDER, the <provider> authority counterpart of the
 * duplicate-permission check handled in [InstallPackageHelperHook]. A package that declares a
 * <provider android:authorities="..."> already owned by another installed package (e.g. a
 * cloned/re-signed copy of an app that never had its authorities re-templated with its own
 * applicationId) is rejected by the platform during scanning.
 *
 * ComponentResolver#assertProvidersNotDefined(AndroidPackage) is a void method that either
 * returns normally or throws a PackageManagerException(INSTALL_FAILED_CONFLICTING_PROVIDER, ...)
 * naming exactly one conflicting authority. Rather than only swallowing that exception (which
 * leaves the authority registered to whichever package already owned it -- the new package's own
 * copy of that provider is never actually reachable, breaking anything that relies on it, e.g. a
 * library that gets its Context from its own ContentProvider#onCreate()), we instead:
 *
 *  1. Let the real check run.
 *  2. On conflict, check who the exception says already owns the authority. If it's this SAME
 *     package (by name) -- a reinstall/update of this exact app whose signature no longer matches
 *     its previous install, e.g. an unsigned/debug build rebuilt on every deploy -- there is
 *     nothing to rename; we just suppress and move on, since renaming here would pile a new
 *     "cp_" prefix onto an already-correct authority on every single update.
 *  3. Otherwise (a genuinely different package, e.g. a clone with a different applicationId),
 *     find which of the installing package's own <provider> declarations owns the clashing
 *     authority and rewrite just that authority to a value derived from the package's own
 *     (unique) package name, so it no longer collides with anyone.
 *  4. Re-run the real check (via the ORIGIN invoker, bypassing our own hook to avoid recursion)
 *     to see if any other authority also conflicts, repeating until it passes or we hit a
 *     provider we're not supposed to touch.
 *
 * A handful of providers (FileProvider and friends) have their authority hardcoded into the
 * app's own compiled code (e.g. FileProvider.getUriForFile(...)), so silently renaming them would
 * leave that call pointing at the wrong (original) app instead of crashing -- a different,
 * quieter kind of broken. Those are only renamed when BYPASS_DUPLICATE_PROVIDER_INCLUDE_SENSITIVE
 * is on; otherwise we fall back to plain suppression for that one authority, same as before.
 *
 * The exact class moved from com.android.server.pm.ComponentResolver (pre Android 12) to
 * com.android.server.pm.resolution.ComponentResolver in later AOSP refactors; both are probed.
 */
object ComponentResolverHook : BaseHook() {
    override val name = "ComponentResolverHook"

    // android.content.pm.PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER
    private const val INSTALL_FAILED_CONFLICTING_PROVIDER = -13
    private const val MAX_RENAME_ATTEMPTS = 32

    // Component class names containing any of these are assumed to have their authority
    // hardcoded somewhere in app code (FileProvider.getUriForFile, DocumentsProvider clients,
    // etc.) rather than only being self-initializing library plumbing.
    private val SENSITIVE_CLASS_NAME_MARKERS = listOf("FileProvider", "DocumentsProvider")

    private val authorityFieldCache = HashMap<Class<*>, Field?>()
    private val nameFieldCache = HashMap<Class<*>, Field?>()

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

        // Lets us call the *real* implementation again after renaming an authority, without
        // re-entering this very hook (which invoking the hooked Method object directly would do).
        val invoker = getOriginInvoker(assertProvidersNotDefinedMethod)

        hookAfter(assertProvidersNotDefinedMethod) { callback ->
            if (!Config.isBypassDuplicateProviderEnabled()) return@hookAfter

            var thrown = callback.throwable
            if (thrown == null || !isConflictingProviderException(thrown)) return@hookAfter

            val pkg = callback.args.getOrNull(0)
            val thisObject = callback.thisObject

            if (pkg == null || thisObject == null || invoker == null) {
                // Can't do the smarter fix on this ROM -- fall back to plain suppression so we
                // at least behave like before rather than leaving the install blocked.
                callback.throwable = null
                return@hookAfter
            }

            var attempts = 0
            var lastAuthoritySeen: String? = null
            while (thrown != null && isConflictingProviderException(thrown!!) && attempts < MAX_RENAME_ATTEMPTS) {
                attempts++
                val conflictingAuthority = extractConflictingAuthority(thrown!!)
                if (conflictingAuthority == null) {
                    log("[$name] could not parse conflicting authority from: ${thrown?.message}")
                    break
                }

                // Guard against an exclude/rename that silently didn't take effect (e.g. an
                // unmodifiable provider list on this ROM): if we see the exact same authority
                // conflict twice in a row, stop trying and just suppress so we don't spin.
                if (conflictingAuthority == lastAuthoritySeen) {
                    log("[$name] '$conflictingAuthority' still conflicting after handling it; giving up and suppressing")
                    thrown = null
                    break
                }
                lastAuthoritySeen = conflictingAuthority

                // If the authority is already owned by THIS SAME package name, we're not looking
                // at a clone/duplicate at all -- we're reinstalling/updating this exact app, just
                // with a signature that no longer matches its previous install (typical for an
                // unsigned or debug build rebuilt on every deploy). There is nothing to rename:
                // the package already owns this authority by name, the only problem is the
                // signature check, so just let the install proceed as-is. Renaming here would be
                // actively harmful -- it would pile a new "cp_" prefix onto an already-correct
                // authority on every single reinstall.
                val ownerPackageName = extractOwnerPackageName(thrown!!)
                val installingPackageName = getPackageName(pkg)
                if (ownerPackageName != null && installingPackageName != null && ownerPackageName == installingPackageName) {
                    log("[$name] '$conflictingAuthority' already belongs to this same package ($installingPackageName); this is a reinstall/update with a signature mismatch, not a clone -- suppressing without renaming")
                    thrown = null
                    break
                }

                val provider = findProviderOwning(pkg, conflictingAuthority)
                if (provider == null) {
                    log("[$name] could not find a <provider> in this package owning authority '$conflictingAuthority', suppressing as-is")
                    thrown = null
                    break
                }

                val sensitive = isSensitiveProvider(provider)
                if (sensitive && !Config.isBypassDuplicateProviderIncludeSensitiveEnabled()) {
                    // Don't touch this one's authority, but don't just give up either -- doing so
                    // would abandon validation of every OTHER provider declared after this one in
                    // the manifest (the real check aborts at the first conflict it hits), which is
                    // exactly what silently broke providers like AndroidContextProvider before.
                    // Instead, exclude this single provider from the package for this install so
                    // the real check can keep going and fix up everything that comes after it.
                    if (excludeProviderFromPackage(pkg, provider)) {
                        log("[$name] '$conflictingAuthority' belongs to a sensitive provider (${providerClassName(provider)}); excluding it from this install and continuing")
                    } else {
                        log("[$name] '$conflictingAuthority' is sensitive and could not be excluded on this ROM; suppressing without renaming")
                        thrown = null
                        break
                    }
                } else {
                    val packageName = getPackageName(pkg)
                    if (packageName == null || !renameAuthority(provider, conflictingAuthority, packageName)) {
                        log("[$name] failed to rename authority '$conflictingAuthority', suppressing as-is")
                        thrown = null
                        break
                    }
                    log("[$name] renamed conflicting authority '$conflictingAuthority' for package $packageName")
                }

                thrown = try {
                    invoker.invoke(thisObject, pkg)
                    null
                } catch (t: InvocationTargetException) {
                    t.targetException ?: t
                } catch (t: Throwable) {
                    t
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
        // error code field/method via reflection on this ROM:
        // "Can't install because provider name <authority> (in package <pkg>) is already used by <owner>"
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

    private fun getProviders(pkg: Any): List<Any> = try {
        val m = pkg.javaClass.methods.firstOrNull { it.name == "getProviders" && it.parameterCount == 0 }
        @Suppress("UNCHECKED_CAST")
        (m?.invoke(pkg) as? List<Any>) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * Removes [provider] from [pkg]'s own provider list so a retried assertProvidersNotDefined()
     * call no longer sees (and doesn't throw on) it, letting validation continue to whatever is
     * declared after it. Only works if the live list returned by getProviders() is actually the
     * mutable backing list (not a defensive copy or an unmodifiable wrapper); returns false if we
     * can't remove it, so the caller can fall back to the old plain-suppress behavior instead.
     */
    private fun excludeProviderFromPackage(pkg: Any, provider: Any): Boolean {
        val list = try {
            val m = pkg.javaClass.methods.firstOrNull { it.name == "getProviders" && it.parameterCount == 0 }
            m?.invoke(pkg)
        } catch (_: Throwable) {
            null
        } ?: return false

        return try {
            @Suppress("UNCHECKED_CAST")
            val mutable = list as? MutableList<Any> ?: return false
            mutable.remove(provider)
        } catch (_: Throwable) {
            false
        }
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

    private fun nameField(provider: Any): Field? =
        nameFieldCache.getOrPut(provider.javaClass) {
            var c: Class<*>? = provider.javaClass
            var found: Field? = null
            while (c != null && found == null) {
                found = c.declaredFields.firstOrNull { it.type == String::class.java && it.name == "className" }
                    ?: c.declaredFields.firstOrNull { it.type == String::class.java && it.name == "name" }
                c = c.superclass
            }
            found?.apply { isAccessible = true }
        }

    private fun providerClassName(provider: Any): String? = try {
        // Prefer the real getName()/getClassName() accessor; fall back to the raw field.
        val m = provider.javaClass.methods.firstOrNull {
            (it.name == "getClassName" || it.name == "getName") && it.parameterCount == 0 && it.returnType == String::class.java
        }
        (m?.invoke(provider) as? String) ?: nameField(provider)?.get(provider) as? String
    } catch (_: Throwable) {
        null
    }

    private fun isSensitiveProvider(provider: Any): Boolean {
        val className = providerClassName(provider) ?: return true // unknown -> be conservative
        return SENSITIVE_CLASS_NAME_MARKERS.any { className.contains(it) }
    }

    private fun readAuthority(provider: Any): String? = try {
        val m = provider.javaClass.methods.firstOrNull { it.name == "getAuthority" && it.parameterCount == 0 }
        (m?.invoke(provider) as? String) ?: authorityField(provider)?.get(provider) as? String
    } catch (_: Throwable) {
        null
    }

    private fun findProviderOwning(pkg: Any, authorityToken: String): Any? {
        for (provider in getProviders(pkg)) {
            val value = readAuthority(provider) ?: continue
            if (splitAuthorities(value).contains(authorityToken)) return provider
        }
        return null
    }

    private fun splitAuthorities(value: String): List<String> =
        value.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun renameAuthority(provider: Any, oldToken: String, packageName: String): Boolean {
        val field = authorityField(provider) ?: return false
        val current = try {
            field.get(provider) as? String ?: return false
        } catch (_: Throwable) {
            return false
        }
        val delimiter = if (current.contains(';')) ';' else ','
        val newToken = "$packageName.cp_${oldToken.replace('.', '_').replace('-', '_')}"
        val updated = current.split(delimiter)
            .joinToString(delimiter.toString()) { if (it.trim() == oldToken) newToken else it }
        return try {
            field.set(provider, updated)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
