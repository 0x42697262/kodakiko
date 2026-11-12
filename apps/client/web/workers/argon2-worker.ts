import sodium from 'libsodium-wrappers-sumo';
import {
  ARGON2_WORKER_ERROR,
  type Argon2Request,
  type Argon2Response,
} from '../../src/adapters/sodium/argon2-messages';

type WorkerScope = {
  onmessage: ((event: MessageEvent<Argon2Request>) => void) | null;
  postMessage: (message: Argon2Response, transfer?: Transferable[]) => void;
};

const scope = self as unknown as WorkerScope;

scope.onmessage = async (event) => {
  const { id, password, salt, opsLimit, memLimitBytes, outputLength } = event.data;
  try {
    await sodium.ready;
    const output = sodium.crypto_pwhash(
      outputLength,
      password,
      salt,
      opsLimit,
      memLimitBytes,
      sodium.crypto_pwhash_ALG_ARGON2ID13,
    );
    scope.postMessage({ id, output }, [output.buffer]);
  } catch {
    scope.postMessage({ id, error: ARGON2_WORKER_ERROR });
  } finally {
    password.fill(0);
    salt.fill(0);
  }
};
