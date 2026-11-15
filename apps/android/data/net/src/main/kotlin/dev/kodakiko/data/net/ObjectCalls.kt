package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.DownloadResponse
import dev.kodakiko.contract.Routes

object ObjectCalls {
    fun download(objectId: String): ApiCall<DownloadResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.objectDownload(objectId),
        body = null,
        responseSchema = ContractSchemas.downloadResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )
}
