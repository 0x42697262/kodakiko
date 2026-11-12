import {
  decodeBytes,
  type KeyBundleDto,
  type ParamsRequest,
  type ParamsResponse,
  type PasswordChangeRequest,
  type RecoveryCompleteRequest,
  type RecoveryStartRequest,
  type RecoveryStartResponse,
  encodeBytes,
  idSchema,
  type SessionDto,
  type SignInRequest,
  type SignInResponse,
  type SignUpRequest,
  type SignUpResponse,
  type UserDto,
} from '@kodakiko/contract';
import type { AuthContext, AuthService } from '../../services/auth-service.ts';
import {
  toKeyBundleDto,
  toParamsResponse,
  toSessionDto,
  toUserDto,
} from './auth-mappers.ts';

export function createAuthController(service: AuthService) {
  return {
    async params(body: ParamsRequest): Promise<ParamsResponse> {
      return toParamsResponse(await service.params(body.username));
    },

    async signUp(body: SignUpRequest, ip: string): Promise<SignUpResponse> {
      const result = await service.signUp(
        {
          userId: body.userId,
          username: body.username,
          inviteToken: body.inviteToken,
          salt: decodeBytes(body.salt),
          opsLimit: body.opsLimit,
          memLimitBytes: body.memLimitBytes,
          loginKey: decodeBytes(body.loginKey),
          recoveryLoginKey: decodeBytes(body.recoveryLoginKey),
          publicKey: decodeBytes(body.publicKey),
          masterKeyByKek: decodeBytes(body.masterKeyByKek),
          masterKeyByRecovery: decodeBytes(body.masterKeyByRecovery),
          recoveryKeyByMaster: decodeBytes(body.recoveryKeyByMaster),
          privateKeyByMaster: decodeBytes(body.privateKeyByMaster),
          homeCollection: {
            id: body.homeCollection.id,
            keyByOwner: decodeBytes(body.homeCollection.keyByOwner),
            metadataBlob: decodeBytes(body.homeCollection.metadataBlob),
          },
        },
        body.deviceLabel,
        ip,
      );
      return { session: toSessionDto(result.session), user: toUserDto(result.user) };
    },

    async signIn(body: SignInRequest): Promise<SignInResponse> {
      const result = await service.signIn(
        body.username,
        decodeBytes(body.loginKey),
        body.deviceLabel,
      );
      return {
        session: toSessionDto(result.session),
        user: toUserDto(result.user),
        keyBundle: toKeyBundleDto(result.keyBundle),
      };
    },

    async keyBundle(auth: AuthContext): Promise<KeyBundleDto> {
      return toKeyBundleDto(await service.keyBundle(auth.userId));
    },

    async recoveryStart(body: RecoveryStartRequest): Promise<RecoveryStartResponse> {
      const result = await service.recoveryStart(body.username);
      return {
        userId: idSchema.parse(result.userId),
        masterKeyByRecovery: encodeBytes(result.masterKeyByRecovery),
      };
    },

    async recoveryComplete(body: RecoveryCompleteRequest): Promise<SignInResponse> {
      const result = await service.recoveryComplete(
        {
          username: body.username,
          recoveryLoginKey: decodeBytes(body.recoveryLoginKey),
          salt: decodeBytes(body.salt),
          opsLimit: body.opsLimit,
          memLimitBytes: body.memLimitBytes,
          loginKey: decodeBytes(body.loginKey),
          masterKeyByKek: decodeBytes(body.masterKeyByKek),
        },
        body.deviceLabel,
      );
      return {
        session: toSessionDto(result.session),
        user: toUserDto(result.user),
        keyBundle: toKeyBundleDto(result.keyBundle),
      };
    },

    async changePassword(
      auth: AuthContext,
      body: PasswordChangeRequest,
    ): Promise<{ session: SessionDto }> {
      const session = await service.changePassword(auth.userId, auth.sessionId, auth.deviceLabel, {
        currentLoginKey: decodeBytes(body.currentLoginKey),
        salt: decodeBytes(body.salt),
        opsLimit: body.opsLimit,
        memLimitBytes: body.memLimitBytes,
        loginKey: decodeBytes(body.loginKey),
        masterKeyByKek: decodeBytes(body.masterKeyByKek),
      });
      return { session: toSessionDto(session) };
    },

    signOut(auth: AuthContext): Promise<void> {
      return service.signOut(auth.sessionId);
    },

    async session(auth: AuthContext): Promise<{ user: UserDto }> {
      return { user: toUserDto(await service.currentUser(auth.userId)) };
    },
  };
}

export type AuthController = ReturnType<typeof createAuthController>;
