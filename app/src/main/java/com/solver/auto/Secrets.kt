package com.solver.auto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Secrets {
    private const val FILE = "solver_secure"
    private const val KEY = "api_key"

    private fun secure(ctx: Context): SharedPreferences? = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, FILE, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) { null }

    fun putKey(ctx: Context, value: String) {
        (secure(ctx) ?: ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE))
            .edit().putString(KEY, value).apply()
    }

    fun getKey(ctx: Context): String =
        secure(ctx)?.getString(KEY, null)
            ?: ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
}
