<script setup lang="ts">
import { markRaw, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { basicSetup, EditorView } from 'codemirror'
const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = shallowRef<HTMLDivElement | null>(null)
let editor: EditorView | null = null, applyingExternal = false
onMounted(() => {
 editor = markRaw(new EditorView({ parent: host.value!, doc: props.modelValue, extensions: [
  basicSetup,
  EditorView.contentAttributes.of({ 'aria-label': '完整规则文本' }),
  EditorView.updateListener.of(update => { if (update.docChanged && !applyingExternal) emit('update:modelValue', update.state.doc.toString()) }),
  EditorView.theme({
   '&': { fontSize: '13px', height: 'min(58vh, 680px)', minHeight: '260px' },
   '.cm-scroller': { overflow: 'auto' },
   '.cm-content': { fontFamily: 'ui-monospace, SFMono-Regular, monospace' },
   '&.cm-focused': { outline: '2px solid #278b7c' },
   '.cm-search': { display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '6px', padding: '8px 30px 8px 8px' },
   '.cm-search br': { display: 'none' },
   '.cm-search input[type=text]': { width: '170px', maxWidth: '100%', flex: '1 1 170px', padding: '5px 8px', borderRadius: '4px' },
   '.cm-search label': { display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', margin: '0' },
   '.cm-search input[type=checkbox]': { width: 'auto', margin: '0' },
   '.cm-search button': { padding: '4px 8px', border: '1px solid var(--line)', borderRadius: '4px', background: 'white', color: 'var(--text)', cursor: 'pointer' },
  }),
 ] }))
})
watch(() => props.modelValue, value => {
 if (!editor || editor.state.doc.toString() === value) return
 applyingExternal = true
 try { editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } }) }
 finally { applyingExternal = false }
})
onBeforeUnmount(() => { editor?.destroy(); editor = null })
</script>
<template><div ref="host" class="rule-text-code"></div></template>
<style scoped>.rule-text-code{min-width:0;max-width:100%;overflow:hidden;border:1px solid var(--line);border-radius:8px}</style>
