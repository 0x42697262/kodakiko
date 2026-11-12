import { withGradleProperties, withProjectBuildGradle } from 'expo/config-plugins';

export const ANDROID_NDK_VERSION = '27.0.12077973';
export const ANDROID_CMAKE_VERSION = '3.22.1';

const CMAKE_PROPERTY_KEY = 'android.cmakeVersion';
const BUILDSCRIPT_OPENING = /buildscript\s*\{/;
const NDK_VERSION_ASSIGNMENT = /\bndkVersion\s*=/;
const GROOVY_LANGUAGE = 'groovy';

export function addNdkVersion(contents) {
  if (NDK_VERSION_ASSIGNMENT.test(contents)) return contents;
  const block = `\n    ext {\n        ndkVersion = "${ANDROID_NDK_VERSION}"\n    }`;
  return contents.replace(BUILDSCRIPT_OPENING, (opening) => `${opening}${block}`);
}

export function setCmakeVersion(items) {
  const property = { type: 'property', key: CMAKE_PROPERTY_KEY, value: ANDROID_CMAKE_VERSION };
  const others = items.filter(
    (item) => item.type !== 'property' || item.key !== CMAKE_PROPERTY_KEY,
  );
  const firstIndex = items.findIndex(
    (item) => item.type === 'property' && item.key === CMAKE_PROPERTY_KEY,
  );
  if (firstIndex === -1) return [...others, property];
  const before = items
    .slice(0, firstIndex)
    .filter((item) => item.type !== 'property' || item.key !== CMAKE_PROPERTY_KEY);
  return [...before, property, ...others.slice(before.length)];
}

export default function withAndroidToolchain(config) {
  const withBuildGradle = withProjectBuildGradle(config, (gradleConfig) => {
    if (gradleConfig.modResults.language === GROOVY_LANGUAGE) {
      gradleConfig.modResults.contents = addNdkVersion(gradleConfig.modResults.contents);
    }
    return gradleConfig;
  });
  return withGradleProperties(withBuildGradle, (propertiesConfig) => {
    propertiesConfig.modResults = setCmakeVersion(propertiesConfig.modResults);
    return propertiesConfig;
  });
}
