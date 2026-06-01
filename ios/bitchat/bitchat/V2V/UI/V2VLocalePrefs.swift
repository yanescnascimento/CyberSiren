import Foundation
import SwiftUI

public enum V2VLocale: String, CaseIterable {
    case en = "en"
    case es = "es"
    case pt = "pt"

    public var bundleLanguageCode: String {
        switch self {
        case .en: return "en"
        case .es: return "es"
        case .pt: return "pt-BR"
        }
    }

    public var displayName: String {
        switch self {
        case .en: return "EN"
        case .es: return "ES"
        case .pt: return "PT"
        }
    }
}

public final class V2VLocalePrefs: ObservableObject {

    public static let shared = V2VLocalePrefs()

    private static let defaultsKey = "v2v.app_locale"

    @Published public private(set) var current: V2VLocale

    private init() {
        let saved = UserDefaults.standard.string(forKey: Self.defaultsKey) ?? V2VLocale.en.rawValue
        self.current = V2VLocale(rawValue: saved) ?? .en
    }

    public func setLocale(_ locale: V2VLocale) {
        current = locale
        UserDefaults.standard.set(locale.rawValue, forKey: Self.defaultsKey)
        NotificationCenter.default.post(name: .v2vLocaleChanged, object: locale)
    }

    public func string(forKey key: String) -> String {
        let code = current.bundleLanguageCode
        if let path = Bundle.main.path(forResource: code, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            let localized = NSLocalizedString(key, tableName: nil, bundle: bundle, value: "", comment: "")
            if !localized.isEmpty { return localized }
        }
        return NSLocalizedString(key, comment: "")
    }
}

public extension Notification.Name {
    static let v2vLocaleChanged = Notification.Name("v2v.localeChanged")
}
