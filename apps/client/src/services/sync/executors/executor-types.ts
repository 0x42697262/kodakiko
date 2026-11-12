import type { PushFailure } from '@kodakiko/core';
import type { HttpClient } from '../../../adapters/http/http';
import type { Logger } from '../../../adapters/logger';
import type { FileStore } from '../../../adapters/filestore/filestore';
import type { OutboxRow, RowStore } from '../../../adapters/rowstore/rowstore';
import type { AppEventBus } from '../../../composition/events';
import type { ConflictResolver } from '../conflict';
import type { UploadCoordinator } from '../uploads';

export type ExecutorResult = 'done' | 'waiting' | { readonly failure: PushFailure };

export type ExecutorDeps = {
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly events: AppEventBus;
  readonly log: Logger;
  readonly uploads: Pick<UploadCoordinator, 'enqueueObjectUploads'>;
  readonly conflict: ConflictResolver;
};

export type Executor = (item: OutboxRow, deps: ExecutorDeps) => Promise<ExecutorResult>;
