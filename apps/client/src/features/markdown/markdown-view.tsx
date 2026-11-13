import { Fragment, useMemo, useState, type ReactNode } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { BORDER_WIDTH, FONT_FAMILY_MONO, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { QUOTE_BORDER_WIDTH } from './constants';
import { parseMarkdown, type Block, type Inline } from './parse';

const MARKDOWN_IDS = Object.freeze({ view: 'markdown.view', link: 'markdown.link', linkSheet: 'markdown.linkSheet', linkUrl: 'markdown.linkUrl' });
const HEADING_SIZES: readonly number[] = Object.freeze([FONT_SIZE.title, FONT_SIZE.headline, FONT_SIZE.body]);

type Colors = ReturnType<typeof useTheme>['colors'];

function renderInlines(inlines: readonly Inline[], colors: Colors, onLink: (href: string) => void): ReactNode[] {
  return inlines.map((inline, position) => {
    switch (inline.type) {
      case 'text':
        return <Fragment key={position}>{inline.text}</Fragment>;
      case 'break':
        return <Fragment key={position}>{'\n'}</Fragment>;
      case 'code':
        return (
          <Text key={position} style={[styles.inlineCode, { backgroundColor: colors.surface }]}>
            {inline.text}
          </Text>
        );
      case 'strong':
        return (
          <Text key={position} style={styles.strong}>
            {renderInlines(inline.children, colors, onLink)}
          </Text>
        );
      case 'em':
        return (
          <Text key={position} style={styles.em}>
            {renderInlines(inline.children, colors, onLink)}
          </Text>
        );
      case 'del':
        return (
          <Text key={position} style={styles.del}>
            {renderInlines(inline.children, colors, onLink)}
          </Text>
        );
      case 'link': {
        const children = renderInlines(inline.children, colors, onLink);
        const href = inline.href;
        if (href === null) return <Fragment key={position}>{children}</Fragment>;
        return (
          <Text
            key={position}
            testID={MARKDOWN_IDS.link}
            accessibilityRole="link"
            onPress={() => onLink(href)}
            style={[styles.link, { color: colors.primary }]}
          >
            {children}
          </Text>
        );
      }
    }
  });
}

function BlockView({ block, depth, onLink }: { readonly block: Block; readonly depth: number; readonly onLink: (href: string) => void }) {
  const { colors } = useTheme();
  switch (block.type) {
    case 'heading':
      return (
        <Text accessibilityRole="header" style={[styles.heading, { color: colors.text, fontSize: HEADING_SIZES[block.level - 1] ?? FONT_SIZE.body }]}>
          {renderInlines(block.children, colors, onLink)}
        </Text>
      );
    case 'paragraph':
      return <Text style={[styles.paragraph, { color: colors.text }]}>{renderInlines(block.children, colors, onLink)}</Text>;
    case 'code':
      return (
        <View style={[styles.codeBlock, { backgroundColor: colors.surface }]}>
          <Text style={[styles.mono, { color: colors.text }]}>{block.text}</Text>
        </View>
      );
    case 'quote':
      return (
        <View style={[styles.quote, { borderLeftColor: colors.border }]}>
          {block.blocks.map((child, position) => (
            <BlockView key={position} block={child} depth={depth + 1} onLink={onLink} />
          ))}
        </View>
      );
    case 'rule':
      return <View style={[styles.rule, { backgroundColor: colors.border }]} />;
    case 'list':
      return (
        <View style={{ paddingLeft: depth === 0 ? 0 : SPACING.lg }}>
          {block.items.map((item, position) => {
            const marker =
              item.checked === true
                ? EN['markdown.taskDone']
                : item.checked === false
                  ? EN['markdown.taskOpen']
                  : block.ordered
                    ? EN['markdown.ordered'](block.start + position)
                    : EN['markdown.bullet'];
            return (
              <View key={position} style={styles.item}>
                <Text style={[styles.marker, { color: colors.text }]}>{marker}</Text>
                <View style={styles.itemBody}>
                  {item.blocks.map((child, childPosition) => (
                    <BlockView key={childPosition} block={child} depth={depth + 1} onLink={onLink} />
                  ))}
                </View>
              </View>
            );
          })}
        </View>
      );
  }
}

export function MarkdownView({ source }: { readonly source: string }) {
  const { colors } = useTheme();
  const blocks = useMemo(() => parseMarkdown(source), [source]);
  const [pendingHref, setPendingHref] = useState<string | null>(null);
  if (blocks.length === 0) return null;
  return (
    <View testID={MARKDOWN_IDS.view} style={styles.view}>
      {blocks.map((block, position) => (
        <View key={position} testID={listTestId('markdown.block', position)}>
          <BlockView block={block} depth={0} onLink={setPendingHref} />
        </View>
      ))}
      {pendingHref === null ? null : (
        <Sheet testID={MARKDOWN_IDS.linkSheet} onClose={() => setPendingHref(null)}>
          <Text style={[styles.heading, { color: colors.text, fontSize: FONT_SIZE.headline }]}>
            {EN['markdown.openLinkTitle']}
          </Text>
          <Text testID={MARKDOWN_IDS.linkUrl} selectable style={{ color: colors.text }}>
            {pendingHref}
          </Text>
          <Button
            testID={testId('markdown.openLink')}
            label={EN['markdown.openLink']}
            onPress={() => {
              const href = pendingHref;
              setPendingHref(null);
              Linking.openURL(href).catch(() => undefined);
            }}
          />
          <Button
            testID={testId('markdown.cancel')}
            label={EN['markdown.cancel']}
            variant="secondary"
            onPress={() => setPendingHref(null)}
          />
        </Sheet>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  view: { gap: SPACING.sm },
  heading: { fontWeight: '700' },
  paragraph: { fontSize: FONT_SIZE.body },
  strong: { fontWeight: '700' },
  em: { fontStyle: 'italic' },
  del: { textDecorationLine: 'line-through' },
  link: { textDecorationLine: 'underline' },
  inlineCode: { fontFamily: FONT_FAMILY_MONO },
  mono: { fontFamily: FONT_FAMILY_MONO, fontSize: FONT_SIZE.caption },
  codeBlock: { padding: SPACING.sm, borderRadius: RADIUS.sm },
  quote: { borderLeftWidth: QUOTE_BORDER_WIDTH, paddingLeft: SPACING.md, gap: SPACING.xs },
  rule: { height: BORDER_WIDTH },
  item: { flexDirection: 'row', gap: SPACING.sm },
  marker: { fontSize: FONT_SIZE.body },
  itemBody: { flex: 1, gap: SPACING.xs },
});
