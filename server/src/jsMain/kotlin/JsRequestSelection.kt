package com.steamstreet.graphkt.server

actual fun gqlRequestContext(): RequestSelection? {
    throw NotImplementedError()
}