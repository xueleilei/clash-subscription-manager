import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { EditorView } from 'codemirror'

export const editorTheme = [
  EditorView.theme({
    '&': { background: 'var(--field)', color: 'var(--text)', fontSize: '13px' },
    '&.cm-focused': { outline: '2px solid var(--accent)', outlineOffset: '-2px' },
    '.cm-content': { caretColor: 'var(--accent)', fontFamily: 'var(--mono)' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--accent)' },
    '.cm-gutters, .cm-panels, .cm-tooltip': { backgroundColor: 'var(--surface)', color: 'var(--muted)', borderColor: 'var(--line)' },
    '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#172c3c' },
    '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#2c506b' },
    '.cm-searchMatch': { backgroundColor: '#5a4725' },
    '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: '#755a2b' },
  }, { dark: true }),
  syntaxHighlighting(HighlightStyle.define([
    { tag: [tags.keyword, tags.propertyName], color: '#9ec9ff', fontWeight: '600' },
    { tag: tags.string, color: '#efc68f' },
    { tag: tags.variableName, color: '#79e4d8' },
    { tag: [tags.modifier, tags.bool, tags.number, tags.null], color: '#d5acfb' },
    { tag: tags.comment, color: '#99b2b3', fontStyle: 'italic' },
    { tag: [tags.punctuation, tags.meta], color: '#9daec5' },
  ])),
]
