import AuthenticationServices
import CryptoKit
import FirebaseAuth
import Foundation
import GoogleSignIn
import Security
import UIKit
import client

/// Owns native identity UI while the KMP runtime remains the source of app session state.
final class AuthCoordinator: NSObject, IosAuthHost {
  private enum AppleOperation { case signIn, prepareDeletion }

  private var appleOperation: AppleOperation?
  private var appleNonce: String?
  private var appleSignInCompletion: ((String?, String?) -> Void)?
  private var deletionPreparationCompletion: ((String?) -> Void)?
  private var deletionAuthorizationCode: String?

  func signIn(provider: String, completion: @escaping (String?, String?) -> Void) {
    switch provider {
    case "google": signInWithGoogle(completion: completion)
    case "apple": beginAppleAuthorization(operation: .signIn, signInCompletion: completion)
    default: completion(nil, "That sign-in method isn't available.")
    }
  }

  func prepareAccountDeletion(completion: @escaping (String?) -> Void) {
    guard let user = Auth.auth().currentUser else {
      completion(nil)
      return
    }
    let providers = Set(user.providerData.map(\.providerID))
    if providers.contains("apple.com") {
      deletionPreparationCompletion = completion
      beginAppleAuthorization(operation: .prepareDeletion, signInCompletion: nil)
    } else if providers.contains("google.com") {
      prepareGoogleDeletion(user: user, completion: completion)
    } else {
      completion(nil)
    }
  }

  func finishAccountDeletion(completion: @escaping (String?) -> Void) {
    let finishFirebaseUser: () -> Void = {
      guard let user = Auth.auth().currentUser else {
        completion(nil)
        return
      }
      user.delete { error in
        if error != nil {
          completion("Your Dayfold account was deleted, but the sign-in credential couldn't be removed automatically.")
        } else {
          completion(nil)
        }
      }
    }

    guard let code = deletionAuthorizationCode else {
      finishFirebaseUser()
      return
    }
    deletionAuthorizationCode = nil
    Auth.auth().revokeToken(withAuthorizationCode: code) { _ in
      // The Dayfold account is already gone. Attempt Firebase deletion even if
      // Apple's revoke endpoint is temporarily unavailable.
      finishFirebaseUser()
    }
  }

  private func signInWithGoogle(completion: @escaping (String?, String?) -> Void) {
    guard let presenter = Self.presentingViewController() else {
      completion(nil, "Couldn't open Google sign-in.")
      return
    }
    GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
      if let error = error as NSError?, error.code == GIDSignInError.canceled.rawValue {
        completion(nil, nil)
        return
      }
      guard error == nil,
            let idToken = result?.user.idToken?.tokenString,
            let accessToken = result?.user.accessToken.tokenString else {
        completion(nil, "Google sign-in couldn't be completed.")
        return
      }
      let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
      Auth.auth().signIn(with: credential) { _, authError in
        guard authError == nil else {
          completion(nil, "Google sign-in couldn't be completed.")
          return
        }
        Self.firebaseIdToken(completion: completion)
      }
    }
  }

  private func prepareGoogleDeletion(user: User, completion: @escaping (String?) -> Void) {
    guard let presenter = Self.presentingViewController() else {
      completion("Couldn't open Google sign-in.")
      return
    }
    GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
      guard error == nil,
            let idToken = result?.user.idToken?.tokenString,
            let accessToken = result?.user.accessToken.tokenString else {
        completion("Sign in again to confirm account deletion.")
        return
      }
      let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
      user.reauthenticate(with: credential) { _, reauthError in
        completion(reauthError == nil ? nil : "Sign in again to confirm account deletion.")
      }
    }
  }

  private func beginAppleAuthorization(
    operation: AppleOperation,
    signInCompletion: ((String?, String?) -> Void)?
  ) {
    guard let nonce = Self.randomNonceString() else {
      signInCompletion?(nil, "Apple sign-in couldn't be started.")
      deletionPreparationCompletion?("Apple sign-in couldn't be started.")
      deletionPreparationCompletion = nil
      return
    }
    appleOperation = operation
    appleNonce = nonce
    appleSignInCompletion = signInCompletion
    let request = ASAuthorizationAppleIDProvider().createRequest()
    request.requestedScopes = operation == .signIn ? [.fullName, .email] : []
    request.nonce = Self.sha256(nonce)
    let controller = ASAuthorizationController(authorizationRequests: [request])
    controller.delegate = self
    controller.presentationContextProvider = self
    controller.performRequests()
  }

  private static func firebaseIdToken(completion: @escaping (String?, String?) -> Void) {
    Auth.auth().currentUser?.getIDToken { token, error in
      if let token { completion(token, nil) }
      else if error != nil { completion(nil, "Sign-in couldn't be completed.") }
      else { completion(nil, "Sign-in couldn't be completed.") }
    }
  }

  private static func presentingViewController() -> UIViewController? {
    let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .first(where: { $0.activationState == .foregroundActive })
    var controller = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
    while let presented = controller?.presentedViewController { controller = presented }
    return controller
  }

  private static func sha256(_ input: String) -> String {
    SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  private static func randomNonceString(length: Int = 32) -> String? {
    precondition(length > 0)
    let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
    var result = ""
    var remaining = length
    while remaining > 0 {
      var bytes = [UInt8](repeating: 0, count: 16)
      guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return nil }
      for byte in bytes where remaining > 0 && Int(byte) < charset.count {
        result.append(charset[Int(byte)])
        remaining -= 1
      }
    }
    return result
  }
}

extension AuthCoordinator: ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
  func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
    Self.presentingViewController()?.view.window ?? ASPresentationAnchor()
  }

  func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
    guard let apple = authorization.credential as? ASAuthorizationAppleIDCredential,
          let nonce = appleNonce,
          let identityToken = apple.identityToken,
          let tokenString = String(data: identityToken, encoding: .utf8) else {
      finishAppleWithError("Apple sign-in couldn't be completed.")
      return
    }
    let credential = OAuthProvider.appleCredential(
      withIDToken: tokenString,
      rawNonce: nonce,
      fullName: apple.fullName
    )

    switch appleOperation {
    case .signIn:
      Auth.auth().signIn(with: credential) { _, error in
        guard error == nil else {
          self.finishAppleWithError("Apple sign-in couldn't be completed.")
          return
        }
        let completion = self.appleSignInCompletion
        self.resetAppleOperation()
        if let completion { Self.firebaseIdToken(completion: completion) }
      }
    case .prepareDeletion:
      guard let user = Auth.auth().currentUser else {
        finishAppleWithError("Sign in again to confirm account deletion.")
        return
      }
      if let authorizationCode = apple.authorizationCode {
        deletionAuthorizationCode = String(data: authorizationCode, encoding: .utf8)
      }
      user.reauthenticate(with: credential) { _, error in
        let completion = self.deletionPreparationCompletion
        self.resetAppleOperation()
        self.deletionPreparationCompletion = nil
        completion?(error == nil ? nil : "Sign in again to confirm account deletion.")
      }
    case .none:
      resetAppleOperation()
    }
  }

  func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
    let nsError = error as NSError
    if nsError.domain == ASAuthorizationError.errorDomain,
       nsError.code == ASAuthorizationError.canceled.rawValue {
      finishAppleWithError(nil)
    } else {
      finishAppleWithError("Apple sign-in couldn't be completed.")
    }
  }

  private func finishAppleWithError(_ message: String?) {
    appleSignInCompletion?(nil, message)
    deletionPreparationCompletion?(message ?? "Account deletion was cancelled.")
    deletionPreparationCompletion = nil
    resetAppleOperation()
  }

  private func resetAppleOperation() {
    appleOperation = nil
    appleNonce = nil
    appleSignInCompletion = nil
  }
}
