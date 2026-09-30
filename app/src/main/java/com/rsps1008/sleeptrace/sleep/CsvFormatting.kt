package com.rsps1008.sleeptrace.sleep

fun csvEscape(value: String): String = if (value.any { it in ",\"\r\n" })
    "\"${value.replace("\"", "\"\"")}\"" else value
