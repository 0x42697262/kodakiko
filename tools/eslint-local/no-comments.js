const ALLOWED_PREFIXES = ['eslint-disable', 'eslint-enable', '@ts-expect-error'];
const LICENSE_TAG = '@license';
const DOC_BLOCK_MARKER = '*';

function isAllowed(comment) {
  const text = comment.value.trim();
  if (ALLOWED_PREFIXES.some((prefix) => text.startsWith(prefix))) {
    return true;
  }
  return (
    comment.type === 'Block' &&
    comment.value.startsWith(DOC_BLOCK_MARKER) &&
    comment.value.slice(DOC_BLOCK_MARKER.length).trim().startsWith(LICENSE_TAG)
  );
}

export default {
  meta: {
    type: 'problem',
    messages: {
      noComments: 'Explanatory comments are forbidden; write docs/ instead (guidelines 4.1)',
    },
    schema: [],
  },
  create(context) {
    return {
      'Program:exit'() {
        for (const comment of context.sourceCode.getAllComments()) {
          if (!isAllowed(comment)) {
            context.report({ loc: comment.loc, messageId: 'noComments' });
          }
        }
      },
    };
  },
};
