import Tor
import SwiftUI
import BitFoundation
import UserNotifications

@main
struct BitchatApp: App {
    static let bundleID = Bundle.main.bundleIdentifier ?? "com.cybersiren.ios"
    static let groupID = "group.\(bundleID)"

    @StateObject private var chatViewModel: ChatViewModel
    #if os(iOS)
    @Environment(\.scenePhase) var scenePhase
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    @State private var didHandleInitialActive: Bool = false
    @State private var didEnterBackground: Bool = false
    #elseif os(macOS)
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) var appDelegate
    #endif

    private let idBridge = NostrIdentityBridge()

    init() {
        let keychain = KeychainManager()
        let idBridge = self.idBridge
        _chatViewModel = StateObject(
            wrappedValue: ChatViewModel(
                keychain: keychain,
                idBridge: idBridge,
                identityManager: SecureIdentityStateManager(keychain)
            )
        )

        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared

        GeoRelayDirectory.shared.prefetchIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(chatViewModel)
                .onAppear {
                    NotificationDelegate.shared.chatViewModel = chatViewModel

                    VerificationService.shared.configure(with: chatViewModel.meshService.getNoiseService())

                    let nickname = chatViewModel.nickname
                    DispatchQueue.global(qos: .utility).async {
                        let npub = try? idBridge.getCurrentNostrIdentity()?.npub
                        _ = VerificationService.shared.buildMyQRString(nickname: nickname, npub: npub)
                    }

                    appDelegate.chatViewModel = chatViewModel

                    NetworkActivationService.shared.start()

                    GeohashPresenceService.shared.start()

                    checkForSharedContent()
                }
                .onOpenURL { url in
                    handleURL(url)
                }
                #if os(iOS)
                .onChange(of: scenePhase) { newPhase in
                    switch newPhase {
                    case .background:

                        TorManager.shared.setAppForeground(false)
                        TorManager.shared.goDormantOnBackground()

                        Task { @MainActor in
                            chatViewModel.endGeohashSampling()
                        }

                        NostrRelayManager.shared.disconnect()
                        didEnterBackground = true
                    case .active:

                        chatViewModel.meshService.startServices()
                        TorManager.shared.setAppForeground(true)

                        if didHandleInitialActive && didEnterBackground {
                            if TorManager.shared.isAutoStartAllowed() && !TorManager.shared.isReady {
                                TorManager.shared.ensureRunningOnForeground()
                            }
                        } else {
                            didHandleInitialActive = true
                        }
                        didEnterBackground = false
                        if TorManager.shared.isAutoStartAllowed() {
                            Task.detached {
                                let _ = await TorManager.shared.awaitReady(timeout: 60)
                                await MainActor.run {

                                    TorURLSession.shared.rebuild()

                                    NostrRelayManager.shared.resetAllConnections()
                                }
                            }
                        }
                        checkForSharedContent()
                    case .inactive:
                        break
                    @unknown default:
                        break
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in

                    checkForSharedContent()
                }
                #elseif os(macOS)
                .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in

                }
                #endif
        }
        #if os(macOS)
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        #endif
    }

    private func handleURL(_ url: URL) {
        if url.scheme == "bitchat" && url.host == "share" {

            checkForSharedContent()
        }
    }

    private func checkForSharedContent() {

        guard let userDefaults = UserDefaults(suiteName: BitchatApp.groupID) else {
            return
        }

        guard let sharedContent = userDefaults.string(forKey: "sharedContent"),
              let sharedDate = userDefaults.object(forKey: "sharedContentDate") as? Date else {
            return
        }

        if Date().timeIntervalSince(sharedDate) < TransportConfig.uiShareAcceptWindowSeconds {
            let contentType = userDefaults.string(forKey: "sharedContentType") ?? "text"

            userDefaults.removeObject(forKey: "sharedContent")
            userDefaults.removeObject(forKey: "sharedContentType")
            userDefaults.removeObject(forKey: "sharedContentDate")

            DispatchQueue.main.async {
                if contentType == "url" {

                    if let data = sharedContent.data(using: .utf8),
                       let urlData = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                       let url = urlData["url"] {

                        self.chatViewModel.sendMessage(url)
                    } else {

                        self.chatViewModel.sendMessage(sharedContent)
                    }
                } else {
                    self.chatViewModel.sendMessage(sharedContent)
                }
            }
        }
    }
}

#if os(iOS)
final class AppDelegate: NSObject, UIApplicationDelegate {
    weak var chatViewModel: ChatViewModel?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil) -> Bool {
        return true
    }

    func applicationWillTerminate(_ application: UIApplication) {
        chatViewModel?.applicationWillTerminate()
    }
}
#endif

#if os(macOS)
import AppKit

final class MacAppDelegate: NSObject, NSApplicationDelegate {
    weak var chatViewModel: ChatViewModel?

    func applicationWillTerminate(_ notification: Notification) {
        chatViewModel?.applicationWillTerminate()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}
#endif

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelegate()
    weak var chatViewModel: ChatViewModel?

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let identifier = response.notification.request.identifier
        let userInfo = response.notification.request.content.userInfo

        if identifier.hasPrefix("private-") {

            if let peerID = userInfo["peerID"] as? String {
                DispatchQueue.main.async {
                    self.chatViewModel?.startPrivateChat(with: PeerID(str: peerID))
                }
            }
        }

        if let deep = userInfo["deeplink"] as? String, let url = URL(string: deep) {
            #if os(iOS)
            DispatchQueue.main.async { UIApplication.shared.open(url) }
            #else
            DispatchQueue.main.async { NSWorkspace.shared.open(url) }
            #endif
        }

        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let identifier = notification.request.identifier
        let userInfo = notification.request.content.userInfo

        if identifier.hasPrefix("private-") {

            if let peerID = userInfo["peerID"] as? String {

                Task { @MainActor in
                    if self.chatViewModel?.selectedPrivateChatPeer == PeerID(str: peerID) {
                        completionHandler([])
                    } else {
                        completionHandler([.banner, .sound])
                    }
                }
                return
            }
        }

        if identifier.hasPrefix("geo-activity-"),
           let deep = userInfo["deeplink"] as? String,
           let gh = deep.components(separatedBy: "/").last {
            if case .location(let ch) = LocationChannelManager.shared.selectedChannel, ch.geohash == gh {
                completionHandler([])
                return
            }
        }

        completionHandler([.banner, .sound])
    }
}
