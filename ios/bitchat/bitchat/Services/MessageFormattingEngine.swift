import BitFoundation
import Foundation
import SwiftUI

@MainActor
protocol MessageFormattingContext: AnyObject {

    var nickname: String { get }

    func isSelfMessage(_ message: BitchatMessage) -> Bool

    func senderColor(for message: BitchatMessage, isDark: Bool) -> Color

    func peerURL(for peerID: PeerID) -> URL?
}

final class MessageFormattingEngine {

    enum Patterns {
        static let hashtag: NSRegularExpression = {
            try! NSRegularExpression(pattern: "#([a-zA-Z0-9_]+)", options: [])
        }()

        static let mention: NSRegularExpression = {
            try! NSRegularExpression(pattern: "@([\\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?)", options: [])
        }()

        static let cashu: NSRegularExpression = {
            try! NSRegularExpression(pattern: "\\bcashu[AB][A-Za-z0-9._-]{40,}\\b", options: [])
        }()

        static let bolt11: NSRegularExpression = {
            try! NSRegularExpression(pattern: "(?i)\\bln(bc|tb|bcrt)[0-9][a-z0-9]{50,}\\b", options: [])
        }()

        static let lnurl: NSRegularExpression = {
            try! NSRegularExpression(pattern: "(?i)\\blnurl1[a-z0-9]{20,}\\b", options: [])
        }()

        static let lightningScheme: NSRegularExpression = {
            try! NSRegularExpression(pattern: "(?i)\\blightning:[^\\s]+", options: [])
        }()

        static let linkDetector: NSDataDetector? = {
            try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        }()

        static let quickCashuPresence: NSRegularExpression = {
            try! NSRegularExpression(pattern: "\\bcashu[AB][A-Za-z0-9._-]{40,}\\b", options: [])
        }()

        static let simplifyHTTPURL: NSRegularExpression = {
            try! NSRegularExpression(pattern: "https?://[^\\s?#]+(?:[?#][^\\s]*)?", options: [.caseInsensitive])
        }()
    }

    enum MatchType: String {
        case hashtag
        case mention
        case url
        case cashu
        case lightning
        case bolt11
        case lnurl
    }

    struct ContentMatch {
        let range: NSRange
        let type: MatchType
    }

    @MainActor
    static func formatMessage(
        _ message: BitchatMessage,
        context: MessageFormattingContext,
        colorScheme: ColorScheme
    ) -> AttributedString {
        let isDark = colorScheme == .dark
        let isSelf = context.isSelfMessage(message)

        if let cached = message.getCachedFormattedText(isDark: isDark, isSelf: isSelf) {
            return cached
        }

        var result = AttributedString()
        let baseColor: Color = isSelf ? .orange : context.senderColor(for: message, isDark: isDark)

        if message.sender == "system" {
            result = formatSystemMessage(message, isDark: isDark)
        } else {

            result = formatSenderHeader(
                message: message,
                baseColor: baseColor,
                isSelf: isSelf,
                context: context
            )

            let contentResult = formatContent(
                message.content,
                baseColor: baseColor,
                isSelf: isSelf,
                isMentioned: message.mentions?.contains(context.nickname) ?? false
            )
            result.append(contentResult)

            result.append(formatTimestamp(message.formattedTimestamp))
        }

        message.setCachedFormattedText(result, isDark: isDark, isSelf: isSelf)

        return result
    }

    @MainActor
    static func formatHeader(
        _ message: BitchatMessage,
        context: MessageFormattingContext,
        colorScheme: ColorScheme
    ) -> AttributedString {
        let isDark = colorScheme == .dark
        let isSelf = context.isSelfMessage(message)
        let baseColor: Color = isSelf ? .orange : context.senderColor(for: message, isDark: isDark)

        if message.sender == "system" {
            var style = AttributeContainer()
            style.foregroundColor = baseColor
            style.font = .bitchatSystem(size: 14, weight: .medium, design: .monospaced)
            return AttributedString(message.sender).mergingAttributes(style)
        }

        return formatSenderHeader(
            message: message,
            baseColor: baseColor,
            isSelf: isSelf,
            context: context
        )
    }

    static func extractMentions(from content: String) -> [String] {
        let nsContent = content as NSString
        let range = NSRange(location: 0, length: nsContent.length)
        let matches = Patterns.mention.matches(in: content, options: [], range: range)

        return matches.compactMap { match -> String? in
            guard match.numberOfRanges > 1 else { return nil }
            let captureRange = match.range(at: 1)
            guard let swiftRange = Range(captureRange, in: content) else { return nil }
            return String(content[swiftRange])
        }
    }

    static func containsCashuToken(_ content: String) -> Bool {
        let nsContent = content as NSString
        let range = NSRange(location: 0, length: nsContent.length)
        return Patterns.quickCashuPresence.numberOfMatches(in: content, options: [], range: range) > 0
    }

    private static func formatSystemMessage(_ message: BitchatMessage, isDark: Bool) -> AttributedString {
        var result = AttributedString()

        let content = AttributedString("* \(message.content) *")
        var contentStyle = AttributeContainer()
        contentStyle.foregroundColor = Color.gray
        contentStyle.font = .bitchatSystem(size: 12, design: .monospaced).italic()
        result.append(content.mergingAttributes(contentStyle))

        let timestamp = AttributedString(" [\(message.formattedTimestamp)]")
        var timestampStyle = AttributeContainer()
        timestampStyle.foregroundColor = Color.gray.opacity(0.5)
        timestampStyle.font = .bitchatSystem(size: 10, design: .monospaced)
        result.append(timestamp.mergingAttributes(timestampStyle))

        return result
    }

    @MainActor
    private static func formatSenderHeader(
        message: BitchatMessage,
        baseColor: Color,
        isSelf: Bool,
        context: MessageFormattingContext
    ) -> AttributedString {
        var result = AttributedString()

        let (baseName, suffix) = message.sender.splitSuffix()
        var senderStyle = AttributeContainer()
        senderStyle.foregroundColor = baseColor
        let fontWeight: Font.Weight = isSelf ? .bold : .medium
        senderStyle.font = .bitchatSystem(size: 14, weight: fontWeight, design: .monospaced)

        if let spid = message.senderPeerID, let url = context.peerURL(for: spid) {
            senderStyle.link = url
        }

        result.append(AttributedString("<@").mergingAttributes(senderStyle))
        result.append(AttributedString(baseName).mergingAttributes(senderStyle))

        if !suffix.isEmpty {
            var suffixStyle = senderStyle
            suffixStyle.foregroundColor = baseColor.opacity(0.6)
            result.append(AttributedString(suffix).mergingAttributes(suffixStyle))
        }

        result.append(AttributedString("> ").mergingAttributes(senderStyle))

        return result
    }

    private static func formatContent(
        _ content: String,
        baseColor: Color,
        isSelf: Bool,
        isMentioned: Bool
    ) -> AttributedString {

        let containsCashu = containsCashuToken(content)
        if (content.count > 4000 || content.hasVeryLongToken(threshold: 1024)) && !containsCashu {
            return formatPlainContent(content, baseColor: baseColor, isSelf: isSelf)
        }

        let matches = findAllMatches(in: content)

        var result = AttributedString()
        var lastEnd = content.startIndex

        for match in matches {
            guard let swiftRange = Range(match.range, in: content) else { continue }

            if lastEnd < swiftRange.lowerBound {
                let beforeText = String(content[lastEnd..<swiftRange.lowerBound])
                result.append(formatPlainText(beforeText, baseColor: baseColor, isSelf: isSelf, isMentioned: isMentioned))
            }

            let matchText = String(content[swiftRange])
            result.append(formatMatch(matchText, type: match.type, baseColor: baseColor, isSelf: isSelf))

            lastEnd = swiftRange.upperBound
        }

        if lastEnd < content.endIndex {
            let remainingText = String(content[lastEnd...])
            result.append(formatPlainText(remainingText, baseColor: baseColor, isSelf: isSelf, isMentioned: isMentioned))
        }

        return result
    }

    private static func findAllMatches(in content: String) -> [ContentMatch] {
        let nsContent = content as NSString
        let nsLen = nsContent.length
        let fullRange = NSRange(location: 0, length: nsLen)

        let hasMentions = content.contains("@")
        let hasHashtags = content.contains("#")
        let hasURLs = content.contains("://") || content.contains("www.") || content.contains("http")
        let hasLightning = content.lowercased().contains("ln") || content.lowercased().contains("lightning:")
        let hasCashu = content.lowercased().contains("cashu")

        let mentionMatches = hasMentions ? Patterns.mention.matches(in: content, options: [], range: fullRange) : []
        let hashtagMatches = hasHashtags ? Patterns.hashtag.matches(in: content, options: [], range: fullRange) : []
        let urlMatches = hasURLs ? (Patterns.linkDetector?.matches(in: content, options: [], range: fullRange) ?? []) : []
        let cashuMatches = hasCashu ? Patterns.cashu.matches(in: content, options: [], range: fullRange) : []
        let lightningMatches = hasLightning ? Patterns.lightningScheme.matches(in: content, options: [], range: fullRange) : []
        let bolt11Matches = hasLightning ? Patterns.bolt11.matches(in: content, options: [], range: fullRange) : []
        let lnurlMatches = hasLightning ? Patterns.lnurl.matches(in: content, options: [], range: fullRange) : []

        let mentionRanges = mentionMatches.map { $0.range(at: 0) }

        func overlapsMention(_ r: NSRange) -> Bool {
            mentionRanges.contains { NSIntersectionRange(r, $0).length > 0 }
        }

        func isStandaloneHashtag(_ r: NSRange) -> Bool {
            guard let swiftRange = Range(r, in: content) else { return false }
            if swiftRange.lowerBound == content.startIndex { return true }
            let prev = content.index(before: swiftRange.lowerBound)
            return content[prev].isWhitespace || content[prev].isNewline
        }

        func attachedToMention(_ r: NSRange) -> Bool {
            guard let swiftRange = Range(r, in: content), swiftRange.lowerBound > content.startIndex else { return false }
            var i = content.index(before: swiftRange.lowerBound)
            while true {
                let ch = content[i]
                if ch.isWhitespace || ch.isNewline { break }
                if ch == "@" { return true }
                if i == content.startIndex { break }
                i = content.index(before: i)
            }
            return false
        }

        var allMatches: [ContentMatch] = []

        for match in hashtagMatches {
            let range = match.range(at: 0)
            if !overlapsMention(range) && !attachedToMention(range) && isStandaloneHashtag(range) {
                allMatches.append(ContentMatch(range: range, type: .hashtag))
            }
        }

        for match in mentionMatches {
            allMatches.append(ContentMatch(range: match.range(at: 0), type: .mention))
        }

        for match in urlMatches where !overlapsMention(match.range) {
            allMatches.append(ContentMatch(range: match.range, type: .url))
        }

        for match in cashuMatches where !overlapsMention(match.range(at: 0)) {
            allMatches.append(ContentMatch(range: match.range(at: 0), type: .cashu))
        }

        for match in lightningMatches where !overlapsMention(match.range(at: 0)) {
            allMatches.append(ContentMatch(range: match.range(at: 0), type: .lightning))
        }

        let occupied = urlMatches.map { $0.range } + lightningMatches.map { $0.range(at: 0) }
        func overlapsOccupied(_ r: NSRange) -> Bool {
            occupied.contains { NSIntersectionRange(r, $0).length > 0 }
        }

        for match in bolt11Matches where !overlapsMention(match.range(at: 0)) && !overlapsOccupied(match.range(at: 0)) {
            allMatches.append(ContentMatch(range: match.range(at: 0), type: .bolt11))
        }

        for match in lnurlMatches where !overlapsMention(match.range(at: 0)) && !overlapsOccupied(match.range(at: 0)) {
            allMatches.append(ContentMatch(range: match.range(at: 0), type: .lnurl))
        }

        return allMatches.sorted { $0.range.location < $1.range.location }
    }

    private static func formatPlainContent(_ content: String, baseColor: Color, isSelf: Bool) -> AttributedString {
        var style = AttributeContainer()
        style.foregroundColor = baseColor
        style.font = isSelf
            ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
            : .bitchatSystem(size: 14, design: .monospaced)
        return AttributedString(content).mergingAttributes(style)
    }

    private static func formatPlainText(_ text: String, baseColor: Color, isSelf: Bool, isMentioned: Bool) -> AttributedString {
        guard !text.isEmpty else { return AttributedString() }

        var style = AttributeContainer()
        style.foregroundColor = baseColor
        style.font = isSelf
            ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
            : .bitchatSystem(size: 14, design: .monospaced)

        if isMentioned {
            style.font = style.font?.bold()
        }

        return AttributedString(text).mergingAttributes(style)
    }

    private static func formatMatch(_ text: String, type: MatchType, baseColor: Color, isSelf: Bool) -> AttributedString {
        var style = AttributeContainer()

        switch type {
        case .mention:

            let (baseName, suffix) = text.splitSuffix()
            var result = AttributedString()

            var mentionStyle = AttributeContainer()
            mentionStyle.foregroundColor = .blue
            mentionStyle.font = .bitchatSystem(size: 14, weight: .semibold, design: .monospaced)
            result.append(AttributedString(baseName).mergingAttributes(mentionStyle))

            if !suffix.isEmpty {
                var suffixStyle = mentionStyle
                suffixStyle.foregroundColor = Color.gray.opacity(0.7)
                result.append(AttributedString(suffix).mergingAttributes(suffixStyle))
            }

            return result

        case .hashtag:
            style.foregroundColor = .purple
            style.font = .bitchatSystem(size: 14, weight: .medium, design: .monospaced)

        case .url:
            style.foregroundColor = .blue
            style.font = .bitchatSystem(size: 14, design: .monospaced)
            style.underlineStyle = .single
            if let url = URL(string: text) {
                style.link = url
            }

        case .cashu:
            style.foregroundColor = .green
            style.font = .bitchatSystem(size: 14, weight: .medium, design: .monospaced)
            style.backgroundColor = Color.green.opacity(0.1)

        case .lightning, .bolt11, .lnurl:
            style.foregroundColor = .yellow
            style.font = .bitchatSystem(size: 14, weight: .medium, design: .monospaced)
            style.backgroundColor = Color.yellow.opacity(0.1)
        }

        return AttributedString(text).mergingAttributes(style)
    }

    private static func formatTimestamp(_ timestamp: String) -> AttributedString {
        let text = AttributedString(" [\(timestamp)]")
        var style = AttributeContainer()
        style.foregroundColor = Color.gray.opacity(0.5)
        style.font = .bitchatSystem(size: 10, design: .monospaced)
        return text.mergingAttributes(style)
    }
}
