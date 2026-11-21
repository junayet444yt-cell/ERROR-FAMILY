FAMILY SHIELD - one-tap family mode for Android
================================================
What it does
  Press Start once. Every website lookup of the whole phone (Chrome, other browsers, apps) is answered by a family-safe
  DNS server (CleanBrowsing Family; fallback Cloudflare for Families): adult websites are blocked and Google/Bing SafeSearch
  is forced on. Starts again by itself after a restart. Stopping needs the PIN you create at the first start.
  Only the website-name lookups go through the app. No browsing history is stored or sent anywhere by the app.

Limits (please read)
  * It blocks by website name. Adult posts/videos on normal sites (social media apps, Reddit, X ...) cannot be filtered this way.
  * Someone who knows Android can switch the VPN off in Settings > Network > VPN. Use Settings > VPN > (gear) > "Always-on VPN"
    and "Block connections without VPN" to make it harder, and add Google Family Link for real parental control.
  * Do not set "Private DNS" to a custom host in Android settings: it would override Family Mode.

Build it on GitHub (no computer program needed)
  1. Create a new GitHub repository, upload all files of this folder (the hidden .github folder usually does not upload:
     Add file > Create new file > name it  .github/workflows/build.yml  and paste the text of WORKFLOW-PASTE.txt).
  2. Actions > "Build Family Shield apk" > Run workflow (release name v1.0.0).
  3. After about 5 minutes open the repository's Releases page and download FAMILY-SHIELD.apk. Install it (allow "unknown apps"
     once; Play Protect may warn: More details > Install anyway), open it and press Start.
