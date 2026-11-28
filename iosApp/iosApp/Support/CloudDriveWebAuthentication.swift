import AuthenticationServices
import Foundation
import Shared
import UIKit

/// Shows Google and Microsoft sign-in pages for Google Drive / OneDrive Screens.
/// The shared module runs the OAuth (PKCE) exchange and keeps the tokens in the Keychain.
final class CloudDriveWebAuthentication: NSObject, IosWebAuthenticationBridge, ASWebAuthenticationPresentationContextProviding {
    static let shared = CloudDriveWebAuthentication()

    private var activeSession: ASWebAuthenticationSession?

    /// Hands the bridge and the public OAuth client IDs (from Info.plist) to the shared module.
    static func register() {
        func value(_ key: String) -> String? {
            guard let raw = Bundle.main.object(forInfoDictionaryKey: key) as? String else { return nil }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("$(") else { return nil }
            return trimmed
        }
        IosDiBridge().registerCloudDriveSignIn(
            bridge: shared,
            googleClientId: value("WINGMATE_GOOGLE_IOS_CLIENT_ID"),
            microsoftClientId: value("WINGMATE_MICROSOFT_CLIENT_ID")
        )
    }

    func authenticate(url: String, callbackScheme: String, completion: @escaping (String?, String?) -> Void) {
        DispatchQueue.main.async {
            guard let authorizeURL = URL(string: url) else {
                completion(nil, "The sign-in address is not valid")
                return
            }
            let session = ASWebAuthenticationSession(
                url: authorizeURL,
                callback: .customScheme(callbackScheme)
            ) { callbackURL, error in
                // The singleton lives for the app's lifetime; clearing the session breaks the cycle.
                self.activeSession = nil
                if let callbackURL {
                    completion(callbackURL.absoluteString, nil)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    completion(nil, IosOAuthBrowser.companion.CANCELLED)
                } else {
                    completion(nil, error?.localizedDescription ?? IosOAuthBrowser.companion.CANCELLED)
                }
            }
            session.presentationContextProvider = self
            self.activeSession = session
            if !session.start() {
                self.activeSession = nil
                completion(nil, "Sign-in could not start")
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeWindows = scenes
            .filter { $0.activationState == .foregroundActive }
            .flatMap(\.windows)
        let allWindows = scenes.flatMap(\.windows)
        return activeWindows.first(where: \.isKeyWindow)
            ?? activeWindows.first
            ?? allWindows.first(where: \.isKeyWindow)
            ?? allWindows.first
            ?? ASPresentationAnchor()
    }
}
