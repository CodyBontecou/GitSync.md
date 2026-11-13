import Foundation

/// Small, dependency-free text diffing for the Tide diff and conflict
/// screens: line matching, a diff3-style merge that isolates the hunks both
/// sides edited, and word-level change ranges for highlighting.
enum TideTextDiff {

    // MARK: - Lines

    /// Splits text into lines without their terminators. A trailing newline
    /// does not produce an extra empty line.
    static func lines(_ text: String) -> [String] {
        var result = text.components(separatedBy: "\n")
        if result.last == "" { result.removeLast() }
        return result.map { $0.hasSuffix("\r") ? String($0.dropLast()) : $0 }
    }

    /// Index pairs (a, b) of equal, in-order elements between two arrays
    /// (Myers via `CollectionDifference`).
    static func matches<T: Hashable>(_ a: [T], _ b: [T]) -> [(Int, Int)] {
        let diff = b.difference(from: a)
        var removed = Set<Int>(), inserted = Set<Int>()
        for change in diff {
            switch change {
            case .remove(let offset, _, _): removed.insert(offset)
            case .insert(let offset, _, _): inserted.insert(offset)
            }
        }
        var pairs: [(Int, Int)] = []
        var i = 0, j = 0
        while i < a.count, j < b.count {
            if removed.contains(i) { i += 1; continue }
            if inserted.contains(j) { j += 1; continue }
            pairs.append((i, j))
            i += 1; j += 1
        }
        return pairs
    }

    // MARK: - Three-way merge

    enum Chunk: Equatable {
        /// Lines both sides agree on (unchanged, or changed the same way, or
        /// changed on one side only).
        case stable([String])
        /// Both sides changed the same region differently.
        case conflict(ConflictHunk)
    }

    struct ConflictHunk: Equatable, Identifiable {
        let id: Int
        let base: [String]
        let ours: [String]
        let theirs: [String]
        /// Nearest Markdown heading above the hunk ("## Step 3"), if any.
        let heading: String?
        /// 1-based line in "ours" where the hunk starts.
        let oursStartLine: Int
    }

    enum Choice: Equatable { case ours, theirs, both }

    /// diff3-style merge. With no common ancestor, every region that differs
    /// is a conflict.
    static func merge(base: String?, ours: String, theirs: String) -> [Chunk] {
        let o = lines(ours), t = lines(theirs)
        let b = base.map(lines) ?? []
        var chunks: [Chunk] = []
        var stable: [String] = []
        var hunkID = 0

        func flushStable() {
            if !stable.isEmpty { chunks.append(.stable(stable)); stable = [] }
        }
        func emit(base bb: ArraySlice<String>, ours oo: ArraySlice<String>, theirs tt: ArraySlice<String>, oursStart: Int) {
            let bArr = Array(bb), oArr = Array(oo), tArr = Array(tt)
            if oArr == tArr { stable += oArr; return }
            if base != nil {
                if oArr == bArr { stable += tArr; return }
                if tArr == bArr { stable += oArr; return }
            }
            flushStable()
            chunks.append(.conflict(ConflictHunk(
                id: hunkID, base: bArr, ours: oArr, theirs: tArr,
                heading: heading(above: oursStart, in: o) ?? heading(in: oArr),
                oursStartLine: oursStart + 1
            )))
            hunkID += 1
        }

        if base == nil {
            // Two-way: anchor on lines equal in both.
            var i = 0, j = 0
            for (mi, mj) in matches(o, t) {
                if mi > i || mj > j { emit(base: [], ours: o[i..<mi], theirs: t[j..<mj], oursStart: i) }
                stable.append(o[mi])
                i = mi + 1; j = mj + 1
            }
            if i < o.count || j < t.count { emit(base: [], ours: o[i...], theirs: t[j...], oursStart: i) }
            flushStable()
            return chunks
        }

        // Ancestor lines matched on both sides are the stable anchors.
        var toOurs = [Int: Int](), toTheirs = [Int: Int]()
        for (bi, oi) in matches(b, o) { toOurs[bi] = oi }
        for (bi, ti) in matches(b, t) { toTheirs[bi] = ti }

        var i = 0, j = 0, k = 0
        for anchor in 0..<b.count {
            guard let oi = toOurs[anchor], let ti = toTheirs[anchor], oi >= j, ti >= k, anchor >= i else { continue }
            if anchor > i || oi > j || ti > k {
                emit(base: b[i..<anchor], ours: o[j..<oi], theirs: t[k..<ti], oursStart: j)
            }
            stable.append(o[oi])
            i = anchor + 1; j = oi + 1; k = ti + 1
        }
        if i < b.count || j < o.count || k < t.count {
            emit(base: b[i...], ours: o[j...], theirs: t[k...], oursStart: j)
        }
        flushStable()
        return chunks
    }

    /// Joins merged chunks back into text, resolving each conflict with
    /// `choices` (unresolved hunks keep this iPhone's lines then the remote's).
    static func assemble(_ chunks: [Chunk], choices: [Int: Choice], trailingNewline: Bool) -> String {
        var out: [String] = []
        for chunk in chunks {
            switch chunk {
            case .stable(let lines): out += lines
            case .conflict(let hunk):
                switch choices[hunk.id] ?? .both {
                case .ours: out += hunk.ours
                case .theirs: out += hunk.theirs
                case .both: out += hunk.ours + hunk.theirs
                }
            }
        }
        let text = out.joined(separator: "\n")
        return trailingNewline && !text.isEmpty ? text + "\n" : text
    }

    private static func heading(above index: Int, in lines: [String]) -> String? {
        guard index > 0 else { return nil }
        for line in lines[..<min(index, lines.count)].reversed() where isHeading(line) {
            return line
        }
        return nil
    }

    private static func heading(in lines: [String]) -> String? {
        lines.first(where: isHeading)
    }

    static func isHeading(_ line: String) -> Bool {
        let trimmed = line.drop(while: { $0 == " " })
        guard trimmed.hasPrefix("#") else { return false }
        let hashes = trimmed.prefix(while: { $0 == "#" })
        return hashes.count <= 6 && trimmed.dropFirst(hashes.count).first == " "
    }

    /// "## Step 3" → "Step 3".
    static func headingTitle(_ line: String) -> String {
        line.drop(while: { $0 == " " || $0 == "#" }).trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Words

    /// Word and whitespace runs, so joining the tokens restores the text.
    static func tokens(_ text: String) -> [String] {
        var tokens: [String] = []
        var current = ""
        var currentIsSpace: Bool?
        for ch in text {
            let isSpace = ch.isWhitespace
            let isPunct = ch.isPunctuation || ch.isSymbol
            if isPunct {
                if !current.isEmpty { tokens.append(current); current = "" }
                tokens.append(String(ch))
                currentIsSpace = nil
                continue
            }
            if let flag = currentIsSpace, flag != isSpace, !current.isEmpty {
                tokens.append(current); current = ""
            }
            current.append(ch)
            currentIsSpace = isSpace
        }
        if !current.isEmpty { tokens.append(current) }
        return tokens
    }

    /// Token runs of `old` and `new`, each flagged when it differs on that side.
    static func wordRuns(old: String, new: String) -> (old: [(String, Bool)], new: [(String, Bool)]) {
        let a = tokens(old), b = tokens(new)
        // Huge lines aren't worth word-diffing.
        guard a.count * b.count < 250_000 else {
            return ([(old, old != new)], [(new, old != new)])
        }
        let pairs = matches(a, b)
        let keptA = Set(pairs.map(\.0)), keptB = Set(pairs.map(\.1))
        func runs(_ tokens: [String], kept: Set<Int>) -> [(String, Bool)] {
            var result: [(String, Bool)] = []
            for (index, token) in tokens.enumerated() {
                // Whitespace between two changed words stays highlighted.
                let changed = !kept.contains(index)
                if let last = result.last, last.1 == changed {
                    result[result.count - 1].0 += token
                } else {
                    result.append((token, changed))
                }
            }
            return result
        }
        return (runs(a, kept: keptA), runs(b, kept: keptB))
    }

    /// Word runs for a whole block of lines (joined with newlines).
    static func blockRuns(old: [String], new: [String]) -> (old: [(String, Bool)], new: [(String, Bool)]) {
        wordRuns(old: old.joined(separator: "\n"), new: new.joined(separator: "\n"))
    }
}
