export type ElementOptions = {
  readonly testId?: string;
  readonly className?: string;
  readonly text?: string;
  readonly attributes?: Readonly<Record<string, string>>;
  readonly children?: readonly Node[];
};

export function createElement<Tag extends keyof HTMLElementTagNameMap>(
  tag: Tag,
  options: ElementOptions = {},
): HTMLElementTagNameMap[Tag] {
  const element = document.createElement(tag);
  if (options.testId !== undefined) element.dataset['testid'] = options.testId;
  if (options.className !== undefined) element.className = options.className;
  if (options.text !== undefined) element.textContent = options.text;
  for (const [name, value] of Object.entries(options.attributes ?? {}))
    element.setAttribute(name, value);
  for (const child of options.children ?? []) element.append(child);
  return element;
}

export function createButton(
  label: string,
  testId: string,
  className: string,
  onClick: () => void,
): HTMLButtonElement {
  const button = createElement('button', {
    testId,
    className,
    text: label,
    attributes: { type: 'button' },
  });
  button.addEventListener('click', onClick);
  return button;
}
