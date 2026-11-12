import { SALT_BYTES } from '../crypto/constants.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { deriveLoginMaterial, type LoginMaterial } from './derive.ts';
import { HostileServerParamsError } from './errors.ts';
import { meetsKdfFloor, type KdfParams } from './kdf-params.ts';

export async function deriveForSignIn(
  sodium: Sodium,
  password: string,
  params: KdfParams,
): Promise<LoginMaterial> {
  if (!meetsKdfFloor(params) || params.salt.length !== SALT_BYTES) {
    throw new HostileServerParamsError();
  }
  return deriveLoginMaterial(sodium, password, params);
}
