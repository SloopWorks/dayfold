package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.sloopworks.dayfold.client.FirebaseSignIn
import kotlinx.coroutines.tasks.await

/**
 * Android implementation of the [FirebaseSignIn] seam (S2, ADR 0023/0027).
 *
 * Credential Manager surfaces the Google account picker → Google ID token →
 * Firebase Auth `signInWithCredential` exchanges it for a **Firebase** ID token,
 * which our backend `/auth/firebase` verifies (iss=securetoken.google.com). Returns
 * null on cancel / error / non-Google provider. The host treats null as a no-op; debug-provider
 * authentication is a separate explicit command and is never a cancellation fallback.
 *
 * @param activity the current Activity — Credential Manager needs it to show native UI. The
 *   adapter stays Activity-scoped and is never retained by [DayfoldRuntimeViewModel].
 * @param webClientId the OAuth **Web** client id (`R.string.default_web_client_id`,
 *   emitted by the google-services plugin from google-services.json's type-3 client).
 */
class AndroidFirebaseSignIn(
  private val activity: ComponentActivity,
  private val webClientId: String,
) : FirebaseSignIn {
  override suspend fun idToken(provider: String): String? {
    if (provider != "google") return null   // Apple/others not wired at S2

    val option = GetGoogleIdOption.Builder()
      .setServerClientId(webClientId)
      .setFilterByAuthorizedAccounts(false)   // any Google account, not just previously-used
      .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

    val googleIdToken = try {
      val result = CredentialManager.create(activity).getCredential(activity, request)
      val cred = result.credential
      if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        GoogleIdTokenCredential.createFrom(cred.data).idToken
      } else {
        return null
      }
    } catch (e: GetCredentialException) {
      return null   // user cancelled / no credential / misconfig
    }

    // Exchange the Google ID token for a Firebase ID token.
    val firebaseCred = GoogleAuthProvider.getCredential(googleIdToken, null)
    val authResult = FirebaseAuth.getInstance().signInWithCredential(firebaseCred).await()
    return authResult.user?.getIdToken(false)?.await()?.token
  }
}
