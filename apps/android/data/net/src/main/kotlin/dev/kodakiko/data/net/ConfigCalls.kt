package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.Routes

object ConfigCalls {
    fun get(): ApiCall<PublicConfigDto> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.config(),
        body = null,
        responseSchema = ContractSchemas.publicConfigSchema,
        auth = ApiAuth.None,
        idempotent = true,
    )
}
