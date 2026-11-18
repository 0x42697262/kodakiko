package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UserDto

object AccountCalls {
    fun me(): ApiCall<UserDto> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.usersMe(),
        body = null,
        responseSchema = ContractSchemas.userDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )
}
