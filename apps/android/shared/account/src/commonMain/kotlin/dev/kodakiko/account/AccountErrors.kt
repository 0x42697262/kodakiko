package dev.kodakiko.account

sealed class AccountException(message: String?) : Exception(message)

class KdfUnavailableError(message: String? = null) : AccountException(message)

class HostileServerParamsError(message: String? = null) : AccountException(message)

class WrongPasswordError(message: String? = null) : AccountException(message)

class WrongRecoveryWordsError(message: String? = null) : AccountException(message)
