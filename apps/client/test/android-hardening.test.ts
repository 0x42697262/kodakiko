import { describe, expect, it } from 'vitest';
import {
  buildDataExtractionRules,
  buildDebugNetworkSecurityConfig,
  buildNetworkSecurityConfig,
  removeMainManifestPermissions,
} from '../plugins/with-android-hardening.js';

describe('android hardening plugin', () => {
  it('excludes every domain from cloud backup and device transfer', () => {
    const rules = buildDataExtractionRules();
    const cloud = rules.slice(rules.indexOf('<cloud-backup>'), rules.indexOf('</cloud-backup>'));
    const transfer = rules.slice(
      rules.indexOf('<device-transfer>'),
      rules.indexOf('</device-transfer>'),
    );
    for (const section of [cloud, transfer]) {
      expect(section.match(/<exclude /g)?.length).toBe(9);
      expect(section).toContain('domain="root"');
      expect(section).toContain('domain="sharedpref"');
      expect(section).toContain('domain="device_sharedpref"');
    }
  });

  it('forbids cleartext in the main config and trusts user certificates only in debug overrides', () => {
    const main = buildNetworkSecurityConfig();
    expect(main).toContain('<base-config cleartextTrafficPermitted="false">');
    expect(main).toContain('<certificates src="system" />');
    expect(main).not.toContain('domain-config');
    expect(main.slice(main.indexOf('<debug-overrides>'))).toContain('<certificates src="user" />');
    expect(main.slice(0, main.indexOf('<debug-overrides>'))).not.toContain('src="user"');
  });

  it('allows cleartext only for local hosts in the debug source set copy', () => {
    const debug = buildDebugNetworkSecurityConfig();
    expect(debug).toContain('<domain-config cleartextTrafficPermitted="true">');
    for (const host of ['localhost', '127.0.0.1', '10.0.2.2']) {
      expect(debug).toContain(`>${host}</domain>`);
    }
    expect(debug).toContain('<base-config cleartextTrafficPermitted="false">');
    expect(debug).toContain('<certificates src="user" />');
  });
});

describe('main manifest permission removal', () => {
  it('removes SYSTEM_ALERT_WINDOW and VIBRATE and keeps every other permission', () => {
    const manifest = {
      'uses-permission': [
        { $: { 'android:name': 'android.permission.INTERNET' } },
        { $: { 'android:name': 'android.permission.SYSTEM_ALERT_WINDOW' } },
        { $: { 'android:name': 'android.permission.VIBRATE' } },
        { $: { 'android:name': 'android.permission.USE_BIOMETRIC' } },
      ],
    };
    removeMainManifestPermissions(manifest);
    expect(manifest['uses-permission'].map((entry) => entry.$['android:name'])).toEqual([
      'android.permission.INTERNET',
      'android.permission.USE_BIOMETRIC',
    ]);
  });

  it('tolerates a manifest without uses-permission', () => {
    const manifest: { 'uses-permission'?: never[] } = {};
    expect(() => removeMainManifestPermissions(manifest)).not.toThrow();
  });
});
