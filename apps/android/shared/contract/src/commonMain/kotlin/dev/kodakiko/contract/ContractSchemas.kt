package dev.kodakiko.contract

object ContractSchemas {
    val collectionMetadataV1: ContractSchema<CollectionMetadataV1> = schema("CollectionMetadataV1", PayloadCodecs.collectionMetadata)

    val entryMetadataV1: ContractSchema<EntryMetadataV1> = schema("EntryMetadataV1", PayloadCodecs.entryMetadata)

    val mediaDescriptorV1: ContractSchema<MediaDescriptorV1> = schema("MediaDescriptorV1", PayloadCodecs.mediaDescriptor)

    val tagName: ContractSchema<String> = schema("TagName", PayloadCodecs.tagName)

    val adminIdParams: ContractSchema<IdParams> = schema("adminIdParams", CommonCodecs.idParams)

    val adminInviteCreateRequest: ContractSchema<AdminInviteCreateRequest> = schema("adminInviteCreateRequest", AdminCodecs.adminInviteCreateRequest)

    val adminInviteCreateResponse: ContractSchema<AdminInviteCreateResponse> = schema("adminInviteCreateResponse", AdminCodecs.adminInviteCreateResponse)

    val adminStatsResponse: ContractSchema<AdminStatsResponse> = schema("adminStatsResponse", AdminCodecs.adminStatsResponse)

    val adminUserDto: ContractSchema<AdminUserDto> = schema("adminUserDto", AdminCodecs.adminUserDto)

    val adminUserListQuery: ContractSchema<ListQuery> = schema("adminUserListQuery", CommonCodecs.listQuery)

    val adminUserListResponse: ContractSchema<AdminUserListResponse> = schema("adminUserListResponse", AdminCodecs.adminUserListResponse)

    val adminUserPatchRequest: ContractSchema<AdminUserPatchRequest> = schema("adminUserPatchRequest", AdminCodecs.adminUserPatchRequest)

    val bytesSchema: ContractSchema<String> = schema("bytesSchema", CommonCodecs.bytes)

    val collectionCreateRequest: ContractSchema<CollectionCreateRequest> = schema("collectionCreateRequest", CollectionsCodecs.collectionCreateRequest)

    val collectionDto: ContractSchema<CollectionDto> = schema("collectionDto", CollectionsCodecs.collectionDto)

    val collectionEntryAddRequest: ContractSchema<CollectionEntryAddRequest> = schema("collectionEntryAddRequest", CollectionsCodecs.collectionEntryAddRequest)

    val collectionEntryParams: ContractSchema<CollectionEntryParams> = schema("collectionEntryParams", CollectionsCodecs.collectionEntryParams)

    val collectionIdParams: ContractSchema<IdParams> = schema("collectionIdParams", CommonCodecs.idParams)

    val collectionListQuery: ContractSchema<ListQuery> = schema("collectionListQuery", CommonCodecs.listQuery)

    val collectionListResponse: ContractSchema<CollectionListResponse> = schema("collectionListResponse", CollectionsCodecs.collectionListResponse)

    val collectionPatchRequest: ContractSchema<CollectionPatchRequest> = schema("collectionPatchRequest", CollectionsCodecs.collectionPatchRequest)

    val deletionRequestResponse: ContractSchema<DeletionRequestResponse> = schema("deletionRequestResponse", UsersCodecs.deletionRequestResponse)

    val diffPage: ContractSchema<DiffPage> = schema("diffPage", SyncCodecs.diffPage)

    val diffQuery: ContractSchema<DiffQuery> = schema("diffQuery", SyncCodecs.diffQuery)

    val downloadResponse: ContractSchema<DownloadResponse> = schema("downloadResponse", ObjectsCodecs.downloadResponse)

    val entryCreateRequest: ContractSchema<EntryCreateRequest> = schema("entryCreateRequest", EntriesCodecs.entryCreateRequest)

    val entryDto: ContractSchema<EntryDto> = schema("entryDto", EntriesCodecs.entryDto)

    val entryIdParams: ContractSchema<IdParams> = schema("entryIdParams", CommonCodecs.idParams)

    val entryPatchRequest: ContractSchema<EntryPatchRequest> = schema("entryPatchRequest", EntriesCodecs.entryPatchRequest)

    val errorResponseSchema: ContractSchema<ErrorResponse> = schema("errorResponseSchema", ErrorCodecs.errorResponse)

    val idSchema: ContractSchema<String> = schema("idSchema", CommonCodecs.id)

    val inviteDto: ContractSchema<InviteDto> = schema("inviteDto", AdminCodecs.inviteDto)

    val inviteListResponse: ContractSchema<InviteListResponse> = schema("inviteListResponse", AdminCodecs.inviteListResponse)

    val keyBundleDto: ContractSchema<KeyBundleDto> = schema("keyBundleDto", AuthCodecs.keyBundleDto)

    val linkDownload: ContractSchema<LinkDownload> = schema("linkDownload", LinksCodecs.linkDownload)

    val linkEntriesQuery: ContractSchema<ListQuery> = schema("linkEntriesQuery", CommonCodecs.listQuery)

    val linkEntriesResponse: ContractSchema<LinkEntriesResponse> = schema("linkEntriesResponse", LinksCodecs.linkEntriesResponse)

    val linkEntryItem: ContractSchema<LinkEntryItem> = schema("linkEntryItem", LinksCodecs.linkEntryItem)

    val linkExpirySchema: ContractSchema<LinkExpiry> = schema("linkExpirySchema", CommonCodecs.linkExpiry)

    val linkObjectDto: ContractSchema<LinkObjectDto> = schema("linkObjectDto", LinksCodecs.linkObjectDto)

    val linkOpenResponse: ContractSchema<LinkOpenResponse> = schema("linkOpenResponse", LinksCodecs.linkOpenResponse)

    val mediaCreateRequest: ContractSchema<MediaCreateRequest> = schema("mediaCreateRequest", MediaCodecs.mediaCreateRequest)

    val mediaDto: ContractSchema<MediaDto> = schema("mediaDto", MediaCodecs.mediaDto)

    val mediaIdParams: ContractSchema<IdParams> = schema("mediaIdParams", CommonCodecs.idParams)

    val objectDto: ContractSchema<ObjectDto> = schema("objectDto", ObjectsCodecs.objectDto)

    val objectIdParams: ContractSchema<IdParams> = schema("objectIdParams", CommonCodecs.idParams)

    val objectStateSchema: ContractSchema<ObjectState> = schema("objectStateSchema", CommonCodecs.objectState)

    val objectVariantSchema: ContractSchema<ObjectVariant> = schema("objectVariantSchema", CommonCodecs.objectVariant)

    val paramsRequest: ContractSchema<ParamsRequest> = schema("paramsRequest", AuthCodecs.paramsRequest)

    val paramsResponse: ContractSchema<ParamsResponse> = schema("paramsResponse", AuthCodecs.paramsResponse)

    val partsResponse: ContractSchema<PartsResponse> = schema("partsResponse", ObjectsCodecs.partsResponse)

    val passwordChangeRequest: ContractSchema<PasswordChangeRequest> = schema("passwordChangeRequest", AuthCodecs.passwordChangeRequest)

    val passwordChangeResponse: ContractSchema<PasswordChangeResponse> = schema("passwordChangeResponse", AuthCodecs.passwordChangeResponse)

    val presignRequest: ContractSchema<PresignRequest> = schema("presignRequest", ObjectsCodecs.presignRequest)

    val presignResponse: ContractSchema<PresignResponse> = schema("presignResponse", ObjectsCodecs.presignResponse)

    val publicConfigSchema: ContractSchema<PublicConfigDto> = schema("publicConfigSchema", ConfigCodecs.publicConfig)

    val publicKeyResponse: ContractSchema<PublicKeyResponse> = schema("publicKeyResponse", UsersCodecs.publicKeyResponse)

    val publicLinkCreateRequest: ContractSchema<PublicLinkCreateRequest> = schema("publicLinkCreateRequest", LinksCodecs.publicLinkCreateRequest)

    val publicLinkDto: ContractSchema<PublicLinkDto> = schema("publicLinkDto", LinksCodecs.publicLinkDto)

    val publicLinkListResponse: ContractSchema<PublicLinkListResponse> = schema("publicLinkListResponse", LinksCodecs.publicLinkListResponse)

    val publicLinkObjectParams: ContractSchema<PublicLinkObjectParams> = schema("publicLinkObjectParams", LinksCodecs.publicLinkObjectParams)

    val publicLinkParams: ContractSchema<PublicLinkParams> = schema("publicLinkParams", LinksCodecs.publicLinkParams)

    val recoveryCompleteRequest: ContractSchema<RecoveryCompleteRequest> = schema("recoveryCompleteRequest", AuthCodecs.recoveryCompleteRequest)

    val recoveryStartRequest: ContractSchema<RecoveryStartRequest> = schema("recoveryStartRequest", AuthCodecs.recoveryStartRequest)

    val recoveryStartResponse: ContractSchema<RecoveryStartResponse> = schema("recoveryStartResponse", AuthCodecs.recoveryStartResponse)

    val revisionConflictDetails: ContractSchema<RevisionConflictDetails> = schema("revisionConflictDetails", EntriesCodecs.revisionConflictDetails)

    val sessionDto: ContractSchema<SessionDto> = schema("sessionDto", AuthCodecs.sessionDto)

    val sessionResponse: ContractSchema<SessionResponse> = schema("sessionResponse", AuthCodecs.sessionResponse)

    val shareCreateRequest: ContractSchema<ShareCreateRequest> = schema("shareCreateRequest", CollectionsCodecs.shareCreateRequest)

    val shareDto: ContractSchema<ShareDto> = schema("shareDto", CollectionsCodecs.shareDto)

    val shareListResponse: ContractSchema<ShareListResponse> = schema("shareListResponse", CollectionsCodecs.shareListResponse)

    val shareParams: ContractSchema<ShareParams> = schema("shareParams", CollectionsCodecs.shareParams)

    val signInRequest: ContractSchema<SignInRequest> = schema("signInRequest", AuthCodecs.signInRequest)

    val signInResponse: ContractSchema<SignInResponse> = schema("signInResponse", AuthCodecs.signInResponse)

    val signUpRequest: ContractSchema<SignUpRequest> = schema("signUpRequest", AuthCodecs.signUpRequest)

    val signUpResponse: ContractSchema<SignUpResponse> = schema("signUpResponse", AuthCodecs.signUpResponse)

    val syncStateResponse: ContractSchema<SyncStateResponse> = schema("syncStateResponse", SyncCodecs.syncStateResponse)

    val userDto: ContractSchema<UserDto> = schema("userDto", AuthCodecs.userDto)

    val userPublicKeyParams: ContractSchema<UserPublicKeyParams> = schema("userPublicKeyParams", UsersCodecs.userPublicKeyParams)

    @Suppress("UNCHECKED_CAST")
    val all: Map<String, ContractSchema<Any?>> = listOf<ContractSchema<*>>(
        collectionMetadataV1,
        entryMetadataV1,
        mediaDescriptorV1,
        tagName,
        adminIdParams,
        adminInviteCreateRequest,
        adminInviteCreateResponse,
        adminStatsResponse,
        adminUserDto,
        adminUserListQuery,
        adminUserListResponse,
        adminUserPatchRequest,
        bytesSchema,
        collectionCreateRequest,
        collectionDto,
        collectionEntryAddRequest,
        collectionEntryParams,
        collectionIdParams,
        collectionListQuery,
        collectionListResponse,
        collectionPatchRequest,
        deletionRequestResponse,
        diffPage,
        diffQuery,
        downloadResponse,
        entryCreateRequest,
        entryDto,
        entryIdParams,
        entryPatchRequest,
        errorResponseSchema,
        idSchema,
        inviteDto,
        inviteListResponse,
        keyBundleDto,
        linkDownload,
        linkEntriesQuery,
        linkEntriesResponse,
        linkEntryItem,
        linkExpirySchema,
        linkObjectDto,
        linkOpenResponse,
        mediaCreateRequest,
        mediaDto,
        mediaIdParams,
        objectDto,
        objectIdParams,
        objectStateSchema,
        objectVariantSchema,
        paramsRequest,
        paramsResponse,
        partsResponse,
        passwordChangeRequest,
        passwordChangeResponse,
        presignRequest,
        presignResponse,
        publicConfigSchema,
        publicKeyResponse,
        publicLinkCreateRequest,
        publicLinkDto,
        publicLinkListResponse,
        publicLinkObjectParams,
        publicLinkParams,
        recoveryCompleteRequest,
        recoveryStartRequest,
        recoveryStartResponse,
        revisionConflictDetails,
        sessionDto,
        sessionResponse,
        shareCreateRequest,
        shareDto,
        shareListResponse,
        shareParams,
        signInRequest,
        signInResponse,
        signUpRequest,
        signUpResponse,
        syncStateResponse,
        userDto,
        userPublicKeyParams,
    ).associate { it.name to it as ContractSchema<Any?> }
}
