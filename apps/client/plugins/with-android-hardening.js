import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { withAndroidManifest, withDangerousMod } from 'expo/config-plugins';

const XML_HEADER = '<?xml version="1.0" encoding="utf-8"?>';
const BACKUP_DOMAINS = [
  'root',
  'file',
  'database',
  'sharedpref',
  'external',
  'device_root',
  'device_file',
  'device_database',
  'device_sharedpref',
];
const DEBUG_CLEARTEXT_HOSTS = ['localhost', '127.0.0.1', '10.0.2.2'];
const MAIN_XML_DIRECTORY = ['app', 'src', 'main', 'res', 'xml'];
const DEBUG_XML_DIRECTORY = ['app', 'src', 'debug', 'res', 'xml'];
const REMOVED_MAIN_PERMISSIONS = [
  'android.permission.SYSTEM_ALERT_WINDOW',
  'android.permission.VIBRATE',
];
const DATA_EXTRACTION_RULES_FILE = 'data_extraction_rules.xml';
const NETWORK_SECURITY_CONFIG_FILE = 'network_security_config.xml';

function excludeAll(section) {
  const excludes = BACKUP_DOMAINS.map(
    (domain) => `    <exclude domain="${domain}" path="." />`,
  ).join('\n');
  return `  <${section}>\n${excludes}\n  </${section}>`;
}

export function buildDataExtractionRules() {
  return `${XML_HEADER}\n<data-extraction-rules>\n${excludeAll('cloud-backup')}\n${excludeAll('device-transfer')}\n</data-extraction-rules>\n`;
}

const BASE_CONFIG = [
  '  <base-config cleartextTrafficPermitted="false">',
  '    <trust-anchors>',
  '      <certificates src="system" />',
  '    </trust-anchors>',
  '  </base-config>',
].join('\n');

const DEBUG_OVERRIDES = [
  '  <debug-overrides>',
  '    <trust-anchors>',
  '      <certificates src="user" />',
  '    </trust-anchors>',
  '  </debug-overrides>',
].join('\n');

function debugDomainConfig() {
  const domains = DEBUG_CLEARTEXT_HOSTS.map(
    (host) => `    <domain includeSubdomains="false">${host}</domain>`,
  ).join('\n');
  return `  <domain-config cleartextTrafficPermitted="true">\n${domains}\n  </domain-config>`;
}

export function buildNetworkSecurityConfig() {
  return `${XML_HEADER}\n<network-security-config>\n${BASE_CONFIG}\n${DEBUG_OVERRIDES}\n</network-security-config>\n`;
}

export function buildDebugNetworkSecurityConfig() {
  return `${XML_HEADER}\n<network-security-config>\n${BASE_CONFIG}\n${debugDomainConfig()}\n${DEBUG_OVERRIDES}\n</network-security-config>\n`;
}

function writeXml(directory, fileName, content) {
  mkdirSync(directory, { recursive: true });
  writeFileSync(join(directory, fileName), content);
}

export function removeMainManifestPermissions(manifest) {
  const permissions = manifest['uses-permission'];
  if (permissions === undefined) return;
  manifest['uses-permission'] = permissions.filter(
    (entry) => !REMOVED_MAIN_PERMISSIONS.includes(entry.$['android:name']),
  );
}

function withManifestHardening(config) {
  return withAndroidManifest(config, (manifestConfig) => {
    removeMainManifestPermissions(manifestConfig.modResults.manifest);
    const [application] = manifestConfig.modResults.manifest.application;
    application.$['android:usesCleartextTraffic'] = 'false';
    application.$['android:fullBackupContent'] = 'false';
    application.$['android:dataExtractionRules'] = '@xml/data_extraction_rules';
    application.$['android:networkSecurityConfig'] = '@xml/network_security_config';
    return manifestConfig;
  });
}

function withHardeningFiles(config) {
  return withDangerousMod(config, [
    'android',
    (fileConfig) => {
      const root = fileConfig.modRequest.platformProjectRoot;
      const mainDirectory = join(root, ...MAIN_XML_DIRECTORY);
      writeXml(mainDirectory, DATA_EXTRACTION_RULES_FILE, buildDataExtractionRules());
      writeXml(mainDirectory, NETWORK_SECURITY_CONFIG_FILE, buildNetworkSecurityConfig());
      writeXml(
        join(root, ...DEBUG_XML_DIRECTORY),
        NETWORK_SECURITY_CONFIG_FILE,
        buildDebugNetworkSecurityConfig(),
      );
      return fileConfig;
    },
  ]);
}

export default function withAndroidHardening(config) {
  return withHardeningFiles(withManifestHardening(config));
}
