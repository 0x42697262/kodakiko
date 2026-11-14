import type { ImportFile } from '../../services/import/journal-import';

const ACCEPT = '.zip,.kdkx,application/zip';

export function pickImportFiles(): Promise<readonly ImportFile[] | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.accept = ACCEPT;
    input.setAttribute('data-testid', 'import.fileInput');
    input.style.display = 'none';
    input.addEventListener('change', () => {
      const files = [...(input.files ?? [])];
      input.remove();
      if (files.length === 0) {
        resolve(null);
        return;
      }
      Promise.all(files.map(async (file) => ({ name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) })))
        .then(resolve)
        .catch(() => resolve(null));
    });
    input.addEventListener('cancel', () => {
      input.remove();
      resolve(null);
    });
    document.body.appendChild(input);
    input.click();
  });
}
