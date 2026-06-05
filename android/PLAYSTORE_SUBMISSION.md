# Play Store Submission Guide — Gitsync.md v1.0.0

Complete step-by-step guide to submit Gitsync.md Android to Google Play Store.

## Pre-Submission Checklist

- [x] App version set to 1.0.0
- [x] versionCode = 1
- [x] Release build successful (APK: 14MB, AAB: 8.3MB)
- [x] All 127 core tests passing
- [x] ProGuard/R8 minification enabled
- [ ] Release keystore generated
- [ ] Signing configured
- [ ] Play Store Console account created
- [ ] Developer Program enrollment complete

## Step 1: Generate Release Keystore

A signing keystore is required to sign the release APK/Bundle for Play Store submission.

### Generate New Keystore (if you don't have one)

```bash
# Create signing keystore
keytool -genkey -v -keystore ~/syncmd-release.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias syncmd-key \
  -keypass <key-password> \
  -storepass <keystore-password>
```

**What to enter:**
- First name: Gitsync.md
- Last name: Git Client
- Organizational Unit: Android
- Organization: Cody Bontecou
- City: San Francisco (or your city)
- State: CA (or your state)
- Country Code: US

### Secure Your Keystore

```bash
# Move keystore to secure location
mv ~/syncmd-release.keystore ~/.android/syncmd-release.keystore
chmod 600 ~/.android/syncmd-release.keystore

# Export keystore details for safekeeping
# IMPORTANT: Save these in a secure password manager!
# - Keystore path: ~/.android/syncmd-release.keystore
# - Keystore password: <keystore-password>
# - Key alias: syncmd-key
# - Key password: <key-password>
```

## Step 2: Configure Release Signing

Update `app/build.gradle.kts` with signing configuration:

```kotlin
android {
    // ... other config ...
    
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("SYNCMD_KEYSTORE_FILE") 
                ?: "${System.getProperty("user.home")}/.android/syncmd-release.keystore")
            storePassword = System.getenv("SYNCMD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SYNCMD_KEY_ALIAS") ?: "syncmd-key"
            keyPassword = System.getenv("SYNCMD_KEY_PASSWORD")
        }
    }
    
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

## Step 3: Build Signed Release

### Option A: Build with Environment Variables

```bash
# Set environment variables
export SYNCMD_KEYSTORE_FILE=~/.android/syncmd-release.keystore
export SYNCMD_KEYSTORE_PASSWORD=<keystore-password>
export SYNCMD_KEY_ALIAS=syncmd-key
export SYNCMD_KEY_PASSWORD=<key-password>

# Build signed bundle (preferred for Play Store)
cd /Users/codybontecou/dev/sync-md-android
gradle bundleRelease

# Output: app/build/outputs/bundle/release/app-release.aab
```

### Option B: Build Unsigned (for manual signing later)

```bash
gradle assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

### Sign APK Manually (if needed)

```bash
# Sign unsigned APK with jarsigner
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore ~/.android/syncmd-release.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  syncmd-key

# Verify signature
jarsigner -verify -verbose app/build/outputs/apk/release/app-release-unsigned.apk
```

## Step 4: Prepare Play Store Assets

### 4.1 App Icon

The app icon is used in Play Store listing. Must be:
- **Format**: PNG or JPEG
- **Size**: 512 × 512 px
- **Transparency**: PNG with alpha channel supported

**Location in project**: `app/src/main/ic_launcher-playstore.png`

Current icon is already configured in app theme.

### 4.2 Screenshots (Required)

Upload **at least 6 screenshots per device type**. Maximum 8 screenshots per device type.

**Phone Screenshots** (Required)
- **Dimensions**: 1080 × 1920 px or 1242 × 2688 px
- **Format**: PNG or JPEG
- **Count**: 6-8 screenshots minimum

**Suggested Screenshots for Gitsync.md**
1. Status screen (showing repository health)
2. Commit screen (showing staging workflow)
3. Branch screen (showing branch list and switching)
4. History screen (showing commit history)
5. Merge/conflict screen (showing conflict resolution)
6. Settings screen (showing repository management)
7. Dark mode view (showing theme support)
8. File diff viewer (showing diff capabilities)

**Tablet Screenshots** (Optional but recommended)
- **Dimensions**: 1200 × 1920 px
- **Count**: 6-8 screenshots minimum

**Generating Screenshots**

```bash
# Use iOS simulator screenshots script (if available)
# Or manually:
# 1. Install app on device/emulator
# 2. Navigate to each screen
# 3. Take screenshots using adb or device interface
# 4. Edit in Photoshop/Figma to add annotations
```

### 4.3 Feature Graphic

A banner image showcasing the app's main features.

- **Dimensions**: 1024 × 500 px
- **Format**: PNG or JPEG
- **Text**: Should clearly describe app (e.g., "Git Client for Android")
- **Design**: Clean, professional, highlights key value proposition

### 4.4 App Description

**Short Description** (80 characters max)
```
Manage GitHub repos safely on Android
```

**Full Description** (4000 characters max)
```
Gitsync.md is a pure Kotlin git client for Android that puts safety first.

FEATURES
✓ Safe Pull Operations - Automatic merge analysis with conflict detection
✓ Staging Workflow - Stage/unstage files and create clean commits
✓ Branch Management - Switch, create, delete, and merge branches
✓ Conflict Resolution - Three-way diff viewer with multiple strategies
✓ History & Recovery - View history, revert commits, manage stashes
✓ Repository Management - Add multiple repos with persistent storage
✓ Dark Mode - Full Material3 theming with dark/light toggle

REQUIREMENTS
• Android 9.0 (API 28) or higher
• Git binary installed on device
• 50MB free storage

ABOUT
Gitsync.md focuses on providing a safe, intuitive interface for git operations on mobile. Unlike other solutions, every operation is carefully validated to prevent data loss. All git operations are performed using your device's native git binary.

No push support yet? Push and GitHub OAuth will be added in v1.1.0.

SUPPORT
Visit the GitHub repository for bug reports, feature requests, and documentation.
```

**Category**
- Productivity

**Content Rating**
- Select "PEGI 3" or "General Audiences" when prompted
- No restricted content, no ads, no in-app purchases

**Pricing**
- Paid app: one-time upfront purchase
- Default price: USD $9.99
- No subscriptions, ads, or in-app purchases

> Note: Google Play lets paid apps become free, but apps that have already been offered free cannot later become paid under the same package name. If this package has already been offered free, create a new paid app/package instead.

## Step 5: Set Up Play Store Console

### 5.1 Create Google Play Developer Account

1. Go to [play.google.com/console](https://play.google.com/console)
2. Sign in with Google Account
3. Accept Google Play terms and pay $25 one-time developer fee
4. Create app listing

### 5.2 Create New App

1. Click "Create app"
2. Enter app name: "Gitsync.md - Git Client for Android"
3. Select "Apps" as app type
4. Choose **Paid** when asked whether the app is free or paid
5. Confirm you'll follow Google Play policies

### 5.3 Complete App Listing

1. **App details** → Fill in:
   - App name: "Gitsync.md"
   - Short description
   - Full description
   - Developer contact email
   - Privacy policy URL (if applicable)

2. **App category** → Select "Productivity"

3. **Content rating questionnaire** → Complete:
   - Usually defaults to "General Audiences"
   - No restricted content questions

4. **Target audience** → Set:
   - Minimum age: 3+
   - Content rating: PEGI 3 / USK 0 / T / General

5. **Store listing** → Upload:
   - App icon (512×512 PNG)
   - Feature graphic (1024×500 PNG)
   - Phone screenshots (6-8, 1080×1920 or 1242×2688)
   - Tablet screenshots (optional, 1200×1920)

6. **App pricing** → Configure:
   - Go to **Products → App pricing**
   - Set the default price to **USD $9.99**
   - Apply generated local prices for target countries/regions
   - Confirm there are no in-app products or subscriptions configured

7. **Release notes** → Add:
   ```
   Gitsync.md v1.0.0 Initial Release
   
   - Safe pull operations with automatic merge analysis
   - Full staging workflow for creating clean commits
   - Complete branch management (switch, create, delete, merge)
   - Three-way conflict resolution
   - Commit history with revert capability
   - Stash and tag management
   - Repository management with persistent storage
   - Dark mode support with Material3 theming
   - 127 core tests ensuring stability
   
   Minimum requirements:
   - Android 9.0 (API 28)
   - Git binary installed on device
   ```

## Step 6: Submit to Internal Testing

**Internal testing** allows you to test the release before public submission.

### 6.1 Upload Build

1. Go to **Testing** → **Internal testing**
2. Click **Create new release**
3. Upload signed AAB file: `app/build/outputs/bundle/release/app-release.aab`
4. Add release notes (as above)
5. Click **Save and review**

### 6.2 Add Testers

1. Click **Manage internal testers**
2. Create a mailing list or select internal Google account
3. Add yourself and any team members
4. Save

### 6.3 Test the Release

1. Open internal testing link on Android device
2. Install the app from Play Store
3. Test all features:
   - Add repository
   - Pull/fetch operations
   - Create commits
   - Switch branches
   - Resolve conflicts
   - View history
   - Dark/light theme toggle
4. Check for crashes, memory leaks, UI issues
5. Verify no debug logging appears

## Step 7: Submit for Review

Once internal testing passes, submit to production review.

### 7.1 Prepare Production Release

1. Go to **Releases** → **Production**
2. Click **Create new release**
3. Upload signed AAB file
4. Add release notes

### 7.2 Submit for Review

1. Click **Save and review**
2. Review all store listing details
3. Confirm:
   - All screenshots uploaded
   - Description complete
   - Content rating set
   - Privacy policy (if required)
   - Target age rating appropriate
4. Click **Submit release to review**

### 7.3 Wait for Google Play Review

- **Typical review time**: 1-2 days
- **Max review time**: 7 days
- You'll receive email updates on review status

### 7.4 Address Review Feedback (if needed)

If Google rejects the app:
1. Read the rejection reason carefully
2. Make necessary changes
3. Increment versionCode (e.g., 2)
4. Rebuild release
5. Resubmit

## Step 8: Go Live! 🚀

Once approved:
1. Google Play will automatically roll out the release
2. App becomes visible in Play Store
3. Users can download and install
4. Monitor crash reports and reviews

### Post-Launch Monitoring

- Check crash reports in Play Console
- Monitor user reviews
- Track install/uninstall rates
- Plan v1.1.0 features (push, auth)

## Release Build Artifacts

**Generated Files**
- **app-release.aab** (8.3 MB) - Bundle for Play Store
- **app-release-unsigned.apk** (14 MB) - APK for testing
- **mapping.txt** - ProGuard mapping file (for crash debugging)

**Where to Find Them**
```
app/build/outputs/bundle/release/app-release.aab
app/build/outputs/apk/release/app-release-unsigned.apk
app/build/outputs/mapping/release/mapping.txt
```

## Troubleshooting

### Build Fails with "No credentials for signing"
**Solution**: Ensure environment variables are set:
```bash
export SYNCMD_KEYSTORE_PASSWORD=<password>
export SYNCMD_KEY_PASSWORD=<password>
```

### "Minification failed"
**Solution**: Check `app/proguard-rules.pro` for errors. Ensure all keep rules are correct.

### "Upload failed: Invalid signature"
**Solution**: Ensure signing configuration is correct. Re-sign the APK:
```bash
jarsigner -verify app/build/outputs/apk/release/app-release-unsigned.apk
```

### App Crashes on Release Build
**Solution**: Check ProGuard mapping file. The app might be obfuscating critical classes. Add keep rules in proguard-rules.pro.

### "Size limit exceeded" (over 100MB)
**Solution**: Current app is only 14MB. No issue. AAB (8.3MB) is even smaller after Play Store optimization.

## FAQ

**Q: Why AAB instead of APK?**
A: Google Play requires AAB (Android App Bundle) for new apps. It's more efficient and automatically generates optimized APKs for each device configuration.

**Q: Can I use the same keystore for updates?**
A: **YES** - Always use the same keystore for the same app. Store it securely and never lose it.

**Q: What happens if I lose the keystore?**
A: You won't be able to update the app. You'd have to publish a new app with a different package name.

**Q: How often can I release updates?**
A: As often as you want. Increments versionCode by 1 for each release.

**Q: Will users need to update automatically?**
A: No. Updates appear in Play Store with an "Update" button. Users choose to update when ready.

**Q: Can I rollback a release?**
A: You can unpublish the app, but can't remove it from devices that already installed it. For bugs, release a new version with a fix.

## Next Steps

1. Generate release keystore (if needed)
2. Configure signing in build.gradle
3. Build signed release bundle
4. Test on internal testing track
5. Prepare Play Store assets (screenshots, icon, descriptions)
6. Set up Play Store Console
7. Submit for internal testing
8. Submit for public review
9. Go live! 🚀

---

**Current Status**: Build artifacts ready  
**versionCode**: 1  
**versionName**: 1.0.0  
**Release APK**: 14 MB  
**Release Bundle**: 8.3 MB  
**Build Type**: Release (minified + resource shrinking)
