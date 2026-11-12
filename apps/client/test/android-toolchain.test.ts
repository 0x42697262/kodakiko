import { describe, expect, it } from 'vitest';
import {
  ANDROID_CMAKE_VERSION,
  ANDROID_NDK_VERSION,
  addNdkVersion,
  setCmakeVersion,
} from '../plugins/with-android-toolchain.js';

const BUILD_GRADLE = `buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath('com.android.tools.build:gradle')
    }
}

allprojects {
    repositories {
        google()
    }
}
`;

describe('android toolchain plugin: NDK version', () => {
  it('pins the documented NDK and CMake versions', () => {
    expect(ANDROID_NDK_VERSION).toBe('27.0.12077973');
    expect(ANDROID_CMAKE_VERSION).toBe('3.22.1');
  });

  it('inserts an ext block with the NDK version inside buildscript', () => {
    const patched = addNdkVersion(BUILD_GRADLE);
    const buildscript = patched.slice(0, patched.indexOf('allprojects'));
    expect(buildscript).toContain(`ndkVersion = "${ANDROID_NDK_VERSION}"`);
    expect(patched.indexOf('ext {')).toBeGreaterThan(patched.indexOf('buildscript {'));
    expect(patched.indexOf('ext {')).toBeLessThan(patched.indexOf('repositories'));
    expect(patched.match(/ndkVersion/g)).toHaveLength(1);
  });

  it('is idempotent on a second run', () => {
    const once = addNdkVersion(BUILD_GRADLE);
    expect(addNdkVersion(once)).toBe(once);
  });

  it('keeps an ndkVersion that is already there', () => {
    const existing = BUILD_GRADLE.replace(
      'buildscript {',
      'buildscript {\n    ext {\n        ndkVersion = "26.1.10909125"\n    }',
    );
    expect(addNdkVersion(existing)).toBe(existing);
  });
});

describe('android toolchain plugin: CMake version', () => {
  it('adds the property to a properties list and keeps the other entries', () => {
    const items = [
      { type: 'comment', value: 'Project-wide settings' },
      { type: 'property', key: 'org.gradle.jvmargs', value: '-Xmx2048m' },
    ] as const;
    const patched = setCmakeVersion(items);
    expect(patched).toContainEqual({
      type: 'property',
      key: 'android.cmakeVersion',
      value: ANDROID_CMAKE_VERSION,
    });
    expect(patched.slice(0, items.length)).toEqual(items);
  });

  it('replaces a different value and stays idempotent', () => {
    const items = [{ type: 'property', key: 'android.cmakeVersion', value: '4.0.0' }] as const;
    const once = setCmakeVersion(items);
    expect(once).toEqual([
      { type: 'property', key: 'android.cmakeVersion', value: ANDROID_CMAKE_VERSION },
    ]);
    expect(setCmakeVersion(once)).toEqual(once);
  });
});
