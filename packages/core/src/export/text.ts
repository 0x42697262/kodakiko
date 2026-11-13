export const EXPORT_TEXT = Object.freeze({
  readme: (date: string) =>
    [
      `Kodakiko journal export, ${date}.`,
      '',
      'Each entry is a folder named YEAR/MONTH/DATE_TIME_ID/. It holds entry.md (the note, with the',
      'date, tags, location and media list at the top) and the photos and videos numbered 001, 002, ...',
      '',
      'These files are NOT encrypted. Store them somewhere safe.',
      '',
    ].join('\n'),
  missingHeader: 'These items could not be exported:',
  missingLine: (folder: string, file: string, reason: string) => `${folder}${file}\t${reason}`,
});
