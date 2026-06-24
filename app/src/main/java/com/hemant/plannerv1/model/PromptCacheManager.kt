package com.hemant.plannerv1.model

import com.hemant.plannerv1.logging.DbgLog

/**
 * Manages validity of the llama.cpp KV-cache prefix state.
 *
 * The static prefix (system instructions, rules, schema, examples) is processed
 * once and kept in the llama.cpp KV cache across multiple generate() calls.
 * When the static prefix changes (even by one character), the cache is invalidated
 * and the prefix is re-evaluated.
 *
 * Hash function: FNV-1a 64-bit — non-cryptographic, fast, collision-resistant
 * enough for string identity checks.
 */
class PromptCacheManager {

    sealed class CacheResult
    /** The current KV-cache already contains this prefix. Skip prefix eval. */
    data object CacheHit : CacheResult()
    /** The prefix has changed or was never set. Re-evaluate and re-cache. */
    data class CacheMiss(val reason: String) : CacheResult()

    private var cachedHash: Long = 0L
    private var cachedTokenCount: Int = 0
    private var hasCachedPrefix: Boolean = false

    /**
     * Call before each [LlamaCppBackend.generate].
     *
     * Returns [CacheHit] if the KV cache already holds this exact prefix,
     * or [CacheMiss] with the reason why it needs to be rebuilt.
     */
    fun checkPrefix(staticPrefix: String): CacheResult {
        if (!hasCachedPrefix) {
            return CacheMiss("no_cached_prefix").also {
                DbgLog.d(
                    "PromptCacheManager MISS reason=no_cached_prefix",
                    tag = "LLAMA_BACKEND",
                )
            }
        }
        val newHash = fnv1a64(staticPrefix)
        return if (newHash == cachedHash) {
            DbgLog.d(
                "PromptCacheManager HIT hash=${cachedHash.toHex()} " +
                    "prefix_tokens=$cachedTokenCount",
                tag = "LLAMA_BACKEND",
            )
            CacheHit
        } else {
            DbgLog.d(
                "PromptCacheManager MISS reason=prefix_changed " +
                    "old_hash=${cachedHash.toHex()} new_hash=${newHash.toHex()}",
                tag = "LLAMA_BACKEND",
            )
            CacheMiss("prefix_changed")
        }
    }

    /**
     * Call after a successful [LlamaCppBackend.nativeEvalPrefix] call to record
     * the cached prefix so subsequent [checkPrefix] calls can hit.
     */
    fun onPrefixCached(prefix: String, tokenCount: Int) {
        cachedHash = fnv1a64(prefix)
        cachedTokenCount = tokenCount
        hasCachedPrefix = true
        DbgLog.d(
            "PromptCacheManager cache updated hash=${cachedHash.toHex()} " +
                "prefix_tokens=$tokenCount",
            tag = "LLAMA_BACKEND",
        )
    }

    /**
     * Invalidate the cache. Call when the model is released or when a hard reset
     * is required (e.g. OOM recovery).
     */
    fun invalidate() {
        cachedHash = 0L
        cachedTokenCount = 0
        hasCachedPrefix = false
        DbgLog.d("PromptCacheManager invalidated", tag = "LLAMA_BACKEND")
    }

    // ── FNV-1a 64-bit ────────────────────────────────────────────────────────

    private fun fnv1a64(text: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (c in text) {
            hash = hash xor c.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    private fun Long.toHex() = "0x${java.lang.Long.toHexString(this)}"

    companion object {
        private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME        = 1099511628211L        // 0x00000100000001b3
    }
}
