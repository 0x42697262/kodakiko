package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.DeletionRequestResponse
import dev.kodakiko.contract.KeyBundleDto
import dev.kodakiko.contract.ParamsRequest
import dev.kodakiko.contract.ParamsResponse
import dev.kodakiko.contract.PasswordChangeRequest
import dev.kodakiko.contract.PasswordChangeResponse
import dev.kodakiko.contract.RecoveryCompleteRequest
import dev.kodakiko.contract.RecoveryStartRequest
import dev.kodakiko.contract.RecoveryStartResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SessionResponse
import dev.kodakiko.contract.SignInRequest
import dev.kodakiko.contract.SignInResponse
import dev.kodakiko.contract.SignUpRequest
import dev.kodakiko.contract.SignUpResponse
import dev.kodakiko.contract.UserDto

object AuthCalls {
    fun params(body: ParamsRequest): ApiCall<ParamsResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authParams(),
        body = ContractSchemas.paramsRequest.encode(body),
        responseSchema = ContractSchemas.paramsResponse,
        auth = ApiAuth.None,
        idempotent = true,
    )

    fun signUp(body: SignUpRequest): ApiCall<SignUpResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authSignUp(),
        body = ContractSchemas.signUpRequest.encode(body),
        responseSchema = ContractSchemas.signUpResponse,
        auth = ApiAuth.None,
        idempotent = false,
    )

    fun signIn(body: SignInRequest): ApiCall<SignInResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authSignIn(),
        body = ContractSchemas.signInRequest.encode(body),
        responseSchema = ContractSchemas.signInResponse,
        auth = ApiAuth.None,
        idempotent = false,
    )

    fun session(): ApiCall<SessionResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.authSession(),
        body = null,
        responseSchema = ContractSchemas.sessionResponse,
        auth = ApiAuth.Session,
        idempotent = true,
        timeoutMs = CallConstants.RESTORE_TIMEOUT_MS,
    )

    fun keyBundle(): ApiCall<KeyBundleDto> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.authKeyBundle(),
        body = null,
        responseSchema = ContractSchemas.keyBundleDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun recoveryStart(body: RecoveryStartRequest): ApiCall<RecoveryStartResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authRecoveryStart(),
        body = ContractSchemas.recoveryStartRequest.encode(body),
        responseSchema = ContractSchemas.recoveryStartResponse,
        auth = ApiAuth.None,
        idempotent = true,
    )

    fun recoveryComplete(body: RecoveryCompleteRequest): ApiCall<SignInResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authRecoveryComplete(),
        body = ContractSchemas.recoveryCompleteRequest.encode(body),
        responseSchema = ContractSchemas.signInResponse,
        auth = ApiAuth.None,
        idempotent = false,
    )

    fun signOut(): ApiCall<Unit> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authSignOut(),
        body = null,
        responseSchema = VoidResponse,
        auth = ApiAuth.Session,
        idempotent = false,
        timeoutMs = CallConstants.SIGN_OUT_TIMEOUT_MS,
    )

    fun changePassword(body: PasswordChangeRequest): ApiCall<PasswordChangeResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.authPassword(),
        body = ContractSchemas.passwordChangeRequest.encode(body),
        responseSchema = ContractSchemas.passwordChangeResponse,
        auth = ApiAuth.Session,
        idempotent = false,
    )

    fun requestDeletion(): ApiCall<DeletionRequestResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.usersMeDelete(),
        body = null,
        responseSchema = ContractSchemas.deletionRequestResponse,
        auth = ApiAuth.Session,
        idempotent = false,
    )

    fun cancelDeletion(): ApiCall<UserDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.usersMeDeleteCancel(),
        body = null,
        responseSchema = ContractSchemas.userDto,
        auth = ApiAuth.Session,
        idempotent = false,
    )
}
