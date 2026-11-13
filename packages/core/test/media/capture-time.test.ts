import { describe, expect, it } from 'vitest';
import { deviceOffsetMinutes, earliestTime, resolveCaptureTime } from '../../src/index.ts';

const NOW = '2026-11-12T10:00:00.000Z';

const BASE = {
  exifDateTimeOriginal: null,
  exifOffset: null,
  containerCreationUtc: null,
  fileModifiedUtc: null,
  nowUtc: NOW,
  deviceOffsetMinutes: 60,
} as const;

describe('resolveCaptureTime', () => {
  it('prefers EXIF with its own offset over every other source', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '2026:05:01 10:00:00',
        exifOffset: '+02:00',
        containerCreationUtc: '2026-04-01T00:00:00.000Z',
        fileModifiedUtc: '2026-03-01T00:00:00.000Z',
      }),
    ).toEqual({ capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 120, source: 'exif' });
  });

  it('reads EXIF without an offset as local time at the device offset', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '2026:05:01 10:00:00',
        deviceOffsetMinutes: -300,
      }),
    ).toEqual({ capturedAtUtc: '2026-05-01T15:00:00.000Z', utcOffsetMinutes: -300, source: 'exif' });
  });

  it('handles a negative EXIF offset with a half hour', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '2026:05:01 10:00:00',
        exifOffset: '-03:30',
      }),
    ).toEqual({ capturedAtUtc: '2026-05-01T13:30:00.000Z', utcOffsetMinutes: -210, source: 'exif' });
  });

  it('rolls the date over when the offset moves the time across midnight', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '2026:12:31 23:30:00',
        exifOffset: '-02:00',
      }),
    ).toEqual({ capturedAtUtc: '2027-01-01T01:30:00.000Z', utcOffsetMinutes: -120, source: 'exif' });
  });

  it('falls back to the device offset when the EXIF offset is not valid', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '2026:05:01 10:00:00',
        exifOffset: 'bogus',
        deviceOffsetMinutes: 120,
      }),
    ).toEqual({ capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 120, source: 'exif' });
  });

  it('uses the container creation time (UTC, device offset) when EXIF is not valid', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: 'garbage',
        containerCreationUtc: '2026-05-01T08:00:00.000Z',
        fileModifiedUtc: '2026-03-01T00:00:00.000Z',
      }),
    ).toEqual({ capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 60, source: 'container' });
  });

  it('treats the zero EXIF date as missing', () => {
    expect(
      resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '0000:00:00 00:00:00',
        fileModifiedUtc: '2026-03-01T00:00:00.000Z',
      }),
    ).toEqual({ capturedAtUtc: '2026-03-01T00:00:00.000Z', utcOffsetMinutes: 60, source: 'file' });
  });

  it('uses the file modified time when nothing is embedded', () => {
    expect(resolveCaptureTime({ ...BASE, fileModifiedUtc: '2026-03-01T00:00:00.000Z' })).toEqual({
      capturedAtUtc: '2026-03-01T00:00:00.000Z',
      utcOffsetMinutes: 60,
      source: 'file',
    });
  });

  it('uses now when no other source exists', () => {
    expect(resolveCaptureTime(BASE)).toEqual({
      capturedAtUtc: NOW,
      utcOffsetMinutes: 60,
      source: 'now',
    });
  });
});

describe('resolveCaptureTime offset limits', () => {
  it.each(['+14:00', '-14:00'])('accepts the EXIF offset %s at the limit', (exifOffset) => {
    const result = resolveCaptureTime({
      ...BASE,
      exifDateTimeOriginal: '2026:05:01 10:00:00',
      exifOffset,
    });

    expect(Math.abs(result.utcOffsetMinutes)).toBe(840);
  });

  it.each(['+14:01', '-14:01', '+99:00', '-23:59'])(
    'uses the device offset for the out-of-range EXIF offset %s',
    (exifOffset) => {
      const result = resolveCaptureTime({
        ...BASE,
        exifDateTimeOriginal: '2026:05:01 10:00:00',
        exifOffset,
        deviceOffsetMinutes: 120,
      });

      expect(result).toEqual({
        capturedAtUtc: '2026-05-01T08:00:00.000Z',
        utcOffsetMinutes: 120,
        source: 'exif',
      });
    },
  );
});

describe('earliestTime', () => {
  it('picks the time with the smallest capturedAtUtc', () => {
    const times = [
      { capturedAtUtc: '2026-05-02T08:00:00.000Z', utcOffsetMinutes: 0, source: 'exif' },
      { capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 120, source: 'container' },
      { capturedAtUtc: '2026-05-03T08:00:00.000Z', utcOffsetMinutes: 60, source: 'now' },
    ] as const;
    expect(earliestTime(times)).toEqual(times[1]);
  });

  it('returns the only time of a single-item list', () => {
    const only = { capturedAtUtc: NOW, utcOffsetMinutes: 60, source: 'now' } as const;
    expect(earliestTime([only])).toEqual(only);
  });
});

describe('deviceOffsetMinutes', () => {
  it('is the negated timezone offset of the date', () => {
    expect(deviceOffsetMinutes({ getTimezoneOffset: () => -120 } as Date)).toBe(120);
    expect(deviceOffsetMinutes({ getTimezoneOffset: () => 300 } as Date)).toBe(-300);
  });
});
