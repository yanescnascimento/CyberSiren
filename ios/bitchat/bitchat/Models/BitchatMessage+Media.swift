import BitFoundation
import Foundation

extension BitchatMessage {
    enum Media {
        case voice(URL)
        case image(URL)

        var url: URL {
            switch self {
            case .voice(let url), .image(let url):
                return url
            }
        }
    }

    private struct Cache {
        let filesDir: URL?

        static let shared = Cache()
        private init() {
            do {
                let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                let filesDir = base.appendingPathComponent("files", isDirectory: true)
                try FileManager.default.createDirectory(at: filesDir, withIntermediateDirectories: true, attributes: nil)
                self.filesDir = filesDir
            } catch {
                filesDir = nil
            }
        }
    }

    func mediaAttachment(for nickname: String) -> Media? {
        guard let baseDirectory = Cache.shared.filesDir else { return nil }

        func url(for category: MimeType.Category) -> URL? {
            guard content.hasPrefix(category.messagePrefix),
                  let filename = String(content.dropFirst(category.messagePrefix.count)).trimmedOrNilIfEmpty
            else {
                return nil
            }

            let subdir = sender == nickname ? "\(category.mediaDir)/outgoing" : "\(category.mediaDir)/incoming"

            let directory = baseDirectory.appendingPathComponent(subdir, isDirectory: true)
            return directory.appendingPathComponent(filename)
        }

        if let url = url(for: .audio) {
            return .voice(url)
        }
        if let url = url(for: .image) {
            return .image(url)
        }
        return nil
    }
}
