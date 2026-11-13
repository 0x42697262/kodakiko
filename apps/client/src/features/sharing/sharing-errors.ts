import { SharingError } from '../../services/sharing/sharing-service';
import { EN } from '../../text/en';

export function sharingErrorText(error: unknown, recipient = ''): string {
  if (!(error instanceof SharingError)) return EN['errors.sharingFailed'];
  switch (error.reason) {
    case 'offline':
      return EN['errors.sharingOffline'];
    case 'userNotFound':
      return EN['errors.userNotFound'];
    case 'alreadyShared':
      return EN['errors.alreadyShared'](recipient);
    case 'disabled':
      return EN['shared.flagOff'];
    case 'locked':
    case 'notOwner':
      return EN['errors.sharingFailed'];
  }
}
