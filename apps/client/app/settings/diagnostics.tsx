import { Redirect } from 'expo-router';
import { DiagnosticsScreen } from '../../src/features/diagnostics/DiagnosticsScreen';
import { APP_ROUTES } from '../../src/keys/routes';

export default function DiagnosticsRoute() {
  return __DEV__ ? <DiagnosticsScreen /> : <Redirect href={APP_ROUTES.root} />;
}
