import { EN } from '../../text/en';
import { Screen } from '../../ui/Screen';
import { Body, Title } from '../../ui/Typography';

export function SecuringScreen() {
  return (
    <Screen testID="signUp.securing.screen">
      <Title testID="signUp.securing" text={EN['signUp.securing']} />
      <Body testID="signUp.securingDetail" text={EN['signUp.securingDetail']} />
    </Screen>
  );
}
