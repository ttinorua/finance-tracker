# Household budget — Firebase setup

The household budget (shared with a partner) runs on a small Firebase project of your own. It's
free for a household. Set it up once:

1. **Create the project.** Go to <https://console.firebase.google.com>, sign in with your Google
   account, click *Create a project*, name it (e.g. `finance-tracker`), and skip Google Analytics.
2. **Turn on anonymous sign-in.** *Build → Authentication → Get started → Sign-in method →
   Anonymous → Enable → Save.* (The phones sign in invisibly; there's no account or password.)
3. **Create the database.** *Build → Firestore Database → Create database*, choose the location
   `eur3 (europe-west)`, and start in **production mode**.
4. **Paste the security rules.** In Firestore, open the *Rules* tab, replace everything with the
   contents of [`firestore.rules`](firestore.rules), and click *Publish*.
5. **Register the app.** *Project settings (gear) → General → Your apps → Android*. Package name:
   `com.financetracker.app`. Click *Register app*; you can skip downloading the config file and the
   remaining steps.
6. **Copy three values into the build environment** (the same place as the other keys), from
   *Project settings → General*:
   - `FIREBASE_PROJECT_ID` — the *Project ID*
   - `FIREBASE_API_KEY` — the *Web API key*
   - `FIREBASE_APP_ID` — under *Your apps*, the Android app's *App ID* (`1:…:android:…`)

The next build includes the household budget. Everything the two phones share is encrypted on the
phones first, so the project only stores data it can't read.
