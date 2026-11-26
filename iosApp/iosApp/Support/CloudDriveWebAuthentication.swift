import AuthenticationServices
import Foundation
import Shared
import UIKit

/// Shows Google and Microsoft sign-in pages for Google Drive / OneDrive Screens.
/// The shared module runs the OAuth (PKCE) exchange and keeps the tokens in the Keychain.
/// The callback scheme (Google's reversed client ID, or `msauth.com.hojmoseit.wingmate`)
/// has to be listed in Info.plist. Otherwise the system sheet refuses to start.
final class CloudDriveWebAuthentication: NSObject, IosWebAuthenticationBridge, ASWebAuthenticationPresentationContextProviding {
    static let shared = CloudDriveWebAuthentication()

    private var activeSession: ASWebAuthenticationSession?

    /// Hands the bridge and the public OAuth client IDs (from Info.plist) to the shared module.
    static func register() {
        func value(_ key: String) -> String? {
            (Bundle.main.object(forInfoDictionaryKey: key) as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
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
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first ?? ASPresentationAnchor()
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
