import Foundation

extension String {

    func hasVeryLongToken(threshold: Int) -> Bool {
        var current = 0
        for ch in self {
            if ch.isWhitespace || ch.isNewline {
                if current >= threshold { return true }
                current = 0
            } else {
                current += 1
                if current >= threshold { return true }
            }
        }
        return current >= threshold
    }

    func extractCashuLinks(max: Int = 3) -> [String] {
        let regex = MessageFormattingEngine.Patterns.cashu
        let ns = self as NSString
        let range = NSRange(location: 0, length: ns.length)
        var found: [String] = []
        for m in regex.matches(in: self, range: range) {
            if m.numberOfRanges > 0 {
                let token = ns.substring(with: m.range(at: 0))
                let enc = token.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-_"))) ?? token
                found.append("cashu:\(enc)")
                if found.count >= max { break }
            }
        }
        return found
    }

    func extractLightningLinks(max: Int = 3) -> [String] {
        var results: [String] = []
        let ns = self as NSString
        let full = NSRange(location: 0, length: ns.length)

        for m in MessageFormattingEngine.Patterns.lightningScheme.matches(in: self, range: full) {
            let s = ns.substring(with: m.range(at: 0))
            results.append(s)
            if results.count >= max { return results }
        }

        for m in MessageFormattingEngine.Patterns.bolt11.matches(in: self, range: full) {
            let s = ns.substring(with: m.range(at: 0))
            results.append("lightning:\(s)")
            if results.count >= max { return results }
        }

        for m in MessageFormattingEngine.Patterns.lnurl.matches(in: self, range: full) {
            let s = ns.substring(with: m.range(at: 0))
            results.append("lightning:\(s)")
            if results.count >= max { return results }
        }
        return results
    }
}
