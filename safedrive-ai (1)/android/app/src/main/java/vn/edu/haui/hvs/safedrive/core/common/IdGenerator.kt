package vn.edu.haui.hvs.safedrive.core.common

import java.util.UUID

/** Generates client-side request/event ids. Never used as a security token. */
interface IdGenerator {
    fun next(prefix: String): String
}

class UuidIdGenerator : IdGenerator {
    override fun next(prefix: String): String = "${prefix}_${UUID.randomUUID()}"
}
