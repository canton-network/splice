#!/usr/bin/env node

// generateOutputDocs.js
//
// - Reads a single export config (default path varies by repo; override with --export-config)
// - Writes extracted snippets into docs-output/<snippetName>.mdx (override with --output)
// - Resolves source files relative to the repo root (override with --repo-root)
//
// Optional CLI overrides:
//   --repo-root <path>
//   --export-config <path>
//   --output <path>
//   --verbose

const fs = require('fs')
const path = require('path')

const DEFAULT_REPO_ROOT = path.join(__dirname, '..', '..')
const DEFAULT_EXPORT_CONFIG_PATH = path.join(__dirname, 'exportConfig.json')
const DEFAULT_OUTPUT_FOLDER_PATH = path.join(DEFAULT_REPO_ROOT, 'docs-output')

function printUsage() {
    console.log(`Usage: node generateOutputDocs.js [options]

Options:
  --repo-root <path>       Repository root for resolving snippet source files
                           (default: ${DEFAULT_REPO_ROOT})
  --export-config <path>   Export config JSON file
                           (default: ${DEFAULT_EXPORT_CONFIG_PATH})
  --output <path>          Output directory for generated .mdx snippets
                           (default: ${DEFAULT_OUTPUT_FOLDER_PATH})
  --verbose                Log each snippet as it is processed
  -h, --help               Show this help message
`)
}

function parseCli(argv) {
    let repoRoot = DEFAULT_REPO_ROOT
    let exportConfigPath = DEFAULT_EXPORT_CONFIG_PATH
    let outputFolderPath = DEFAULT_OUTPUT_FOLDER_PATH
    let verbose = false

    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i]
        if (arg === '--verbose') {
            verbose = true
            continue
        }
        if (arg === '--help' || arg === '-h') {
            printUsage()
            process.exit(0)
        }
        if (arg === '--repo-root') {
            const value = argv[++i]
            if (!value) {
                throw new Error('--repo-root requires a path argument')
            }
            repoRoot = path.resolve(value)
            continue
        }
        if (arg === '--export-config') {
            const value = argv[++i]
            if (!value) {
                throw new Error('--export-config requires a path argument')
            }
            exportConfigPath = path.resolve(value)
            continue
        }
        if (arg === '--output') {
            const value = argv[++i]
            if (!value) {
                throw new Error('--output requires a path argument')
            }
            outputFolderPath = path.resolve(value)
            continue
        }
        throw new Error(`Unknown argument: ${arg}`)
    }

    return {
        repoRoot,
        exportConfigPath,
        outputFolderPath,
        verbose,
    }
}

function readFileContent(filePath) {
    try {
        return fs.readFileSync(filePath, 'utf8')
    } catch (error) {
        throw new Error(`Failed to read file ${filePath}: ${error.message}`)
    }
}

function extractByLines(fileContent, start, end) {
    const lines = fileContent.split(/\r?\n/)
    const startLine = Number(start)
    const endLine = Number(end)

    if (
        startLine < 1 ||
        endLine < 1 ||
        startLine > lines.length ||
        endLine > lines.length
    ) {
        throw new Error(
            `Line numbers out of range: start=${startLine}, end=${endLine}, file has ${lines.length} lines`
        )
    }

    if (startLine > endLine) {
        throw new Error(
            `Invalid line range: start (${startLine}) must be <= end (${endLine})`
        )
    }

    return lines.slice(startLine - 1, endLine).join('\n')
}

function extractByStringMarker(fileContent, startMarker, endMarker) {
    const startIndex = fileContent.indexOf(startMarker)
    if (startIndex === -1) {
        throw new Error(`Start marker not found: "${startMarker}"`)
    }

    // Match Sphinx literalinclude :start-after: / :end-before: — exclude marker lines.
    let contentStart = fileContent.indexOf('\n', startIndex)
    if (contentStart === -1) {
        contentStart = startIndex + startMarker.length
    } else {
        contentStart += 1
    }

    const endIndex = fileContent.indexOf(endMarker, contentStart)
    if (endIndex === -1) {
        throw new Error(`End marker not found: "${endMarker}"`)
    }

    let contentEnd = fileContent.lastIndexOf('\n', endIndex)
    if (contentEnd < contentStart) {
        contentEnd = endIndex
    }

    return fileContent.substring(contentStart, contentEnd).trim()
}

function extractByRegexWrap(fileContent, startRegex, endRegex) {
    const startPattern = new RegExp(startRegex)
    const endPattern = new RegExp(endRegex)

    const startMatch = fileContent.match(startPattern)
    if (!startMatch) {
        throw new Error(`Start regex pattern not found: "${startRegex}"`)
    }

    const contentStart = startMatch.index + startMatch[0].length
    const remainingContent = fileContent.substring(contentStart)
    const endMatch = remainingContent.match(endPattern)

    if (!endMatch) {
        throw new Error(`End regex pattern not found: "${endRegex}"`)
    }

    return remainingContent.substring(0, endMatch.index).trim()
}

function extractByJsonIndex(fileContent, start, end) {
    let arr
    try {
        arr = JSON.parse(fileContent)
    } catch (e) {
        throw new Error(`File is not valid JSON: ${e.message}`)
    }
    if (!Array.isArray(arr)) {
        throw new Error(
            'JSON root must be an array for location type jsonIndex'
        )
    }
    const startIdx = Number(start)
    const endIdx = Number(end)
    if (
        startIdx < 0 ||
        endIdx < 0 ||
        startIdx >= arr.length ||
        endIdx >= arr.length
    ) {
        throw new Error(
            `Array index out of range: start=${startIdx}, end=${endIdx}, array length=${arr.length}`
        )
    }
    if (startIdx > endIdx) {
        throw new Error(
            `Invalid index range: start (${startIdx}) must be <= end (${endIdx})`
        )
    }
    if (startIdx === endIdx) {
        const item = arr[startIdx]
        return typeof item === 'string' ? item : String(item)
    }
    return arr
        .slice(startIdx, endIdx + 1)
        .map((item) => (typeof item === 'string' ? item : String(item)))
        .join('\n')
}

function extractSnippetContent(fileContent, location) {
    switch (location.type) {
        case 'fullFile':
            return fileContent

        case 'lines':
            return extractByLines(fileContent, location.start, location.end)

        case 'jsonIndex':
            return extractByJsonIndex(fileContent, location.start, location.end)

        case 'stringMarker':
            return extractByStringMarker(
                fileContent,
                location.start,
                location.end
            )

        case 'regexWrap':
            return extractByRegexWrap(fileContent, location.start, location.end)

        default:
            throw new Error(`Unknown location type: ${location.type}`)
    }
}

function normalizeIndent(content) {
    const lines = content.split('\n')

    let minIndent = null
    for (const line of lines) {
        if (line.trim() === '') continue
        const match = line.match(/^(\s*)/)
        const indent = match ? match[1].length : 0
        if (minIndent === null || indent < minIndent) {
            minIndent = indent
        }
    }

    // Strip the common leading whitespace from every non-blank line and then
    // re-indent the whole block by two spaces. Using `line.slice(strip)`
    // (instead of stripping ALL leading whitespace) preserves the relative
    // indentation between lines — including the case where minIndent is 0,
    // which would otherwise flatten any source that contains a top-level
    // line at column 0 (e.g. HOCON config files where a `}` closes at the
    // start of the line).
    const strip = minIndent ?? 0
    return lines
        .map((line) => {
            if (line.trim() === '') return ''
            return `  ${line.slice(strip)}`
        })
        .join('\n')
}

/** Strip common leading indent only; first line starts at column 0 (HOCON/config in RST). */
function baselineIndent(content) {
    const lines = content.split('\n')
    let minIndent = null
    for (const line of lines) {
        if (line.trim() === '') continue
        const indent = (line.match(/^(\s*)/) || ['', ''])[1].length
        if (minIndent === null || indent < minIndent) minIndent = indent
    }
    const strip = minIndent ?? 0
    return lines
        .map((line) => (line.trim() === '' ? '' : line.slice(strip)))
        .join('\n')
}

function applyIndentOption(content, normalizeIndentOption) {
    if (normalizeIndentOption === false) return content
    if (normalizeIndentOption === 'baseline') return baselineIndent(content)
    return normalizeIndent(content)
}

/**
 * Resolve indent mode for a snippet. Crawl configs historically set bash to false
 * (preserve RST indent); we want column-0 commands via baseline instead.
 */
function resolveNormalizeIndent(snippet) {
    const opt = snippet.options?.normalizeIndent
    const lang = (snippet.options?.language || '').toLowerCase()
    if (opt === false && (lang === 'bash' || lang === 'parsed-literal')) {
        return 'baseline'
    }
    if (opt !== undefined) return opt
    return defaultNormalizeIndent(snippet.location)
}

/** Default indent mode when options.normalizeIndent is omitted. */
function defaultNormalizeIndent(location) {
    switch (location && location.type) {
        case 'fullFile':
        case 'stringMarker':
            return 'baseline'
        default:
            return true
    }
}

function trimBlankEdges(content) {
    return content.replace(/^\s*\n+/, '').replace(/\n+\s*$/, '')
}

// --- RST include → MDX (options.transform === "rstinclude") ---

function stripLeadingRstComments(lines) {
    let i = 0
    while (i < lines.length) {
        const t = lines[i].trim()
        if (t === '') {
            i++
            continue
        }
        if (/^\.\./.test(t) && !/^\.\.\s+[\w-]+::/.test(t)) {
            i++
            continue
        }
        if (/^Copyright\b/i.test(t) || /^SPDX-License-Identifier:/i.test(t)) {
            i++
            continue
        }
        break
    }
    return lines.slice(i)
}

function dedentBlock(lines) {
    if (lines.length === 0) return []
    const indents = lines
        .filter((l) => l.trim() !== '')
        .map((l) => (l.match(/^(\s*)/) || ['', ''])[1].length)
    const min = indents.length ? Math.min(...indents) : 0
    return lines.map((l) => (l.trim() === '' ? '' : l.slice(min)))
}

function inlineRstMarkup(text, refTargets = {}) {
    let out = text
    out = out.replace(
        /:ref:`([^<]+?)\s*<([^>]+)>`/g,
        (_, label, target) => {
            const href = refTargets[target.trim()]
            return href ? `[${label.trim()}](${href})` : label.trim()
        }
    )
    out = out.replace(/:ref:`([^`]+)`/g, (_, target) => {
        const key = target.trim()
        const href = refTargets[key]
        if (href) {
            const label = key.replace(/_/g, ' ')
            return `[${label}](${href})`
        }
        return key
    })
    out = out.replace(/\*\*([^*]+)\*\*/g, '**$1**')
    out = out.replace(/``([^`]+)``/g, '`$1`')
    return out
}

function fieldListToMarkdown(lines, startIdx, refTargets = {}) {
    const items = []
    let i = startIdx
    while (i < lines.length) {
        const line = lines[i]
        const lt = line.trim()
        if (lt === '') {
            i++
            continue
        }
        if (/^\.\.\s+[\w-]+::/.test(lt)) break
        if (/^\s/.test(line)) break
        if (!/^[A-Z][A-Z0-9_]+$/.test(lt)) break
        const term = lt
        i++
        const body = []
        while (i < lines.length && /^\s+/.test(lines[i])) {
            const bt = lines[i].trim()
            if (bt === '') {
                i++
                continue
            }
            if (/^\.\.\s+/.test(bt)) break
            body.push(bt)
            i++
        }
        const bodyText = inlineRstMarkup(body.join(' '), refTargets)
        const linkified = bodyText
            .replace(
                /form \|generic_sv_url\|/g,
                'form <a href="|generic_sv_url|">|generic_sv_url|</a>'
            )
            .replace(
                /use \|gsf_sv_url\|/g,
                'use <a href="|gsf_sv_url|">|gsf_sv_url|</a>'
            )
        if (term === 'ONBOARDING_SECRET') {
            items.push({
                type: 'paragraph',
                text: `**${term}**\n${linkified}`,
            })
        } else {
            items.push({
                type: 'bullet',
                text: `**${term}** — ${linkified}`,
            })
        }
    }
    return { next: i, items }
}

function readDirectiveBlock(lines, startIdx) {
    const body = []
    let i = startIdx
    while (i < lines.length) {
        const line = lines[i]
        if (line.trim() === '') {
            body.push('')
            i++
            continue
        }
        if (!/^\s/.test(line) && /^\.\.\s+/.test(line)) break
        if (!/^\s/.test(line) && line.trim() !== '' && body.length > 0) break
        body.push(line)
        i++
    }
    return { body: dedentBlock(body), next: i }
}

function convertRstIncludeToMdx(content, options = {}) {
    const refTargets = options.refTargets || {}
    const rawLines = trimBlankEdges(content).split('\n')
    const lines = stripLeadingRstComments(rawLines)
    const out = []
    let i = 0

    while (i < lines.length) {
        const line = lines[i]
        const trimmed = line.trim()

        if (trimmed === '') {
            i++
            continue
        }

        const warning = trimmed.match(/^\.\.\s+warning::\s*$/)
        if (warning) {
            const block = readDirectiveBlock(lines, i + 1)
            const inner = block.body
                .filter((l) => l.trim() !== '')
                .map((l) => inlineRstMarkup(l.trim(), refTargets))
                .join('\n\n')
            out.push(`<Warning>\n\n${inner}\n\n</Warning>`)
            i = block.next
            continue
        }

        const note = trimmed.match(/^\.\.\s+note::\s*$/)
        if (note) {
            const block = readDirectiveBlock(lines, i + 1)
            const inner = block.body
                .filter((l) => l.trim() !== '')
                .map((l) => inlineRstMarkup(l.trim(), refTargets))
                .join('\n\n')
            out.push(`<Note>\n\n${inner}\n\n</Note>`)
            i = block.next
            continue
        }

        const tip = trimmed.match(/^\.\.\s+tip::\s*$/)
        if (tip) {
            const block = readDirectiveBlock(lines, i + 1)
            const inner = block.body
                .filter((l) => l.trim() !== '')
                .map((l) => inlineRstMarkup(l.trim(), refTargets))
                .join('\n\n')
            // Mintlify doesn't have a Tip component; render as Note.
            out.push(`<Note>\n\n${inner}\n\n</Note>`)
            i = block.next
            continue
        }

        const admonition = trimmed.match(/^\.\.\s+admonition::\s*(.+)\s*$/)
        if (admonition) {
            const title = admonition[1].trim()
            const block = readDirectiveBlock(lines, i + 1)
            const parts = []
            let j = 0
            const bodyLines = block.body
            while (j < bodyLines.length) {
                const bl = bodyLines[j].trim()
                if (bl === '') {
                    j++
                    continue
                }
                if (/^\.\.\s+parsed-literal::\s*$/.test(bl)) {
                    j++
                    const codeBlock = readDirectiveBlock(bodyLines, j)
                    const code = codeBlock.body
                        .filter((l) => l.trim() !== '')
                        .join('\n')
                    parts.push('```bash\n' + code + '\n```')
                    j = codeBlock.next
                    continue
                }
                const para = []
                while (j < bodyLines.length && bodyLines[j].trim() !== '') {
                    if (/^\.\.\s+/.test(bodyLines[j].trim())) break
                    para.push(bodyLines[j].trim())
                    j++
                }
                if (para.length) {
                    parts.push(inlineRstMarkup(para.join(' '), refTargets))
                }
            }
            const wrapper =
                title.toLowerCase().includes('devnet') ||
                title.toLowerCase().includes('devnet-only')
                    ? '<div data-network-only="devnet">\n\n'
                    : ''
            const wrapperEnd = wrapper ? '\n\n</div>' : ''
            const inner = parts
                .map((p) =>
                    p.startsWith('```') ? p : `<Note>\n\n${p}\n\n</Note>`
                )
                .join('\n\n')
            out.push(`${wrapper}${inner}${wrapperEnd}`)
            i = block.next
            continue
        }

        const parsedLiteral = trimmed.match(/^\.\.\s+parsed-literal::\s*$/)
        if (parsedLiteral) {
            const block = readDirectiveBlock(lines, i + 1)
            const code = block.body
                .filter((l) => l.trim() !== '')
                .join('\n')
            out.push('```bash\n' + code + '\n```')
            i = block.next
            continue
        }

        const codeBlock = trimmed.match(/^\.\.\s+code(?:-block)?::\s*(\S*)\s*$/)
        if (codeBlock) {
            const lang = codeBlock[1] || 'bash'
            const block = readDirectiveBlock(lines, i + 1)
            const code = block.body
                .filter((l) => l.trim() !== '')
                .join('\n')
            out.push('```' + lang + '\n' + code + '\n```')
            i = block.next
            continue
        }

        if (/^\.\.\s+/.test(trimmed)) {
            i++
            continue
        }

        if (!/^\s/.test(line) && /^[A-Z][A-Z0-9_]+$/.test(trimmed)) {
            const fl = fieldListToMarkdown(lines, i, refTargets)
            let inList = false
            for (const item of fl.items) {
                if (item.type === 'bullet') {
                    if (!inList) {
                        out.push('<ul>')
                        inList = true
                    }
                    out.push(`<li>${item.text}</li>`)
                } else {
                    if (inList) {
                        out.push('</ul>')
                        inList = false
                    }
                    out.push(`<p>${item.text.replace(/\n/g, '<br />\n')}</p>`)
                }
            }
            if (inList) out.push('</ul>')
            i = fl.next
            continue
        }

        if (trimmed.startsWith('- ')) {
            const items = []
            while (i < lines.length) {
                const t = lines[i].trim()
                if (t.startsWith('- ')) {
                    items.push(
                        inlineRstMarkup(t.replace(/^-\s+/, ''), refTargets)
                    )
                    i++
                } else if (t === '') {
                    i++
                    if (
                        i >= lines.length ||
                        (!lines[i].trim().startsWith('- ') &&
                            !/^\s+/.test(lines[i]))
                    ) {
                        break
                    }
                } else if (/^\s/.test(lines[i]) && items.length > 0) {
                    items[items.length - 1] +=
                        ' ' + inlineRstMarkup(t, refTargets)
                    i++
                } else {
                    break
                }
            }
            out.push('<ul>')
            for (const item of items) {
                out.push(`<li>${item}</li>`)
            }
            out.push('</ul>')
            continue
        }

        if (!/^\s/.test(line)) {
            const para = []
            while (i < lines.length) {
                const pl = lines[i]
                const pt = pl.trim()
                if (pt === '') break
                if (/^\.\.\s+/.test(pt)) break
                if (pt.startsWith('- ')) break
                if (!/^\s/.test(pl) && /^[A-Z][A-Z0-9_]+$/.test(pt)) {
                    break
                }
                para.push(pt)
                i++
            }
            if (para.length) {
                out.push(
                    `<p>${inlineRstMarkup(para.join(' '), refTargets)}</p>`
                )
            }
            continue
        }

        i++
    }

    return trimBlankEdges(out.join('\n\n'))
}

/** Replace literal substrings (e.g. legacy docs.daml.com URLs in YAML comments). */
function applyUrlSubstitutions(content, globalSubstitutions, snippetSubstitutions) {
    const merged = {
        ...(globalSubstitutions || {}),
        ...(snippetSubstitutions || {}),
    }
    const keys = Object.keys(merged)
    if (keys.length === 0) return content
    let result = content
    for (const from of keys) {
        result = result.split(from).join(merged[from])
    }
    return result
}

function convertRstBlocksToMarkdown(content, fallbackLanguage = '') {
    const input = trimBlankEdges(content)
    const lines = input.split('\n')
    const out = []
    let i = 0

    while (i < lines.length) {
        const m = lines[i].match(/^\s*\.\.\s+code-block::\s*(\S*)\s*$/)
        if (!m) {
            i++
            continue
        }

        let language = (m[1] || '').trim()
        if (!language || language.toLowerCase() === 'none') {
            language = fallbackLanguage || ''
        }

        i++
        while (i < lines.length && lines[i].trim() === '') i++

        const block = []
        while (i < lines.length) {
            const line = lines[i]
            if (line.trim() === '') {
                block.push('')
                i++
                continue
            }

            if (/^( {4}|\t)/.test(line)) {
                block.push(line.replace(/^( {4}|\t)/, ''))
                i++
                continue
            }
            break
        }

        while (block.length > 0 && block[block.length - 1] === '') {
            block.pop()
        }

        if (language) {
            out.push(`\`\`\`${language}`)
        } else {
            out.push('```')
        }
        out.push(block.join('\n'))
        out.push('```')
        out.push('')
    }

    if (out.length === 0) {
        // Safety fallback: strip any leftover RST directives and keep only content.
        const cleaned = input
            .split('\n')
            .filter((line) => !/^\s*\.\.\s+code-block::/.test(line))
            .join('\n')
        const trimmed = trimBlankEdges(cleaned)
        const language = fallbackLanguage || ''
        if (language) {
            return `\`\`\`${language}\n${trimmed}\n\`\`\``
        }
        return `\`\`\`\n${trimmed}\n\`\`\``
    }

    while (out.length > 0 && out[out.length - 1] === '') out.pop()
    return out.join('\n')
}

function formatSnippetContent(content, options, globalOptions = {}) {
    let body = content
    if (options && options.unescapeRstQuotes) {
        body = body.replace(/\\'/g, "'")
    }
    if (options && options.transform === 'rstinclude') {
        return convertRstIncludeToMdx(body, {
            refTargets: {
                ...(globalOptions.rstIncludeRefTargets || {}),
                ...(options.refTargets || {}),
            },
        })
    }
    if (options && options.transform === 'rstjson') {
        const language = options && options.language ? options.language : ''
        return convertRstBlocksToMarkdown(body, language)
    }
    const displayStyle = (options && options.displayStyle) || 'wrapCode'
    const rawLanguage = options && options.language ? options.language : ''
    const language =
        rawLanguage && rawLanguage.toLowerCase() === 'none' ? '' : rawLanguage

    switch (displayStyle) {
        case 'wrapCode':
            body = trimBlankEdges(body)
            if (language) {
                return `\`\`\`${language}\n${body}\n\`\`\``
            } else {
                return `\`\`\`\n${body}\n\`\`\``
            }

        default:
            return content
    }
}

function getSourceFilePath(snippet, repoRoot) {
    if (snippet.sourceFilepath) {
        return path.join(repoRoot, snippet.sourceFilepath)
    } else {
        throw new Error(
            `Snippet "${snippet.snippetName}" has no source file path specified`
        )
    }
}

function processSnippet(snippet, paths, globalOptions = {}) {
    const { repoRoot, outputFolderPath, verbose } = paths
    try {
        if (verbose) {
            console.log(`Processing snippet: ${snippet.snippetName}`)
        }

        if (!snippet.snippetName) {
            throw new Error('Snippet missing required field: snippetName')
        }

        if (!snippet.location) {
            throw new Error(
                `Snippet "${snippet.snippetName}" missing required field: location`
            )
        }

        const sourceFilePath = getSourceFilePath(snippet, repoRoot)

        const fileContent = readFileContent(sourceFilePath)

        const extractedContent = extractSnippetContent(
            fileContent,
            snippet.location
        )
        const skipTransform =
            snippet.options &&
            (snippet.options.transform === 'rstjson' ||
                snippet.options.transform === 'rstinclude')
        const normalizedContent = skipTransform
            ? extractedContent
            : applyIndentOption(extractedContent, resolveNormalizeIndent(snippet))

        const substitutedContent = applyUrlSubstitutions(
            normalizedContent,
            globalOptions.urlSubstitutions,
            snippet.options && snippet.options.urlSubstitutions
        )

        const formattedContent = formatSnippetContent(
            substitutedContent,
            snippet.options || {},
            globalOptions
        )

        const outputFileName = `${snippet.snippetName}.mdx`
        const outputPath = path.join(outputFolderPath, outputFileName)
        const outputPathDir = path.dirname(outputPath)

        fs.mkdirSync(outputPathDir, { recursive: true })

        fs.writeFileSync(outputPath, formattedContent, 'utf8')

        if (verbose) {
            console.log(`✓ Successfully extracted snippet to: ${outputPath}`)
        }
    } catch (error) {
        console.error(
            `✗ Error processing snippet "${snippet.snippetName}": ${error.message}`
        )
        throw error
    }
}

/**
 * Main function
 * Reads docs/config/exportConfig.json and processes each snippet.
 */
function main() {
    try {
        const paths = parseCli(process.argv.slice(2))
        const configContent = readFileContent(paths.exportConfigPath)
        const config = JSON.parse(configContent)

        if (!config.snippets || !Array.isArray(config.snippets)) {
            throw new Error(
                'exportConfig.json must have a top-level "snippets" array'
            )
        }

        let successCount = 0
        let errorCount = 0

        const globalOptions = {
            rstIncludeRefTargets: config.rstIncludeRefTargets || {},
            urlSubstitutions: config.urlSubstitutions || {},
        }

        for (const snippet of config.snippets) {
            try {
                processSnippet(snippet, paths, globalOptions)
                successCount++
            } catch (error) {
                errorCount++
            }
        }

        console.log(
            `\nProcessing complete: ${successCount} succeeded, ${errorCount} failed`
        )

        if (errorCount > 0) {
            process.exit(1)
        }
    } catch (error) {
        console.error(`Fatal error: ${error.message}`)
        process.exit(1)
    }
}

main()
