import type { DeletionRequestResponse, UserDto } from '@kodakiko/contract';
import type { AuthContext, AuthService } from '../../services/auth-service.ts';
import { toUserDto } from './auth-mappers.ts';

export function createUsersController(service: AuthService) {
  return {
    async me(auth: AuthContext): Promise<UserDto> {
      return toUserDto(await service.currentUser(auth.userId));
    },

    async requestDeletion(auth: AuthContext): Promise<DeletionRequestResponse> {
      const { deletionDueAt } = await service.requestDeletion(auth.userId);
      return { deletionDueAt: deletionDueAt.toISOString() };
    },

    async cancelDeletion(auth: AuthContext): Promise<UserDto> {
      return toUserDto(await service.cancelDeletion(auth.userId));
    },
  };
}

export type UsersController = ReturnType<typeof createUsersController>;
