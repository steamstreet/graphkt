package com.steamstreet.graphkt.server

val gqlContext = ThreadLocal<RequestSelection>()

actual fun gqlRequestContext(): RequestSelection {
    return gqlContext.get()
}