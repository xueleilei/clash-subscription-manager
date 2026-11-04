import { StreamLanguage } from '@codemirror/language'

type Style = 'keyword' | 'string' | 'variableName' | 'modifier' | 'comment' | 'punctuation'
interface Span { from: number; to: number; style: Style }
interface State { spans: Span[]; index: number }

// Each rule is one line. Highlighting never rewrites text or decides whether a rule is valid.
function lineSpans(line: string): Span[] {
 const spans: Span[] = []
 let start = line.search(/\S/)
 if (start < 0) return spans
 if (line[start] === '#') return [{ from: start, to: line.length, style: 'comment' }]
 const dash = /^-\s+/.exec(line.slice(start))
 if (dash) { spans.push({ from: start, to: start + 1, style: 'punctuation' }); start += dash[0].length }

 const starts: number[] = [], ends: number[] = []
 let content = '', literal = false
 const outerQuote = line[start]
 if (dash && (outerQuote === "'" || outerQuote === '"')) {
  literal = true
  spans.push({ from: start, to: start + 1, style: 'punctuation' })
  let index = start + 1
  while (index < line.length) {
   const from = index, character = line[index]!
   if (character === outerQuote) {
    if (outerQuote === "'" && line[index + 1] === "'") { content += "'"; starts.push(from); ends.push(index + 2); index += 2; continue }
    spans.push({ from: index, to: index + 1, style: 'punctuation' })
    const comment = /^\s*(#.*)$/.exec(line.slice(index + 1))
    if (comment) spans.push({ from: line.indexOf('#', index + 1), to: line.length, style: 'comment' })
    break
   }
   if (outerQuote === '"' && character === '\\' && index + 1 < line.length) {
    content += line[index + 1]; starts.push(from); ends.push(index + 2); index += 2
   } else { content += character; starts.push(from); ends.push(++index) }
  }
 } else {
  content = line.slice(start)
  for (let index = 0; index < content.length; index++) { starts.push(start + index); ends.push(start + index + 1) }
 }

 let quote = '', depth = 0, escaped = false, end = content.length
 let commas: number[] = []
 for (let index = 0; index < content.length; index++) {
  const character = content[index]!
  if (escaped) { escaped = false; continue }
  if (character === '\\') { escaped = true; continue }
  if (quote) {
   if (character === quote) { if (content[index + 1] === quote) index++; else quote = '' }
   continue
  }
  if (character === "'" || character === '"') { quote = character; continue }
  if (!literal && character === '#' && (index === 0 || /\s/.test(content[index - 1]!))) {
   end = index; spans.push({ from: starts[index]!, to: line.length, style: 'comment' }); break
  }
  if (character === '(') depth++
  else if (character === ')') depth = Math.max(0, depth - 1)
  else if (character === ',' && depth === 0) commas.push(index)
 }
 const type = content.slice(0, commas[0] ?? end).trim().toUpperCase()
 if (['DOMAIN-REGEX', 'PROCESS-NAME-REGEX', 'PROCESS-PATH-REGEX'].includes(type) && commas.length) {
  const last = content.lastIndexOf(',', end - 1)
  commas = last > commas[0]! ? [commas[0]!, last] : [commas[0]!]
 }
 const boundaries = [-1, ...commas, end], target = type === 'MATCH' ? 1 : 2
 for (let field = 0; field < boundaries.length - 1; field++) {
  let from = boundaries[field]! + 1, to = boundaries[field + 1]!
  while (from < to && /\s/.test(content[from]!)) from++
  while (to > from && /\s/.test(content[to - 1]!)) to--
  if (from < to) spans.push({ from: starts[from]!, to: ends[to - 1]!, style: field === 0 ? 'keyword' : field === target ? 'variableName' : field > target ? 'modifier' : 'string' })
 }
 for (const index of commas) spans.push({ from: starts[index]!, to: ends[index]!, style: 'punctuation' })
 return spans.sort((a, b) => a.from - b.from)
}

export const clashRuleLanguage = StreamLanguage.define<State>({
 name: 'Clash rules',
 startState: () => ({ spans: [], index: 0 }),
 token(stream, state) {
  if (stream.sol()) { state.spans = lineSpans(stream.string); state.index = 0 }
  while (state.spans[state.index] && state.spans[state.index]!.to <= stream.pos) state.index++
  const span = state.spans[state.index]
  if (!span) { stream.skipToEnd(); return null }
  if (stream.pos < span.from) { stream.pos = span.from; return null }
  stream.pos = span.to; state.index++; return span.style
 },
 languageData: { commentTokens: { line: '#' } },
})
