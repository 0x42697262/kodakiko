import { useState } from 'react';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { errorBanner } from '../auth/error-text';
import { extractInviteToken } from '../auth/invite-input';
import { useSubmit } from '../auth/use-submit';
import { SettingsPage } from './settings-ui';

export function ConnectServerScreen() {
  const { services } = useAppContext();
  const state = useAccountState();
  const [server, setServer] = useState('');
  const [invite, setInvite] = useState('');
  const [username, setUsername] = useState(state.kind === 'unlocked' ? state.username : '');
  const [password, setPassword] = useState('');
  const [done, setDone] = useState(false);
  const { busy, error, submit } = useSubmit();
  const banner = error === null ? null : errorBanner(error);

  const connect = () =>
    submit(async () => {
      const inviteToken = invite.trim().length === 0 ? null : extractInviteToken(invite);
      await services.auth.connectServer({ serverInput: server, inviteToken, username, password });
      setPassword('');
      setDone(true);
    });

  return (
    <SettingsPage testID="connect.screen" title={EN['connect.title']}>
      {done ? <Banner testID="connect.done" kind="info" message={EN['connect.done']} /> : null}
      {banner === null ? null : <Banner testID={banner.testID} kind="error" message={banner.message} />}
      <TextField testID="connect.server" label={EN['connect.server']} value={server} onChangeText={setServer} autoCapitalize="none" keyboardType="url" />
      <TextField testID="connect.invite" label={EN['connect.invite']} value={invite} onChangeText={setInvite} autoCapitalize="none" />
      <TextField testID="connect.username" label={EN['signUp.username']} value={username} onChangeText={setUsername} autoCapitalize="none" />
      <TextField testID="connect.password" label={EN['settings.deletePassword']} value={password} onChangeText={setPassword} secureTextEntry onSubmit={connect} />
      <Button testID="connect.action" label={EN['connect.action']} busy={busy} disabled={done || server.trim().length === 0 || password.length === 0} onPress={connect} />
    </SettingsPage>
  );
}
