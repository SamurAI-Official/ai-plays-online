package me.nathanfallet.aiplayspokemon.dev

/** The value of `--flag=value` in a tool's arguments, or null (shared by the `dev` tools). */
internal fun Array<String>.value(flag: String): String? =
    firstOrNull { it.startsWith(flag) }?.removePrefix(flag)?.takeIf { it.isNotBlank() }
